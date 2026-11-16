package com.pooy.vpn.core

import android.content.Context
import android.util.Log
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.lang.reflect.InvocationTargetException
import java.util.UUID

/**
 * The ONLY file that touches the Xray AAR (libv2ray, from 2dust/AndroidLibXrayLite).
 * Targets the CoreController API (Libv2ray.newCoreController / initCoreEnv / measureOutboundDelay).
 * If your AAR exposes a different API, adapt [AarBackend] / [AarController] only. Lifecycle rules live in [XrayLifecycle].
 *
 * NOTE: the AAR is not part of the repository, so every call below that touches it is unchanged from the
 * original code and has NOT been verified against the pinned AAR's real signatures.
 */
object XrayCore {
    private val lifecycle = XrayLifecycle(AarBackend) { Log.w("XrayCore", "Core-stop listener failed: ${it.javaClass.simpleName}") }

    val isRunning: Boolean get() = lifecycle.isRunning
    val state: XrayLifecycle.State get() = lifecycle.state

    /** Set by the VPN service; called when the core reports shutdown without stop() having been requested. */
    var onUnexpectedStop: (() -> Unit)?
        get() = lifecycle.onUnexpectedStop
        set(value) {
            lifecycle.onUnexpectedStop = value
        }

    @Synchronized
    fun init(context: Context) {
        if (lifecycle.isInitialised) return
        val prefs = context.applicationContext.getSharedPreferences("core", Context.MODE_PRIVATE)
        val key = prefs.getString("xudp_key", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("xudp_key", it).apply() }
        lifecycle.init(context.applicationContext.filesDir.absolutePath, key)
    }

    /** Throws if a core is already running (call [stop] first) or if startup fails; failed starts clean up after themselves. */
    fun start(configJson: String) = lifecycle.start(configJson)

    fun stop() = lifecycle.stop()

    /** Bytes since the previous call (the core resets the counter on read). tag = "proxy", direction = "uplink" | "downlink". */
    fun queryTraffic(tag: String, direction: String): Long = lifecycle.queryTraffic(tag, direction)

    /** Round-trip time in ms through the given outbound config, or throws. Blocking. */
    fun measureDelay(configJson: String, url: String): Long = lifecycle.measureDelay(configJson, url)
}

private object AarBackend : XrayBackend {
    override fun initEnv(basePath: String, xudpKey: String) {
        Libv2ray.initCoreEnv(basePath, xudpKey)
    }

    override fun newController(onCoreShutdown: () -> Unit): XrayController {
        val handler = object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long {
                onCoreShutdown()
                return 0
            }
            override fun onEmitStatus(code: Long, message: String?): Long = 0
        }
        return AarController(Libv2ray.newCoreController(handler))
    }

    override fun measureDelay(configJson: String, url: String): Long = Libv2ray.measureOutboundDelay(configJson, url)
}

private class AarController(private val c: CoreController) : XrayController {
    override val isRunning: Boolean get() = c.isRunning

    /**
     * Some AndroidLibXrayLite releases expose startLoop(config) and newer ones startLoop(config, tunFd).
     * We always run a SOCKS inbound and our own tun2socks, so pass fd 0 ("no tun") if a second argument exists.
     */
    override fun start(config: String) {
        val m = CoreController::class.java.methods.firstOrNull { it.name == "startLoop" }
            ?: throw IllegalStateException("CoreController.startLoop not found in libv2ray.aar")
        try {
            if (m.parameterTypes.size >= 2) m.invoke(c, config, 0) else m.invoke(c, config)
        } catch (e: InvocationTargetException) {
            throw (e.targetException ?: e)
        }
    }

    override fun stop() {
        c.stopLoop()
    }

    override fun queryStats(tag: String, direction: String): Long {
        val method = c.javaClass.methods.firstOrNull { it.name == "queryStats" && it.parameterTypes.size == 2 }
            ?: return 0L
        return (method.invoke(c, tag, direction) as? Number)?.toLong() ?: 0L
    }
}
