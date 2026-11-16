# POOOY: lightweight Xray VPN client for Android

Native Kotlin / Jetpack Compose / Material 3 app. Imports VLESS, VMess, Shadowsocks, Trojan, SOCKS and HTTP configs, runs them through **Xray-core** and routes the whole device via Android `VpnService`.

> ## Verification status (please read)
> The 2026 hardening pass (see [docs/HARDENING_REPORT.md](docs/HARDENING_REPORT.md)) was done **without a Kotlin compiler, Gradle, Android SDK/NDK or network**. The project has still never been compiled or run, and the unit tests (old and new) have **not been executed**. The Xray AAR checksum is **not pinned yet** (builds fail until you pin it, see *Dependencies*). Real-device testing is required before release.

## How it works

```
apps -> TUN (VpnService) -> hev-socks5-tunnel (native) -> 127.0.0.1 SOCKS5 (Xray inbound) -> Xray outbound -> your server
```

* **Xray-core** comes from `libv2ray.aar` (gomobile build, 2dust/AndroidLibXrayLite). Only `core/XrayCore.kt` touches it.
* **hev-socks5-tunnel** (C, built by the NDK during the Gradle build) turns TUN packets into SOCKS5 TCP/UDP. This is the same split v2rayNG uses.
* POOOY excludes **itself** from the VPN, so Xray's sockets reach your server directly (no routing loop, no `protect()` hooks).
* The loopback SOCKS port is random per connection and protected with a random per-session username/password, so other apps on the device cannot use it.
* Private/LAN ranges are routed `direct`; everything else uses the selected server. DNS: the VPN hands out 1.1.1.1 / 8.8.8.8 (queried *through* the tunnel); Xray resolves the **server's own hostname** with the system resolver outside the VPN (use an IP address to avoid that lookup). IPv6 is routed into the tunnel so it cannot leak around it. Details and limits: docs/HARDENING_REPORT.md.
* Nothing runs until you tap CONNECT. Disconnect tears down tunnel, core and foreground service. Traffic counters are polled only while the screen is visible (the core keeps counting meanwhile).

## Dependencies (pinned)

`scripts/deps.lock` pins exact versions: Xray AAR release **v26.7.31** and hev-socks5-tunnel **2.16.0** (full commit hash). `fetch-deps` verifies the AAR's SHA-256 and the hev commit, refuses moving refs, and fails if no checksum is pinned. **One manual step is required once:**

1. `./scripts/fetch-deps.sh --print-sha256` (or run the *Pin dependencies* workflow). 
2. Verify the value independently (second machine, upstream's published digest/GPG, or build the AAR yourself).
3. Put it into `XRAY_AAR_SHA256=` in `scripts/deps.lock` and commit.

## Build

Requirements: JDK 17, Android SDK Platform **36**, build-tools 35.0.0, **NDK 27.2.12479018**, Gradle 8.11.1 (AGP 8.9.1), `git`, `curl`.

1. `./scripts/fetch-deps.sh` (Windows: `powershell -ExecutionPolicy Bypass -File scripts/fetch-deps.ps1`). Places `app/libs/libv2ray.aar` and checks out `app/src/main/jni/hev-socks5-tunnel/`, verifying both.
2. Open in Android Studio, or `gradle wrapper --gradle-version 8.11.1` once, then:
   ```
   ./gradlew testDebugUnitTest      # unit tests
   ./gradlew assembleDebug          # debug APK
   ```
3. ABIs: default `arm64-v8a` only (what Play needs; 32-bit-only phones are not supported). Add more with `-Ppooy.abis=arm64-v8a,armeabi-v7a,x86_64`. The AAR must contain the same ABIs.

### GitHub Actions

* **CI** (`build-apk.yml`, on push/PR/manual): fetch + verify deps, unit tests, debug APK artifact.
* **Pin dependencies**: prints the AAR SHA-256 for you to verify and commit.
* **Release** (`release.yml`, manual): runs tests, builds a **signed release APK and AAB**, verifies the APK signature, 16 KB zip alignment and ELF alignment, uploads APK, AAB and R8 `mapping.txt`.

### Signed release APK / AAB

1. Create a keystore once and **back it up safely** (losing it means you cannot update your app): `keytool -genkeypair -v -keystore poooy-release.jks -alias poooy -keyalg RSA -keysize 4096 -validity 10000`
2. Add repository secrets: `POOOY_KEYSTORE_BASE64` (`base64 -w0 poooy-release.jks`), `POOOY_KEYSTORE_PASSWORD`, `POOOY_KEY_ALIAS`, `POOOY_KEY_PASSWORD`.
3. Actions -> *Release* -> Run workflow (enter versionName; versionCode = run number).
4. Download the artifact: upload the `.aab` to Play Console (use Play App Signing), keep `mapping.txt` for crash deobfuscation.

Local signed build: export the same four variables (`POOOY_KEYSTORE_FILE` = path) and run `./gradlew assembleRelease bundleRelease`. `-Ppooy.debugSign=true` (debug key, personal use only) still exists and is not valid for Play. Never commit keystores or passwords (`.gitignore` blocks `*.jks`, `*.keystore`, `*.p12`).

Play Console also needs: the VPN Service declaration, the foreground-service (`systemExempted`) declaration, and a privacy policy.

Unit tests (parser, storage, crypto, subscriptions, Xray lifecycle, VPN state): `./gradlew testDebugUnitTest`.

## Using the app

**+ menu:** paste from clipboard, scan QR, import from URL, import from file, add manually.
**Per config (⋮):** test latency, edit (name + raw link/JSON), rename, duplicate, delete. Tap a card to select it; selecting while connected switches the live tunnel.
**⋮ menu (top):** test all, update subscriptions, manage subscriptions.

Supported inputs:

| Input | Notes |
|---|---|
| `vless://` | tcp/raw, ws, grpc, httpupgrade, xhttp, kcp, h2; TLS and REALITY (`pbk`, `sid`, `spx`, `fp`, `flow`) |
| `vmess://` | base64-JSON (v2rayN style) and URI style |
| `ss://` | SIP002, legacy base64, 2022 ciphers. **Plugins are rejected** (not supported) |
| `trojan://` | TLS by default; transports as VLESS |
| `socks://`, `socks5://` | optional `user:pass` or base64 userinfo |
| `http://user:pass@host:port` | HTTP proxy. A bare `http(s)://…` URL is treated as a **subscription URL** instead; `http://host:port#name` is an HTTP proxy |
| Xray JSON | full config (first proxy outbound is used) or a single outbound object. Chains (`dialerProxy`), routing and DNS from the file are not preserved |
| Subscriptions | base64 or plain list of links. Updating replaces that subscription's configs and keeps your selection if the same server is still present |

Subscription/URL downloads must be **https**: `http://` URLs are rejected, and redirects that leave https are refused. Downloads go out directly, not through the VPN.

## Privacy and security

* Profiles/subscriptions are stored in one file, **AES-256-GCM encrypted with a non-exportable Android Keystore key**, written crash-safely (temp file, fsync, atomic rename, last-known-good `.bak`); `allowBackup=false` and backup/transfer rules exclude everything. If a file is ever unreadable it is kept (`profiles.dat.bad-*`), never silently deleted.
* No analytics, ads, crash reporters or third-party network calls. The only outbound traffic is your VPN traffic, subscription URLs you add, and the latency test (`https://www.gstatic.com/generate_204`, change `XrayConfigBuilder.TEST_URL` if you prefer another).
* Permissions: `INTERNET`, `FOREGROUND_SERVICE` (+ `SYSTEM_EXEMPTED` type for VPN), `POST_NOTIFICATIONS` (asked once on first connect), `CAMERA` (asked only when you tap Scan QR).

## Footprint

R8 + resource shrinking, one ABI, no image libraries, no HTTP library, no JSON library (platform `org.json`), no DI/serialization frameworks, no `material-icons-extended`. APK size is dominated by the Xray AAR (Go runtime). **RAM:** the UI side is small, but the Go runtime inside Xray sets the floor (tens of MB while connected); this cannot go below what Xray needs. I have not measured either number.

## Known risks and limitations

1. **AAR API shape** (unchanged, unverified). `XrayCore.kt` still calls `Libv2ray.initCoreEnv`, `newCoreController(handler)`, `CoreController.startLoop/stopLoop/isRunning/queryStats`, `measureOutboundDelay`, and `CoreCallbackHandler` (`startup/shutdown/onEmitStatus`). Whether `shutdown()` fires on unexpected termination is unknown; the 15 s watchdog covers that case.
2. **hev build.** 2.16.0 is pinned because 2.17+ changed the Java API; the `-DPKGNAME` JNI setup is unverified against 2.16.0.
3. **Fail-closed is partial.** While the app process is alive the tunnel interface is never released by a failure: if the core dies or a server switch fails after a connection worked, the state becomes **Blocked** (no traffic leaves the device), the service retries 3 times (2 s, 5 s, 15 s) and then waits for you to reconnect or press Disconnect. Only a user stop, a system revoke, or a first connection that never worked releases the interface. **Not covered:** the app process being killed or crashing, and OS-initiated revocation: the system then closes the interface and traffic uses the normal network. The only supported protection for that is Android's own **Always-on VPN with "Block connections without VPN"** (Settings -> Network -> VPN -> POOOY). The service now declares `SUPPORTS_ALWAYS_ON=true` and starts with the active profile when the system launches it; this path is untested on a device and can only be switched on by the user, the app cannot force it.
4. **DNS bootstrap** of the server hostname and subscription downloads use the underlying network (see docs/HARDENING_REPORT.md). UDP, including DNS, needs UDP support in the outbound (not available over an HTTP-proxy outbound).
5. **16 KB page size:** hev is built with flexible page sizes, but the Go-built `libgojni.so` inside the AAR must be aligned too; CI checks this for release builds.
6. `allowInsecure` links fail on current Xray builds (upstream removed it 2026-08-01).
7. UI strings are English only; dark theme only. Android 16 behaviour (edge-to-edge, predictive back) at targetSdk 36 is untested.
8. Gradle wrapper JAR is not included, and workflow actions are pinned to major versions, not commit SHAs.

## Layout

```
app/src/main/java/com/pooy/vpn/
  MainActivity.kt, ScanActivity.kt, MainViewModel.kt
  core/     ConfigParser, Importer, Subscriptions, XrayConfigBuilder, XrayCore (AAR adapter), XrayLifecycle
  data/     Models, ProfileStore, Crypto (Keystore)
  service/  PooyVpnService, VpnController, TProxyService.java (JNI)
  ui/       Theme, PooyApp (Compose)
app/src/main/jni/   Android.mk, Application.mk (+ hev-socks5-tunnel/ after fetch)
scripts/            deps.lock, fetch-deps.sh / .ps1, check-elf-alignment.sh
docs/               HARDENING_REPORT.md
```
