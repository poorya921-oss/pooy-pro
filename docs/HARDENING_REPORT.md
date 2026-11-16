# POOOY hardening report

Status of this pass, stated plainly: **everything below was written without a Kotlin compiler, Gradle, Android SDK/NDK
or network access. Nothing was compiled and no unit test was executed.** Only shell syntax, XML/YAML well-formedness,
bracket balance and the fail-closed behaviour of `scripts/fetch-deps.sh` were checked mechanically. Expect to fix
small compile errors on the first build, then run `gradle testDebugUnitTest`.

## 1. What changed

| Area | Change | Files |
|---|---|---|
| Storage | Replaced "rename, else delete original and rename again" with: encrypt, write `.tmp` + fsync, read back and validate, refresh `.bak` only from a verified-good primary, atomic rename over primary. The primary is never deleted. Corrupt/undecryptable files are copied to `profiles.dat.bad-<ms>` (newest 3 kept) before being replaced; nothing is auto-deleted on load. Load order: primary, `.bak`, complete `.tmp`. AES-GCM tags detect torn or flipped files. Structural validation (version, ids, fields). File format and Keystore key unchanged, so existing installs load as before. | `data/ProfileStore.kt`, `data/Crypto.kt` |
| Save results | `save()` returns `SaveResult`; `StoreCommit` only advances state after a successful save. ViewModel shows success messages only when persisted, shows `err_save` on failure, skips the live server switch if the selection could not be saved, and reports recovery/unreadable store at startup. | `data/ProfileStore.kt`, `MainViewModel.kt`, `strings.xml` |
| Subscriptions | https only; manual redirect handling (max 5), every hop must be https, final URL re-checked; malformed URLs, odd schemes, runtime errors all become `SubscriptionException`; 5 MB limit kept (header and streamed). TLS validation untouched. `network_security_config` blocks cleartext and user CAs. | `core/Subscriptions.kt`, `res/xml/*`, manifest |
| Xray lifecycle | State machine split into `XrayLifecycle` (no AAR types): serialised start, duplicate start rejected, failed start stops the half-started controller, idempotent stop, lock-free unexpected-shutdown detection with generation check. `XrayCore` keeps the same AAR calls as before. | `core/XrayLifecycle.kt`, `core/XrayCore.kt` |
| VPN service | Request generations (stop/start/profile-switch cancel stale work at checkpoints); old TUN kept open while switching servers so traffic is blackholed, not leaked; watchdog (15 s) plus shutdown callback for unexpected core death; `setUnderlyingNetworks` follows the default network; destroy-time cleanup ordered after in-flight start; no `RejectedExecutionException` after destroy; `tun.yaml` (holds per-session SOCKS credentials) is owner-only, deleted on teardown and on next service start; exception messages no longer logged. | `service/PooyVpnService.kt`, `service/VpnController.kt` |
| Dependencies | `scripts/deps.lock` pins versions; fetch scripts verify AAR SHA-256 and hev full commit + submodules, refuse moving refs, and fail if no checksum is pinned. Gradle `checkNativeDeps` re-verifies the AAR hash. | `scripts/*`, `app/build.gradle.kts` |
| Release | `targetSdk`/`compileSdk` 36 (Play requires API 36 for new apps and updates since 2026-08-31), AGP 8.9.1 (minimum for API 36) and Gradle 8.11.1, `minSdk` 26 unchanged. Env-var based release signing, AAB with ABI splits, versionCode/Name from CI, R8 rules, unit tests in CI, signed release workflow with APK signature check, 16 KB zip and ELF alignment checks. | `app/build.gradle.kts`, `.github/workflows/*`, `proguard-rules.pro` |
| Manifest | explicit `usesCleartextTraffic=false`, network security config, backup rules for API<31 and `dataExtractionRules` for API 31+. | manifest, `res/xml/*` |

## 2. DNS leak investigation

How resolution works today:

* The TUN routes `0.0.0.0/0` and `::/0`; the system DNS handed to apps is `1.1.1.1`, `8.8.8.8`, `2606:4700:4700::1111`.
  App DNS is therefore plain UDP/TCP inside the tunnel, forwarded by hev as SOCKS5 UDP/TCP to Xray, out through the
  proxy server, which contacts Cloudflare/Google. Apps cannot bypass the VPN (`allowBypass` is not set).
* POOOY itself is excluded from the VPN (`addDisallowedApplication`). Xray's sockets, and therefore **Xray's resolution of
  the server hostname** (`dns.servers = ["localhost"]`), use the underlying network and the ISP/Wi-Fi resolver.
* hev only ever sends IP addresses to Xray, so Xray never resolves destination names; routing is `AsIs` (IP rules only).

Protected: all DNS queries made by other apps (including DoH/DoT to public resolvers, since those are normal traffic),
IPv6 DNS (IPv6 is routed into the tunnel).

Still using the underlying network, by design:
1. Resolving the proxy server's hostname (once per outbound connection). Mitigation: use an IP address as server
   address, or a hostname you are comfortable exposing to the local network's resolver. Not fixable without either
   changing the architecture (DoH bootstrap) or breaking the loop-avoidance.
2. Subscription downloads and their DNS lookup (POOOY is excluded from the VPN).
3. LAN/private ranges (routed to `direct`).
4. Latency tests resolve the server hostname the same way as (1).

Not changed in code: no defect was found in the app-traffic DNS path. Two things could not be verified without the AAR
or a device: (a) that the AAR's "localhost" resolver works on Android as expected, (b) how `registerDefaultNetworkCallback`
reports networks for an excluded app (the code ignores `TRANSPORT_VPN` networks defensively).
Known functional limit: UDP (including DNS) needs UDP support in the outbound; an HTTP-proxy outbound cannot carry it.

## 3. Test status

| Item | Status |
|---|---|
| `scripts/fetch-deps.sh` syntax, "unpinned hash" and "hash mismatch" failures | **Executed here**, both fail as intended |
| Workflow YAML, manifest/XML well-formedness, Kotlin bracket balance | **Checked here** |
| New unit tests (storage failure/recovery, AES-GCM, URL validation and redirects, response handling, Xray lifecycle, VPN state, save-failure propagation, config properties) | **Written, NOT run** (no Kotlin toolchain here) |
| Existing `ConfigParserTest` | Not run |
| Gradle/NDK build, R8 minification, AAB generation, signing | **Not run** |
| Anything needing the real Xray AAR (start/stop, `measureOutboundDelay`, callbacks) | Cannot be tested: AAR not in repo; API unverified |
| VPN permission, TUN, hev, routing, DNS, network switching, reconnect, profile switch, revoke, process death | **Needs a physical device** |
| Android 16 behaviour at targetSdk 36 (edge-to-edge, predictive back, FGS type) | Needs a device/emulator |

## 4. Dependency pins

| Dependency | Pin | Source | Checksum |
|---|---|---|---|
| libv2ray.aar | tag `v26.7.31` (source commit `b21389865ed69ba01e81c1521965c27832a33cf9`) | github.com/2dust/AndroidLibXrayLite releases | **NOT PINNED**: the release page did not expose a digest and the sandbox had no network. Builds fail until you set `XRAY_AAR_SHA256` (see README). Asset name `libv2ray.aar` is also unverified. A newer tag (`v26.8.20`) was seen in search results but its assets could not be inspected; evaluate before bumping. |
| hev-socks5-tunnel | tag `2.16.0`, commit `0a05221275a51a884d93328c55fc2fbc9e9b6974` | github.com/heiher/hev-socks5-tunnel | git commit hash (content-addressed); submodules fixed by that commit |

Why hev 2.16.0 and not 2.18.0: 2.17.0 changed the Java API (`Start()/Stop()` return `boolean`, new `IsRunning()`), and
`TProxyService.java` declares `void` natives. Moving to 2.17+ requires updating that JNI facade and the `PKGNAME`
build flags together; not attempted blind. Whether 2.16.0's `Android.mk` still honours `-DPKGNAME` is unverified.

## 5. Security audit findings

| Topic | Finding | Action |
|---|---|---|
| Keystore | Non-exportable AES-256-GCM key, no user-auth requirement | kept; `setRandomizedEncryptionRequired(true)` made explicit |
| IV | Provider-generated 12-byte random IV per encrypt (Keystore forbids caller IVs) | tested for uniqueness (software provider); format unchanged |
| Store integrity | Torn/corrupt file silently replaced by empty store in old code path | fixed (section 1) |
| Logs | Start failure logged the full exception (may contain config fragments) | now class name only |
| Temp files | `tun.yaml` had per-session credentials in cache | owner-only perms, deleted on teardown/startup; still exists while connected (hev reads it asynchronously) |
| Backup | `allowBackup=false` only | plus `fullBackupContent` and `dataExtractionRules` |
| Permissions | INTERNET, FOREGROUND_SERVICE(+SYSTEM_EXEMPTED), POST_NOTIFICATIONS, CAMERA (runtime, optional) | no change; all used |
| Components | Only `MainActivity` exported (launcher); service not exported, protected by `BIND_VPN_SERVICE` | no change |
| Cleartext | Relied on platform default | explicit config |
| Native loading | `System.loadLibrary` of own hev build only | no change; ELF alignment check added to CI |
| Dependencies | `latest` AAR URL, unpinned git clone | fixed |
| `allowInsecure` | upstream AAR disables it from 2026-08-01 | users' links using it will fail; documented |
| Fail-open on core death | If the core or service dies, the TUN closes and traffic uses the normal network | **not changed** (design); see limitations |

---

# Second pass

Same caveat as the first pass: **no compiler, Gradle, SDK/NDK or network was available. Nothing was compiled and no unit test was run.** Only XML/YAML/shell syntax and bracket balance were checked mechanically.

## Implemented

| Topic | Fix | Files |
|---|---|---|
| Recovery data | A failed save no longer deletes the temp file unless the primary is verified valid or nothing existed before. Before the temp file is overwritten by a new save, primary and backup are validated first; if the primary is not valid, any existing temp file (possibly the only complete copy) and the corrupt primary are copied to `*.bad-<ms>` (newest 5 kept), and the save aborts untouched if that copy fails. Load priority is primary, backup, complete temp, each decrypted and validated. A valid backup is never replaced from an invalid primary. | `data/ProfileStore.kt` |
| Xray races | Callback bookkeeping moved under a tiny separate monitor (never held across native calls, callbacks never take the main lock, so no deadlock). A shutdown callback during start now fails that start (`XrayStartAbortedException`) instead of being lost. `stop()` during start cancels it (`XrayStartCancelledException`). Every controller is stopped exactly once. A core that died on its own is released by the next start or stop; stale callbacks of older controllers are ignored. | `core/XrayLifecycle.kt` |
| XrayCore | `init` synchronised (one xudp key); all other behaviour unchanged. AAR calls untouched and still unverified. | `core/XrayCore.kt` |
| Fail-closed | Connection logic extracted into `VpnSession` (pure Kotlin, single session thread, request generations). Old TUN is kept until the new one exists; a bad profile is rejected before anything is stopped; after a working connection, core death or a failed switch holds the TUN (new `Blocked` state, notification and Disconnect still available) and retries a bounded number of times; the first connection that never worked releases the TUN. Duplicate/overlapping requests, stale death signals and stale reconnect timers are ignored. | `service/VpnSession.kt`, `service/PooyVpnService.kt`, `service/VpnController.kt`, `ui/PooyApp.kt` (label and ring colour for Blocked only) |
| Always-on | `SUPPORTS_ALWAYS_ON=true`; service also starts on `VpnService.SERVICE_INTERFACE`. | manifest, service |
| Keystore | If the key is permanently invalidated/unrecoverable, `encrypt` replaces it once so saving works again (old ciphertext was already lost); transient Keystore errors never reset it; decrypt never resets it. | `data/Crypto.kt` |

## Code-level review results

* Logs: only exception class names are logged; no config, URL, credential or profile content.
* Temp files: `tun.yaml` (per-session loopback SOCKS credentials) is owner-only, deleted on tunnel stop, teardown and next service start; it must exist while hev reads it. Remaining exposure: app-private storage only.
* AES-GCM: provider-generated random 96-bit IV per encryption, 128-bit tag, format unchanged; empty plaintext now accepted by `decrypt` (28-byte blobs).
* Subscriptions/redirects: unchanged from pass one; reviewed again, no new issue found.
* Exported components: only the launcher activity; service not exported and protected by `BIND_VPN_SERVICE`.
* Native init: `System.loadLibrary` failure surfaces as `UnsatisfiedLinkError` inside the session and becomes a failed start; teardown calls are guarded.

## Remaining code-level issues

1. Process death/OS revocation still fail open without the user enabling Always-on lockdown (see README).
2. The Always-on start path (`SERVICE_INTERFACE`) and `registerDefaultNetworkCallback` behaviour for an excluded app are unverified.
3. `ProfileStore.load()` is read-only; recovery is persisted on the next successful save, not at load time.
4. A temp file kept after a failed save can resurface as "Recovered" data if primary and backup are both unreadable (documented last-resort behaviour).
5. `MainViewModel` still has no unit tests of its own (it needs Android types); its persistence logic is covered through `StoreCommit`, and it was not reviewed on a device.
6. AAR API, the hev 2.16.0 JNI contract, and 16 KB alignment of the AAR's native library remain unverified; the Xray AAR checksum is still unpinned.
7. Auto-reconnect uses fixed delays (2/5/15 s); no connectivity-aware backoff.

## Tests

Written, **not executed**: `ProfileStoreTest` (new scenarios for temp-file preservation, corrupt backup/temp, interrupted writes), `XrayLifecycleTest` (+7: callback during start, stop during start, exactly-once cleanup, restart after termination, listener re-entrancy, 6-thread start/stop hammer), `VpnSessionTest` (new, 23 scenarios: fail-closed, bounded reconnect, profile switching, duplicate/stale requests, cancellation), `VpnControllerTest` (+4 for Blocked/error). Executed here: nothing beyond the syntax checks listed above.

---

# Third pass

Same caveat as before: **nothing was compiled and no unit test was run** (no Kotlin compiler, Gradle, SDK or network in the sandbox). Only bracket balance and unused-import scans were run over the Kotlin sources.

## Files modified / added

Modified: `data/ProfileStore.kt` (rewritten), `core/XrayLifecycle.kt`, `core/XrayCore.kt` (listener-error logging only), `service/VpnSession.kt`, `service/PooyVpnService.kt`, `MainViewModel.kt`.
Added: `data/StoreFs.kt`, `service/UnderlyingNetworkTracker.kt`, `core/LatencyMeasure.kt`.
Tests: `ProfileStoreTest` (updated + ~20 new cases), new `FallbackStoreFsTest`, `UnderlyingNetworkTrackerTest`, `LatencyMeasureTest`; additions to `VpnSessionTest` and `XrayLifecycleTest`.

## Problems fixed

| # | Problem | Fix |
|---|---|---|
| 1 | Quarantine pruning deleted the oldest copies regardless of content; a failed save could delete a pre-existing interrupted `.tmp`; quarantine copies were not fsynced or verified; backup temp was not verified. | Files are probed as Valid / Corrupt (fails AES-GCM authentication or is not a well-formed blob) / Unknown (I/O or Keystore trouble, or authenticated but rejected by structure/version). Only Corrupt copies are ever pruned, and only beyond the newest 5 of them. A pre-existing temp file is overwritten without a copy only if it is Corrupt or a valid duplicate of what primary/backup already hold; otherwise it is durably copied first. After a failed save only a temp file *this call wrote* can be removed. Quarantine copies are written with fsync, read back and compared before anything else is modified (identical copies are not duplicated; best-effort directory sync). The backup file is written, read back and validated before it replaces the old backup; on any failure the old backup stays. Format unchanged. |
| 2 | Lenient `fromJson`. | Requires `v == 1` (newer versions are kept, never pruned), both arrays with correct types, object records, string fields of the right type, non-empty and unique ids. Malformed data is rejected, never turned into an empty store. Dangling `active` / profile `sub` references are **repaired to null and logged** rather than rejected (they cannot come from valid saves; rejecting would cost the user every profile). Valid existing version-1 data loads as before. |
| 3 | `DefaultStoreFs` only handled `AtomicMoveNotSupportedException`. | `FallbackStoreFs`: ATOMIC_MOVE, then REPLACE_EXISTING move (Java ignores REPLACE_EXISTING together with ATOMIC_MOVE, so they are separate steps), then `File.renameTo`, then an `IOException` carrying all earlier failures. The destination is never deleted; if everything fails it is untouched and the source remains. Primary and backup use the same path. |
| 4 | `tunnelRunning` was set only after `TProxyStartService` returned. | `tunnelStartAttempted` is set before the native call and cleared before the single stop call, so a start that throws halfway is cleaned up exactly once, a throwing stop is not repeated, and cleanup is skipped if hev was never attempted. The service port deletes `tun.yaml` when initialisation fails and in `stopTunnel`. Fail-closed behaviour is unchanged (tested for first connect and for a switch). |
| 5 | `onLost()` always cleared the underlying network. | `UnderlyingNetworkTracker` remembers the selected network; a loss clears only if it is that network, all state is under one lock, and `release()` (on unregister) clears once and makes in-flight callbacks no-ops. |
| 6 | `runTest()` swallowed `CancellationException` (caught `Throwable`) and could publish stale results. | `LatencyMeasure.measure`: rethrows cancellation, maps `Exception`/`LinkageError` to -1, lets other errors propagate, and checks `ensureActive()` after the uninterruptible native call. The ViewModel publishes only if the test is still the newest for that profile and the profile content is unchanged; the `finally` cleanup of `testing` only removes the flag of its own test. |
| 7 | An exception in the application's listener could escape into the native callback thread. | The listener runs outside all locks inside try/catch (errors go to an optional `onListenerError`, itself guarded). The generation checks and exactly-once bookkeeping happen before the listener is called. |

## Remaining code-level limitations

1. Untouched from earlier passes: process death or OS revocation still fails open unless the user enables Android's Always-on VPN lockdown; the AAR API, hev 2.16.0 JNI contract and 16 KB alignment of the AAR's native library are unverified; the Xray AAR checksum is still unpinned.
2. Corrupt-copy classification is by AES-GCM authentication failure. If the Keystore key is permanently replaced, old ciphertext is classified Corrupt and becomes prunable (it is unrecoverable by this app anyway).
3. Valid and Unknown quarantine copies are never pruned, so repeated failed-save cycles that leave distinct valid temp files can accumulate small files.
4. Directory fsync is best effort (not supported on every filesystem/JVM).
5. Dangling-reference repair is a deliberate deviation from "reject everything invalid"; it is logged and covered by a test.
6. The native latency measurement cannot be interrupted; cancellation takes effect when it returns.
7. `MainViewModel` itself still has no direct unit tests (it needs Android types); its cancellation logic lives in `LatencyMeasure` and is tested there.

---

# Fourth pass

Same caveat: **nothing was compiled and no unit test was run** (no Kotlin compiler, Gradle, SDK or network in the sandbox). Only bracket-balance and unused-import scans were run.

## Files modified / added

Modified: `data/ProfileStore.kt`, `MainViewModel.kt`.
Added: `core/LatestRunTracker.kt`, `core/Operations.kt` (`Operations`, `BusyTracker`).
Tests: `ProfileStoreTest` (updated + new backup.tmp / validation cases), new `LatestRunTrackerTest`, new `OperationsTest`.

## Problems fixed

| # | Problem | Fix |
|---|---|---|
| 1 | `profiles.dat.bak.tmp` was ignored by the existence check and by recovery, was overwritten by every backup refresh without preserving it, and was deleted after any failed refresh. | It is part of the existence check (a store with only a `.bak.tmp` is no longer "Empty") and the last recovery source. **Deterministic priority: primary, backup, tmp, bak.tmp**, each decrypted and validated, first valid wins, read-only at load. Before the staging file is overwritten it is probed: corrupt/torn or a valid duplicate of the current primary/backup data = disposable; valid but different, or unclassifiable (e.g. newer version) = durably copied first, and the save aborts untouched if that copy fails. The new backup is staged, read back, byte-compared and validated before it replaces `profiles.dat.bak`, so the previous valid backup stays until replacement succeeds. After a failed refresh the staging file is deleted only if it never verified (our own torn scratch); a verified copy is kept. A save that fails before it starts writing never touches an existing `.bak.tmp`. Same staging file name as before (no new file, format unchanged). |
| 2 | `testTokens`: the token check and its removal were separate synchronized blocks, and the loading indicator was cleared outside the lock, so an older test could remove a newer test's token or clear its indicator. | New `LatestRunTracker`: `begin` (token + indicator on), `finish` (compare-and-remove + indicator off) and `publishIfCurrent` each run atomically under one lock together with the UI-state update. The stale-result check (same profile content) now runs inside `publishIfCurrent`. |
| 3 | `CancellationException` could be swallowed by `catch (e: Exception)` in `importUrl`, `importFile`, `updateSubscription` and, inside the loop, `updateAllSubscriptions`; `busy` was a single boolean cleared by whichever operation finished first; success/failure messages could appear for cancelled work. | All five now use `Operations.attempt` (rethrows cancellation, reports ordinary exceptions, converts "failed because we were cancelled" into the cancellation) and `Operations.announce` (throws instead of showing a message once cancelled). `updateAll` uses `Operations.countResults` (cancellation stops the batch and is not counted as a failure; no summary after cancellation). `busy` is a counter (`BusyTracker`) cleaned up in `finally`. `runTest` (previous pass) uses the tracker. `update()` runs the commit under `NonCancellable`, so a cancellation can no longer leave data saved on disk but not applied in memory; the cancellation surfaces right afterwards and suppresses the message. |
| 4 | Empty required values. | `fromJson` now also rejects an empty profile `raw` and an empty subscription `url`. Valid existing data always has both (profiles are parsed before saving; subscription URLs are validated https). A rejected file is classified "unknown", never pruned, never overwritten without a durable copy, and never turned into an empty store (tests cover bytes surviving a later save). |

## Remaining code-level limitations

1. Directory fsync is best effort; a power loss can still lose a rename on filesystems without it.
2. A verified `.bak.tmp` left by a failed backup refresh, and valid temp files left by repeated failed saves, are never auto-pruned (by design), so small files can accumulate.
3. `MainViewModel` itself still has no direct unit tests (it needs Android types); its concurrency and cancellation logic lives in `LatestRunTracker`, `Operations`, `BusyTracker` and `LatencyMeasure`, which are tested, but the wiring inside the ViewModel is not.
4. `update()` completes its commit even if cancelled just before it starts (intentional, for memory/disk consistency).
5. The blocking native latency call and the blocking `HttpURLConnection` fetch cannot be interrupted; cancellation takes effect when they return.
6. Not touched in this pass, still open from earlier passes: process death/OS revocation fails open without Always-on lockdown; AAR API, hev 2.16.0 JNI contract and AAR 16 KB alignment unverified; AAR checksum unpinned.

---

# Fifth pass

Same caveat: **nothing was compiled and no unit test was run** (no Kotlin compiler, Gradle, SDK or network in the sandbox). Only bracket-balance and unused-import scans were run over the Kotlin sources.

## Files modified / added

Modified: `MainViewModel.kt`, `core/Operations.kt` (`BatchCounts.skipped`, `BatchOutcome`, `countOutcomes`).
Added: `data/StoreUpdater.kt`, `data/SubscriptionRefresher.kt`.
Tests added: `data/StoreUpdaterTest.kt`, `data/SubscriptionRefresherTest.kt`; `OperationsTest` extended.

## Fixes

1. **Commit/apply consistency.** `update()` now delegates to `StoreUpdater.update`, which does read, compute, persist and publish (`data = ...`) inside ONE `withContext(io + NonCancellable)` block under the existing `Mutex`; disk I/O stays on `Dispatchers.IO`. A cancellation during the commit can no longer land between "on disk" and "in memory". It is propagated right after the critical section (`ensureActive()`), so no code after the update, including success messages, runs for cancelled work. Cancellation while waiting for the lock still just propagates (nothing is written, the lock is released). Failed saves publish nothing.
2. **Stale subscription refresh.** `SubscriptionRefresher` downloads outside any lock, then decides inside the store update (latest data, store mutex held, same step as the write) whether to apply. It applies only if the subscription still exists, its URL still equals the downloaded URL, and no newer-started refresh has already claimed that subscription (per-subscription sequence; a newer refresh that fails never claims, so it does not block an older one). Otherwise it returns `Stale`, writes nothing, and the ViewModel reports no success. A subscription that is already gone is not even downloaded. `updateAllSubscriptions` counts stale results as neither updated nor failed and shows no summary if nothing was counted. Cancellation behaviour is unchanged (propagates, nothing applied).

## Remaining known code-level issues

1. A claim is recorded when the refresh is applied inside the update; if the following disk write fails, that claim stands, so an older overlapping refresh is still discarded (one refresh opportunity is lost; the user is told the save failed and can retry). Chosen over the alternative, which could let an older download overwrite a newer one.
2. There is no UI operation that edits a subscription URL in place, so the URL check protects against future edits and delete-and-re-add with the same id only; it is covered by a test that edits the URL directly in the store.
3. A discarded (stale) refresh is silent by design; the user gets no message that a download was thrown away.
4. `MainViewModel` itself still has no direct unit tests (needs Android types); the logic is tested through `StoreUpdater`, `SubscriptionRefresher`, `Operations`, `BusyTracker`, `LatestRunTracker`; the wiring in the ViewModel is not.
5. Blocking downloads (`HttpURLConnection`) and the native latency call still cannot be interrupted; cancellation takes effect when they return.
6. Open from earlier passes: process death or OS revocation fails open without Always-on lockdown; AAR API, hev 2.16.0 JNI contract and AAR 16 KB alignment unverified; AAR checksum unpinned; directory fsync is best effort.

## Tests executed

None. All tests, including the new ones, are written but **not run**.

---

# Sixth pass: subscription refresh transaction safety

Same caveat: **no Kotlin compiler, Gradle, SDK or network is available in the sandbox, so no test was run.** Only bracket-balance and unused-import scans were executed.

## Files modified

`data/SubscriptionRefresher.kt` (rewritten), `data/StoreUpdater.kt` (new `onResult` hook), `MainViewModel.kt` (`updateWithHook`, refresher wiring).
Tests: `data/SubscriptionRefresherTest.kt` (harness + 9 new cases), `data/StoreUpdaterTest.kt` (4 hook cases).

## The bug

`claim()` ran inside the update block, i.e. before the write. A newer refresh whose save then failed had already advanced the sequence, so an older refresh that could have been saved was rejected as stale.

## The fix

Applying a refresh is one transaction with two phases, both under the store mutex and inside the same non-cancellable critical section:

1. **Decide** (update block, latest data, before the write, read-only): eligible iff the subscription still exists, its URL equals the downloaded URL, and `seq > lastSaved[subId]`.
2. **Register** (new `onResult` hook of `StoreUpdater`, right after the write attempt, before the lock is released): `lastSaved[subId]` is advanced only if the refresh was eligible and the result is `Committed` or `Unchanged` (disk already equals the new state). A `Failed` write registers nothing.

Registration is therefore not "after `update()` returns" (where another refresh could decide in between, or a cancellation could intervene) but inside the critical section: the next refresh's decision cannot run until the sequence reflects the outcome of this save. Consequences: a failed newer refresh supersedes nobody; a saved (or identical-to-disk) newer refresh supersedes every older one; an older refresh that committed first never blocks a newer one; a cancellation during the write still leaves disk, memory and sequence consistent. Deletion and URL-change protection is unchanged; cancellation propagates after the critical section as before. Other pre-persistence mutations: none remain (the sequence number handed out at refresh start only defines "newer" and decides nothing; the removed `claim()` was the only shared decision state written before the save).

## Remaining known issues

1. `lastSaved` entries are never pruned (one `Long` per subscription id ever refreshed in the process); negligible.
2. Start order defines "newer": if the newer-started refresh downloads older content from a slower server, its (saved) result still wins.
3. A discarded (stale) refresh is silent; the user gets no message.
4. `MainViewModel` itself still has no direct unit tests (needs Android types); the wiring is untested.
5. Open from earlier passes: process death/OS revocation fails open without Always-on lockdown; AAR API, hev 2.16.0 JNI contract and AAR 16 KB alignment unverified; AAR checksum unpinned; directory fsync best effort; blocking downloads cannot be interrupted.

## Tests executed

None. All tests are written but **not run**.
