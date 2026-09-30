# AGENTS.md

Repository guidance for coding agents working on DoomStop. Keep this file focused on
durable project context; record detailed measurements and task history in `docs/`.

## Project

DoomStop is a single-module Android app for one personal Pixel 9. It uses Kotlin,
Jetpack Compose, Room and coroutines, with manual dependency wiring and no DI framework.
Android **device ownership** is required for enforcement; ordinary device-admin permission
is insufficient.

- Instagram, TikTok and Reddit share one automatically metered daily allowance. Unused
  base allowance carries forward without a cap; PIN-authorized extension time does not.
- Chrome's social-site `URLBlocklist` and suspension of known alternative browsers apply
  permanently while enforcement is enabled.
- Accessibility services provide best-effort YouTube Shorts blocking and Instagram DM
  recognition. DM time is exempt only while the messaging guard reports a DM screen.
  At allowance exhaustion, Instagram permits messaging if the guard is healthy; otherwise
  it is suspended. Other recovery or protection failures hard-suspend Instagram too.
- A trusted person's six-digit PIN authorizes extensions, recovery and admin actions.

## Where to look

Source packages are under `app/src/main/java/dev/personal/doomstop/`:

| Location | Responsibility |
| --- | --- |
| `domain/` | Pure Kotlin accounting, clocks, day boundaries and models |
| `core/` | Serialized `LimiterCoordinator`, status and deadline scheduling |
| `admin/` | Device policy gateway, platform policy calls and restoration |
| `monitor/` | Usage events, visibility tracking, accessibility guards and receivers |
| `data/` | Room database, migrations, DAO and direct-boot marker |
| `config/` | Verified package IDs, browser/host lists and guard detection rules |
| `security/`, `ui/` | PIN handling and Compose screens driven by `LimiterViewModel` |
| `DoomStopApp.kt` | Lazy dependency wiring |

Use these references when relevant to the task:

- `README.md`: product behavior, limitations and build overview.
- `docs/social-limit-implementation-plan.md`: original requirements and constraints;
  consult the README and later test-report sections for subsequent behavior changes.
- `docs/test-report.md`: measured evidence, device/emulator history and unverified gaps.
- `docs/setup-and-recovery.md`: provisioning, signed updates, PIN and removal procedures.
- `CLAUDE.md`: additional architecture, test fixture and local troubleshooting details.

## Build and verification

Run from the repository root with the committed Gradle wrapper. PowerShell commands:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:testDebugUnitTest --tests "dev.personal.doomstop.domain.BudgetEngineTest"
.\gradlew.bat :app:lintDebug
```

On Unix/Git Bash, use `./gradlew` instead. The daemon toolchain is JDK 25; Java source
compatibility is 17. If Java is unavailable locally, set the session's `JAVA_HOME` to
Android Studio's JBR (`C:\Program Files\Android\Android Studio\jbr` on the recorded host).
For the recorded Windows AAPT2 startup failure (`CreateProcess error=4551`), use the
SDK AAPT2 override documented in `CLAUDE.md`; keep machine-specific overrides local.

Choose verification that exercises the change: JVM tests for accounting/guard rules,
build and lint for Android changes, and instrumented tests for Room/platform behavior.
Documentation-only changes need a content/link/diff review. Report checks actually run
and any device-dependent behavior that remains unverified.

Instrumented tests live in `app/src/androidTest/`; JVM tests live in `app/src/test/`.
`:app:connectedDebugAndroidTest` requires a compatible emulator and targets all connected
devices. Inspect devices and signing state first; when multiple devices are attached,
install/run explicitly with `adb -s <emulator-serial>` instead. Use a disposable emulator
for debug tests: the real phone and recorded `doomstop36` AVD hold release-signed device
owners that reject debug-signed updates. Real policy suites require a device owner and
otherwise skip. `@ManualFixture` tests intentionally leave policies applied and are
excluded from the default runner; invoke them only for an intentional manual check.

## Constraints to preserve

- `domain/` stays Android-free with injected clocks. Keep wall time, elapsed time and boot
  identity distinct; never compare monotonic timestamps across boots.
- `LimiterCoordinator` serializes state and enforcement through one `Mutex`. Replay from
  the settled snapshot, persist only settled accounting (30-second lag), and estimate the
  unsettled tail for enforcement. Persist accounting before applying platform policy.
- Recovery stays latched until PIN-authorized acknowledgement; a clean poll cannot clear it.
- `PolicyController` owns device policy calls. Record original values before mutation and
  verify restoration in stages before releasing ownership. Do not guess `UNKNOWN` ledger
  values. Policy status must reflect platform read-back; stored Chrome policy alone does
  not prove that Chrome applied it.
- Keep dependency wiring lazy: locked boot cannot access credential-protected Room storage.
- Room changes require an explicit registered migration, a version bump, exported JSON in
  `app/schemas/`, and migration-test coverage. Never use destructive migration fallback.
- Package-list changes require matching `<queries>` entries in `AndroidManifest.xml`.
  Use verified IDs and preserve `BlockedBrowsers.NEVER_SUSPEND` (Chrome and WebView).
- AGP supplies built-in Kotlin: do not apply `org.jetbrains.kotlin.android`. Versions live
  in `gradle/libs.versions.toml`; keep AGP's Kotlin, Compose Compiler and KSP compatible,
  and retain `android.disallowKotlinSourceSets=false` while the current KSP requires it.
  Update the README when toolchain versions change. Release R8 is intentionally disabled.
- Preserve `applicationId = "dev.personal.doomstop"`, without a debug suffix, and the
  release signing identity. Increment `versionCode` for builds installed on the phone.
  Release builds require local `keystore.properties`; `-PallowUnsignedRelease=true` is
  only for inspecting development artifacts. Never commit keys, passwords or local paths.
- Diagnostics must not expose PINs or window content. Preserve single-use authorization
  tickets, idempotent extension grants and revocation of admin sessions on backgrounding.

## Product and device boundaries

Keep ordinary installs, Settings, Play Store, calls, SMS and unrelated apps usable without
a PIN. No backend, VPN, root, hidden APIs, analytics, remote PIN approval or Start-session
button. Do not blanket-block installers or set `DISALLOW_INSTALL_APPS`.

Factory resets, device-owner provisioning/removal, disabling USB debugging, and leaving
fixture policies applied on the real phone require explicit user authorization. Follow
the setup/recovery documentation for any authorized device operation.

When behavior changes, update the relevant README limitation and test-report evidence.
Distinguish engine, coordinator, emulator and real-device evidence, and keep accessibility
guards described as best-effort. Re-measure view IDs after app updates when guard behavior
is in question; do not turn historical measurements into current enforcement guarantees.
