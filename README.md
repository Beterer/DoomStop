# DoomStop

An automatic, shared daily limit for Instagram, TikTok and Reddit on one personal Pixel,
enforced with Android device-owner policy.

There is no Start button. Opening a target app starts counting; switching away or locking
the phone stops it. When the shared allowance runs out, the three apps are suspended. A
trusted person holds a six-digit PIN that authorises a fixed extension. The three services'
websites are blocked in Chrome permanently, whether or not app time remains.

The phone otherwise stays a normal phone: installing apps needs no PIN, and calls, SMS,
maps, camera, banking and ordinary browsing are untouched.

Built to [`docs/social-limit-implementation-plan.md`](docs/social-limit-implementation-plan.md).

---

## Current status

| Area | State |
|---|---|
| Application, accounting core, enforcement, PIN, UI | Implemented |
| Unit tests (86, JVM only) | Passing |
| Instrumented tests (55, emulator) | Passing |
| Android lint | Clean |
| Gate A — device-owner provisioning | See `docs/test-report.md` |
| Gate B — actual app suspension | See `docs/test-report.md` |
| Gate C — Chrome policy without an enterprise backend | See `docs/test-report.md` |
| Gate D — usage observation and background survival | Measured on the Pixel 9 itself; see `docs/test-report.md` §6.1 |
| Signed release and in-place update | Verified end to end; see `docs/test-report.md` §7.4 |
| Code review of 2026-09-08 (findings F1-F8) | Fixed and covered by regression tests |
| Provisioning on the real Pixel 9 | **Blocked** — requires a factory reset, not yet authorised |

`docs/test-report.md` records what was measured, on what, and what is still unverified.
Nothing in this repository claims an enforcement guarantee that has not been demonstrated.

## What it does not do

Stated plainly, because a limiter that overstates its reach is worse than none:

- **Device ownership is mandatory.** Without it nothing is enforced. Ordinary device
  administrator permission is not enough, and the app reports "not protected" rather than
  pretending.
- **Browser coverage is a hardcoded list.** A browser that is not in
  `config/BlockedBrowsers.kt` is not blocked. WebView-based apps and in-app browsers inside
  unrelated applications are not covered. This is not Internet filtering.
- **Browsers are blocked from USE, not from installing.** Standard device-owner APIs expose
  no per-package pre-install denylist, and package broadcasts are asynchronous, so a newly
  installed browser has a short launchable window. It is measured, not hidden.
- **Chrome URL policy is not a firewall.** It has documented limits for dynamic pages and
  for tasks already in flight, and it does not retroactively erase a page that is already
  open. The readiness line "Chrome policy stored and read back" means the value in
  DevicePolicyManager is the one this app intended to store — **not** that Chrome has
  accepted it. That second question is answered only by opening a blocked host, which is a
  manual check.
- **Accounting trails the present by thirty seconds.** Usage events are not always queryable
  the instant they occur, so charges are committed on a lag and the unsettled tail is only
  estimated. Enforcement still uses the estimate, so cut-off accuracy is unaffected; what
  the lag buys is that a late event corrects an interval before anything is written for it.
- **The accounting clock is not the phone's clock.** It advances by measured elapsed time,
  and the system clock is adopted only while the two agree. A larger disagreement is refused
  and needs the PIN holder, which also means a genuinely corrected clock needs acknowledging.
- **A target that stays paused-but-never-stopped ends in a question, not an answer.** After
  ten minutes the observer refuses to interpret it, suspends the targets and asks the PIN
  holder, rather than silently deciding the app went away.
- **A controller can be killed or delayed by the OS.** Suspension that is already applied
  persists, but a crash while apps are allowed does not suspend them by magic. Gaps are
  reconciled from usage events on restart; an unreconstructable gap suspends the apps and
  asks the PIN holder, rather than quietly forgiving the time.
- **Six digits is not a strong secret.** PBKDF2 raises the cost of an offline attack on a
  stolen database; the real protection is the app sandbox, backup being disabled, and
  online throttling.
- **Anyone with recovery or firmware control can reset the device.** This protects against
  ordinary on-phone circumvention, not against an owner willing to wipe or reflash.
- YouTube, Shorts, Facebook and Snapchat are out of scope.

## Build

Toolchain, as used for the delivered build:

| Component | Version |
|---|---|
| Android Gradle Plugin | 9.4.0 |
| Gradle | 9.6.0 |
| Kotlin (AGP built-in) | 2.2.10 |
| Compose Compiler plugin | 2.2.10 |
| KSP | 2.2.10-2.0.2 |
| Room | 2.8.4 |
| Compose BOM | 2026.08.00 |
| JDK | 25 (Android Studio JBR) |
| compileSdk / targetSdk | 37 |
| minSdk | 34 (the target Pixel 9 launched on API 34) |
| Android Studio | 2026.1.4 (AI-261.26222.65) |

Three details are not free choices and will break the build if changed independently:

1. AGP 9 supplies **built-in Kotlin**, so `org.jetbrains.kotlin.android` must *not* be
   applied.
2. AGP 9.4.0's module metadata pins KGP 2.2.10 and constrains
   `symbol-processing-gradle-plugin` to exactly `2.2.10-2.0.2` ("KSP version needs to match
   KGP version"). The Compose Compiler plugin must match the same Kotlin version.
3. KSP registers its generated sources through the `kotlin.sourceSets` DSL, which AGP 9
   rejects by default; `android.disallowKotlinSourceSets=false` in `gradle.properties` is
   the escape hatch AGP itself points at.

```bash
./gradlew :app:assembleDebug          # debug build
./gradlew :app:testDebugUnitTest      # 86 unit tests, no device needed
./gradlew :app:lintDebug              # lint
./gradlew :app:connectedDebugAndroidTest   # 55 tests, needs a device or emulator
./gradlew :app:assembleRelease        # release build (needs keystore.properties)
```

### Release signing

`app/build.gradle.kts` reads `keystore.properties` from the repository root. That file and
every `*.jks` / `*.keystore` are gitignored, and there is no fallback to the debug key.
`assembleRelease` and `bundleRelease` depend on a `verifyReleaseSigning` check that **fails
the build** when no key is configured, rather than quietly producing an APK that cannot be
installed over the provisioned app:

```
> No keystore.properties: this release APK would be unsigned and could not be installed
  over the provisioned build.
```

`-PallowUnsignedRelease=true` is the deliberate escape hatch for inspecting an unsigned
artifact during development. The build that goes on the phone must not use it.

```properties
storeFile=../doomstop-release.jks
storePassword=...
keyAlias=doomstop
keyPassword=...
```

**Keep a backup of this key somewhere outside the repository.** Losing it means no future
version can be installed in place over the provisioned device-owner build, and recovering
from that needs a factory reset.

## Setup and recovery

See [`docs/setup-and-recovery.md`](docs/setup-and-recovery.md) for provisioning, the
readiness checklist, granting Usage access, what the PIN can and cannot do, and how to
remove the app cleanly.

## Layout

```
app/src/main/java/dev/personal/doomstop/
  admin/        every DevicePolicyManager call, and nothing else
  config/       target packages, blocked browsers, blocked hosts
  core/         the serialized coordinator, status model, deadline alarms
  data/         Room entities, DAO, database, direct-boot marker
  domain/       pure Kotlin accounting: BudgetEngine, DayBoundary, model types
  monitor/      usage events, visibility tracker, foreground service, receivers
  security/     PIN derivation, verification, throttling
  ui/           Compose screens: status, PIN, setup/diagnostics, admin
```

`domain/` has no Android imports and takes its clock by injection, which is what lets the
accounting tests advance time by days without sleeping.
