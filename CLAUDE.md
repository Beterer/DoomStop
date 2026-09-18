# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

DoomStop is a single-module Android app (Kotlin, Compose, Room, coroutines, no DI framework) that enforces one shared daily allowance for Instagram, TikTok and Reddit on one personal Pixel 9. It uses **device-owner** policy: it suspends packages, sets a permanent Chrome `URLBlocklist`, suspends a hardcoded list of alternative browsers, and runs a best-effort YouTube Shorts guard. A trusted person's six-digit PIN authorizes extensions and admin actions.

- `docs/social-limit-implementation-plan.md` holds the fixed requirements and hard constraints. It is still the spec, with four exceptions: the app ID became `dev.personal.doomstop`, YouTube Shorts was added in 0.2.0, unused time carries over to the next day since 0.3.0 (amended in the plan itself), and Instagram messaging mode was added in 0.4.0 (Instagram DM time is never charged, and once the limit is reached Instagram is left open on the messages screen only instead of suspended, guarded by an accessibility service).
- `docs/test-report.md` records what was measured, on which device, and what is still unverified. `docs/setup-and-recovery.md` covers provisioning, the PIN, removal and updates.
- **Documentation convention:** the README's "What it does not do" section and the test report claim only what has been demonstrated, and they name the kind of evidence (engine / coordinator / emulator / device). Keep that standard. Don't describe a best-effort mechanism as a hard limit. When behavior changes, update the relevant limitation or test-report row.

## Commands

`java` is not on PATH and `JAVA_HOME` is unset on this machine. Point it at Android Studio's JBR first (Git Bash: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`). In PowerShell, use `.\gradlew.bat`.

If `processDebugResources` fails with "AAPT2 … Daemon startup failed", check `--info` for `CreateProcess error=4551`. Since 2026-09-17, Windows application control has blocked the Gradle-cached `aapt2.exe`. The SDK's copy is allowed: add `-Pandroid.aapt2FromMavenOverride=C:\Users\flori\AppData\Local\Android\Sdk\build-tools\36.0.0\aapt2.exe` on the command line. Don't commit that override.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest                                   # JVM unit tests, no device
./gradlew :app:testDebugUnitTest --tests "dev.personal.doomstop.domain.BudgetEngineTest"
./gradlew :app:testDebugUnitTest --tests "*.BudgetEngineTest.someTestName"
./gradlew :app:lintDebug                                           # lint has abortOnError = true
./gradlew :app:connectedDebugAndroidTest                           # needs emulator/device
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.personal.doomstop.data.LimiterDaoTest
./gradlew :app:assembleRelease                                     # requires keystore.properties
```

- **Instrumented tests run on AVD `doomstop36`** (API 36, provisioned as device owner). Don't run them on the phone. It has the release-signed build as device owner, so a debug-signed APK can't be installed over it (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and uninstalling is refused. Since the signed-update measurement (test report §7.4), `doomstop36` is in the same state: it holds release-signed versionCode 2. The non-device-owner suites (coordinator, DAO, migration) were therefore run on a throwaway AVD made from the same image (§11). With both the phone and an emulator attached, `connectedDebugAndroidTest` targets every device, so install and run with `adb -s emulator-5554 …` instead.
- `PolicyControllerTest` and `EnforcementIntegrationTest` call the real DevicePolicyManager. They skip themselves through `assumeTrue` when the device isn't a device owner. `CoordinatorRegressionTest` uses `FakePolicyGateway`, a real Room database and a fake clock.
- `@ManualFixture` tests (`admin/ManualFixtures.kt`) deliberately leave policy applied. The default runner argument `notAnnotation` excludes them from Gradle runs. Run one directly after installing the debug and androidTest APKs: `adb shell am instrument -w -e class dev.personal.doomstop.admin.ManualFixtures#<method> dev.personal.doomstop.test/androidx.test.runner.AndroidJUnitRunner`.
- `assembleRelease` and `bundleRelease` depend on `verifyReleaseSigning`, which fails the build when there's no key. `-PallowUnsignedRelease=true` is for inspecting an unsigned build only, never for the phone.

## Toolchain constraints (will break the build if changed independently)

- AGP 9.4.0 supplies **built-in Kotlin**. Never apply `org.jetbrains.kotlin.android`.
- AGP pins KGP 2.2.10. KSP must be exactly `2.2.10-2.0.2`, and the Compose Compiler plugin must match the same Kotlin version. `gradle/libs.versions.toml` is the single source of versions. The README records them too and must be kept in sync.
- `android.disallowKotlinSourceSets=false` in `gradle.properties` is required for KSP (Room) under AGP 9.
- R8 is intentionally off for release: the device-admin receiver, manifest receivers and Room entities are reflection-driven.

## Architecture

**Wiring.** `DoomStopApp` does manual dependency injection, and everything in it is `lazy`. That's load-bearing: `BootReceiver` runs at `LOCKED_BOOT_COMPLETED`, before credential-protected storage (the Room DB) is readable. At that point it may only touch `BootMarkerStore`, a tiny SharedPreferences file in device-protected storage, and `PolicyController`, to re-apply the last suspension state.

**One serialized owner: `core/LimiterCoordinator`.** The foreground-service poll, broadcast receivers, alarms and the UI all call `tick(trigger)` or another suspend method, and all of them are guarded by one `Mutex`. Nothing else mutates balances or policy. One `tickLocked` pass runs in this order:

1. `BudgetEngine.resolveClock` decides the accepted "now". Durations come from `elapsedRealtime`. The wall clock is adopted only while it agrees with elapsed time. Large jumps are refused and latch a recovery request.
2. The visibility observer (`monitor/VisibleTargetTracker`) is **rebuilt from the settled snapshot on every pass**, not carried in memory.
3. Usage events are merged: retained `pending_event` rows plus fresh `UsageStatsManager` events, deduplicated by `TrackedEvent.key`. Events older than the settled boundary are dropped, and the drop is logged as a diagnostic.
4. Only the **settled window** is charged. It trails "now" by `SETTLE_LAG_MS` (30 s), so late events can still correct it. The unsettled tail is estimated with `provisionalSlices` for enforcement but never persisted.
5. **Persist, then enforce.** A crash between the two can only leave time charged, never give it back.
6. `syncEnforcement` / `syncChromePolicy` push policy to the platform when it changes, when forced by a trigger, or on periodic re-assert (30 s / 5 min). Then the boot marker, the backup deadline alarm and the published `StateFlow<LimiterStatus>` are updated.

**Key invariants:**

- `domain/` is pure Kotlin with no Android imports, and it takes every instant through `TickInput`/`ClockSource`. Keep it that way; it's what lets the unit tests advance time by days. The three clocks (elapsed, wall, boot ID) are never interchanged. Monotonic values from different boots are never compared.
- **Recovery is latched** (`Checkpoint.recovery`). An unreconstructable gap suspends the targets. Only a PIN-authorized `acknowledgeRecovery()` clears it, never a later successful poll.
- **Write-ahead policy ledger.** Before changing any platform value for the first time (package suspension, the Chrome blocklist, self-protection, restrictions, auto-time), record the previous value as `VALUE`, `ABSENT` or `UNKNOWN`. `restoreDevice` restores from the ledger in verified stages and stops at the first failure. It releases device ownership only after every earlier stage verified, and it never guesses `UNKNOWN` values.
- **Read-back, not return values.** `admin/PolicyController` is the only place that calls `DevicePolicyManager`. Its reports carry state read back from the platform. "Chrome policy stored" means DPM holds the value, not that Chrome applied it.
- Diagnostics never contain the PIN or any window content.

**Test seams** (the interfaces the coordinator depends on): `DevicePolicyGateway` (real: `PolicyController`, fake: `FakePolicyGateway`), `UsageSource` (`UsageEventReader`), `ShortsGuardProbe`, `InstagramGuardProbe`, `ClockSource`.

**Monitoring.** `UsageMonitorService` is a foreground service. Its type is `systemExempted` when the app is device owner and `specialUse` otherwise. It polls every 1 s while a target is visible, every 2 s while the screen is on, and every 30 s while the screen is off. Screen and package broadcasts are registered at runtime so they wake it immediately, because manifest receivers don't get most of them. A `PACKAGE_CHANGE` trigger forces policy re-application, which is what keeps the new-browser launch window small.

**Shorts guard.** `monitor/ShortsGuardService` is an accessibility service limited to the YouTube package. It detects the Shorts player by the view IDs in `config/ShortsGuard.SHORTS_PLAYER_VIEW_IDS` and backs out. The rule that gives it teeth is `ShortsGuard.youtubeMustBeSuspended`: while enforcing, if the guard isn't connected (after a 15 s bind grace), the whole YouTube app is suspended. After a YouTube update, re-measure the IDs with `adb shell uiautomator dump` on three screens (a Short, the home feed, the ordinary player). `ShortsGuardTest` holds the measured lists as fixtures.

**Instagram messaging guard (0.4.0).** `monitor/InstagramGuardService` is the Shorts guard inverted: an accessibility service limited to `com.instagram.android` that recognises the ALLOWED screens (the DM inbox/thread) via `config/InstagramGuard.DM_SCREEN_VIEW_IDS` and backs out of everything else once the limit is reached (`InstagramGuard.shouldBackOut`, a 3 s launch grace then Back+Home). Two coupled effects: (1) **metering exemption** — while the guard reports a DM screen on top, the coordinator masks Instagram in `VisibleTargetTracker` (a `PACKAGE_MASKED`/`PACKAGE_UNMASKED` event injected onto the replay stream by `instagramMaskCorrection`, exactly like `liveScreenCorrection`), so DM time is never charged whether or not the limit is reached; (2) **enforcement split** — `applyEnforcement(suspendTargets, suspendYouTube, suspendInstagram)` carves Instagram out of the bulk suspend, and `InstagramGuard.instagramMustBeSuspended` is its teeth (guard off at the limit ⇒ Instagram suspended outright). Messaging mode is only offered when `EnforcementReason.ALLOWANCE_EXHAUSTED`; any other suspend reason hard-suspends Instagram too. `DM_SCREEN_VIEW_IDS` were **measured on the Pixel 9, Instagram 447.0.0.55.81 (2026-09-17)** and `InstagramGuardTest` now carries measured inbox/thread/feed fixtures. Caveat from that measurement: the DM inbox is a `MainTabActivity` pane readable via `uiautomator dump`, but an **open thread is a separate FLAG_SECURE `com.instagram.modal.ModalActivity`** — black to screencap, null-root to `uiautomator`; its IDs were read via `adb shell dumpsys activity <component>` (unaffected by FLAG_SECURE). FLAG_SECURE does not block accessibility in general, so the bound guard is *expected* to read the thread, but that is **unverified end-to-end** (0.4.0 can't install over the release-signed device owner on the phone) — verify on a throwaway AVD or briefly with TalkBack. Re-measure IDs after a major IG update, as for the Shorts guard. `TrackerState` is now version 2 (adds masked packages; version 1 still decodes). At locked boot Instagram is hard-suspended whenever targets are (the guard can't run pre-unlock).

**UI.** `MainActivity` shows Compose screens driven by `ui/LimiterViewModel`: a `Screen` state machine for Status, Setup, Pin, Admin and ChangePin. A correct PIN yields a single-use `AuthorizationTicket`. An extension is idempotent because the ticket token is the grant row's primary key. The admin session expires on idle and is revoked the moment the app leaves the foreground. PIN and admin screens use `FLAG_SECURE`.

## Change checklists

- **Adding or removing a package** in `config/TargetPackages`, `config/BlockedBrowsers`, `ShortsGuard` or `InstagramGuard`: also update the `<queries>` block in `AndroidManifest.xml`. `QUERY_ALL_PACKAGES` is deliberately not requested, so an undeclared package is invisible and silently not enforced. Use verified package IDs only, never substring or brand matching. Never suspend anything in `BlockedBrowsers.NEVER_SUSPEND` (Chrome stable, WebView).
- **Room schema change:** bump the `version` in `LimiterDatabase`, write an explicit `Migration` and add it to `MIGRATIONS`, commit the exported JSON in `app/schemas/`, and extend `MigrationTest`. `fallbackToDestructiveMigration` is forbidden, because it would reset the day's balance.
- **Never change** `applicationId`, and don't add an `applicationIdSuffix`. The release signing key must also stay the same. Either change breaks in-place updates of the provisioned device owner, and recovering needs a factory reset. Bump `versionCode` for every build that goes on the phone.

## Hard product constraints (from the plan)

Don't set `DISALLOW_INSTALL_APPS` or blanket-block installers, Settings, the Play Store, VPN apps, calls, SMS or similar. Installing unrelated apps must never need a PIN. No backend, VPN, root, hidden APIs, analytics or remote PIN approval. No Start-session button: counting is automatic. Don't factory-reset the phone, run `dpm set-device-owner`/removal commands, disable USB debugging, or leave fixtures' policy applied on the real phone without explicit authorization.
