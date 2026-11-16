package com.pooy.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.pooy.vpn.MainActivity
import com.pooy.vpn.R
import com.pooy.vpn.core.ConfigParser
import com.pooy.vpn.core.XrayConfigBuilder
import com.pooy.vpn.core.XrayCore
import com.pooy.vpn.data.ProfileStore
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Data path:  apps -> TUN (VpnService) -> hev-socks5-tunnel -> 127.0.0.1 SOCKS5 inbound (Xray) -> outbound -> server
 * POOOY itself is excluded from the VPN, so Xray's own sockets reach the server directly (no loop, no protect() needed).
 * Nothing runs until the user connects; everything is torn down on disconnect.
 */
class PooyVpnService : VpnService() {

    /** The one "session thread": every SessionPorts/SessionEvents call and every VpnSession method runs here. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "pooy-timer").apply { isDaemon = true }
    }
    private var watchdog: ScheduledExecutorService? = null // session thread only
    private var netCallback: ConnectivityManager.NetworkCallback? = null // session thread only
    private var netTracker: UnderlyingNetworkTracker<Network>? = null // session thread only

    private val session = VpnSession(
        ports = Ports(),
        events = Events(),
        scheduler = SessionScheduler { delayMs, task -> schedule(delayMs, task) },
    )

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        File(cacheDir, TUN_CONFIG).delete() // leftover from a crashed session (contains the per-session SOCKS credentials)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // SERVICE_INTERFACE: the system starting us for Always-on VPN (uses the active profile).
            ACTION_START, VpnService.SERVICE_INTERFACE -> {
                goForeground(notification(getString(R.string.notif_connecting)))
                val id = intent?.getStringExtra(EXTRA_PROFILE_ID)
                val gen = session.newRequest()
                VpnController.connecting(null)
                post { session.start(id, gen) }
            }
            ACTION_STOP -> {
                session.newRequest() // aborts a start/reconnect that is still pending
                post { session.stop() }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // User switched VPN off in system settings, or another VPN app took over (the system closes the TUN itself).
        session.newRequest()
        post { session.stop() }
    }

    override fun onDestroy() {
        session.newRequest()
        // Ordered after any start that is still in flight (which aborts as stale).
        post { session.destroy() }
        worker.shutdown()
        timer.shutdownNow()
        super.onDestroy()
    }

    private fun post(block: () -> Unit) {
        try {
            worker.execute(block)
        } catch (ignored: RejectedExecutionException) {
            // service is being destroyed
        }
    }

    private fun schedule(delayMs: Long, task: () -> Unit) {
        try {
            timer.schedule(Runnable { post(task) }, delayMs, TimeUnit.MILLISECONDS)
        } catch (ignored: RejectedExecutionException) {
            // destroyed
        }
    }

    // ------------------------------------------------------------------ session ports (session thread)

    private inner class Ports : SessionPorts {
        override fun prepare(profileId: String?): PreparedConnection {
            val data = ProfileStore(applicationContext).load()
            val profile = data.profiles.firstOrNull { it.id == (profileId ?: data.activeId) }
                ?: throw IllegalStateException(getString(R.string.err_no_profile))
            val server = ConfigParser.parse(profile.raw)
            val port = freePort()
            val user = token()
            val pass = token()
            return PreparedConnection(profile.id, profile.name, XrayConfigBuilder.runtime(server, port, user, pass), port, user, pass)
        }

        override fun startCore(p: PreparedConnection) {
            XrayCore.init(applicationContext)
            XrayCore.start(p.coreConfig)
        }

        override fun stopCore() = XrayCore.stop()

        override fun isCoreRunning(): Boolean = XrayCore.isRunning

        override fun establishTun(): TunHandle? = buildTun()?.let { PfdTun(it) }

        override fun startTunnel(p: PreparedConnection, tun: TunHandle) {
            val cfg = File(cacheDir, TUN_CONFIG)
            try {
                cfg.writeText(tunYaml(p.socksPort, p.user, p.pass))
                cfg.setReadable(false, false)
                cfg.setReadable(true, true)
                cfg.setWritable(false, false)
                cfg.setWritable(true, true)
                TProxyService.TProxyStartService(cfg.absolutePath, tun.fd)
            } catch (t: Throwable) {
                cfg.delete() // credentials never outlive a failed initialisation (the session also calls stopTunnel)
                throw t
            }
        }

        override fun stopTunnel() {
            try {
                TProxyService.TProxyStopService()
            } finally {
                File(cacheDir, TUN_CONFIG).delete()
            }
        }

        override fun watchCore(onDead: (() -> Unit)?) {
            watchdog?.shutdownNow()
            watchdog = null
            if (onDead == null) {
                XrayCore.onUnexpectedStop = null
                return
            }
            XrayCore.onUnexpectedStop = { post(onDead) } // delivered on the session thread
            // Cheap liveness check in case the core dies without invoking its shutdown callback.
            val ses = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "pooy-watchdog").apply { isDaemon = true } }
            ses.scheduleWithFixedDelay({ if (!XrayCore.isRunning) post(onDead) }, WATCHDOG_SECONDS, WATCHDOG_SECONDS, TimeUnit.SECONDS)
            watchdog = ses
        }
    }

    private class PfdTun(private val pfd: ParcelFileDescriptor) : TunHandle {
        override val fd: Int get() = pfd.fd
        override fun close() {
            try {
                pfd.close()
            } catch (ignored: IOException) {
                // ignore
            }
        }
    }

    // ------------------------------------------------------------------ session events (session thread)

    private inner class Events : SessionEvents {
        override fun connecting(name: String?) {
            VpnController.connecting(name)
            postNotification(notification(getString(R.string.notif_connecting)))
        }

        override fun connected(name: String) {
            VpnController.connected(name)
            postNotification(notification(getString(R.string.notif_connected, name)))
            registerNetworkCallback()
        }

        override fun blocked(message: String) {
            unregisterNetworkCallback()
            VpnController.blocked(getString(R.string.err_blocked, message))
            postNotification(notification(getString(R.string.notif_blocked)))
        }

        override fun failed(message: String) {
            unregisterNetworkCallback()
            VpnController.failed(message)
        }

        override fun error(message: String) = VpnController.reportError(message)

        override fun disconnected() {
            unregisterNetworkCallback()
            VpnController.disconnected()
        }

        override fun finished() {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        override fun describe(t: Throwable): String = friendly(t)
    }

    /**
     * Tells the system which physical network carries the VPN, so metering/capabilities follow Wi-Fi <-> mobile
     * changes. POOOY itself is excluded from the VPN, so its default network is the underlying one; networks
     * that report TRANSPORT_VPN are ignored defensively. The tracker makes the callbacks order-independent (a late
     * "lost" of the old network cannot clear the new one). Not verified on a device.
     */
    private fun registerNetworkCallback() {
        unregisterNetworkCallback()
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val tracker = UnderlyingNetworkTracker<Network> { n ->
            try {
                setUnderlyingNetworks(if (n == null) null else arrayOf(n))
            } catch (e: Exception) {
                Log.w(TAG, "setUnderlyingNetworks failed: ${e.javaClass.simpleName}")
            }
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                tracker.onAvailable(network)
            }

            override fun onLost(network: Network) {
                tracker.onLost(network)
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
            netTracker = tracker
        } catch (e: Exception) {
            tracker.release()
            Log.w(TAG, "Network callback not registered: ${e.javaClass.simpleName}")
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = netCallback
        val tracker = netTracker
        netCallback = null
        netTracker = null
        tracker?.release() // callbacks that are still in flight are ignored from here on
        if (cb == null) return
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        } catch (ignored: Exception) {
            // not registered
        }
    }

    // ------------------------------------------------------------------ tunnel

    private fun buildTun(): ParcelFileDescriptor? {
        val b = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            .addAddress(TUN_V4, 30)
            .addAddress(TUN_V6, 126)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)          // route IPv6 into the tunnel too, so it cannot leak around the VPN
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .addDnsServer("2606:4700:4700::1111")
        try {
            b.addDisallowedApplication(packageName)   // our own (Xray) sockets bypass the VPN
        } catch (ignored: PackageManager.NameNotFoundException) {
            // cannot happen for our own package
        }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        return b.establish()
    }

    private fun tunYaml(port: Int, user: String, pass: String): String = """
        tunnel:
          mtu: $MTU
          ipv4: $TUN_V4
          ipv6: '$TUN_V6'
        socks5:
          port: $port
          address: 127.0.0.1
          udp: 'udp'
          username: '$user'
          password: '$pass'
        misc:
          task-stack-size: 20480
          connect-timeout: 5000
          read-write-timeout: 60000
          log-file: stderr
          log-level: error
    """.trimIndent() + "\n"

    private fun freePort(): Int =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun token(): String {
        val b = ByteArray(12)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun friendly(t: Throwable): String = when (t) {
        is VpnPermissionException -> getString(R.string.err_vpn_permission)
        is CoreStoppedException -> getString(R.string.err_core_died)
        is NoClassDefFoundError, is ClassNotFoundException -> getString(R.string.err_core_missing)
        is UnsatisfiedLinkError -> getString(R.string.err_native_missing)
        else -> t.message ?: t.javaClass.simpleName
    }

    // ------------------------------------------------------------------ notification

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PooyVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notification),
            getString(R.string.notif_action_disconnect),
            stop,
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(action)
            .build()
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun postNotification(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    companion object {
        const val ACTION_START = "com.pooy.vpn.START"
        const val ACTION_STOP = "com.pooy.vpn.STOP"
        const val EXTRA_PROFILE_ID = "profile_id"

        private const val TAG = "PooyVpnService"
        private const val CHANNEL_ID = "vpn"
        private const val NOTIF_ID = 1
        private const val TUN_CONFIG = "tun.yaml"
        private const val MTU = 1500
        private const val WATCHDOG_SECONDS = 15L
        private const val TUN_V4 = "10.10.14.1"
        private const val TUN_V6 = "fc00::1"
    }
}
