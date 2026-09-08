# Personal Pixel social-media limiter: implementation plan

Status: approved product direction, implementation not started. Prepared 2026-09-08.

## 1. Instructions for the implementing agent

Build a small native Android application for one person's Pixel. Android Studio is already installed on the Windows laptop, and the phone is connected with USB debugging enabled. Inspect the actual environment; do not assume a Pixel model, Android API level, SDK path, or device-owner eligibility.

This document is a plan, not authorization to wipe the phone, change its operating system, or disable debugging. When asked to implement, build and test the application first. Obtain explicit authorization before a factory reset or OS installation. Keep debugging enabled throughout development and recovery testing. Explain any required final device restrictions before applying them.

Use stock Android first. GrapheneOS is optional and is not required for this design. Do not root the phone, install GrapheneOS, create a backend, build a VPN, or use hidden Android APIs. Do not replace this design with a kiosk that restricts normal phone use.

The difficult work is reliable enforcement, not UI. Complete the feasibility gates in section 5 before polishing screens. Report a failed gate honestly; do not label an overlay or best-effort timer a hard limit.

## 2. Fixed product requirements

1. Automatically meter native Instagram, TikTok, and Reddit use against ONE shared daily allowance.
2. There is NO Start Session button. Opening a target app starts counting automatically. Switching away or locking the phone pauses counting.
3. Count whole target apps, including their messaging and long-form content. Do not attempt to isolate Reels or individual feeds.
4. When the allowance is exhausted, suspend all target packages through Android device-owner policy.
5. A trusted person chooses and retains a six-digit numeric PIN. The phone user does not know it. Entering it authorizes a fixed amount of extra social-media time or changes to protected settings.
6. The PIN does not unlock the entire phone. Ordinary phone functionality remains available.
7. Permanently block the three services' websites in Chrome, even when native-app allowance or extra time remains.
8. Normal app installation remains available WITHOUT a PIN. Do not set a blanket installation restriction.
9. Maintain a hardcoded list of alternative browser package IDs. Suspend listed browsers already installed and newly installed. No automatic category-based blocking of unrelated applications.
10. No login, server, analytics, cloud sync, Play Store publication, remote PIN approval, or fancy reporting.

Defaults not explicitly selected by the user: propose 30 minutes daily, 10 minutes per authorized extension, and reset at midnight in the phone's timezone captured during setup. Make these editable by the PIN holder. Clearly label them defaults, not previously confirmed decisions. No unused-time rollover. Extra time expires at the next daily reset.

## 3. Honest enforcement boundary

- Device-owner privileges are required for the intended native-app enforcement. Ordinary device administrator permission alone is insufficient.
- Standard device-owner APIs do not expose a universal per-package pre-install denylist. The browser list therefore blocks USE after installation, not the installation transaction itself. Installation broadcasts are asynchronous; a short first-launch window may exist. Measure it.
- Unknown browsers, differently packaged clients, websites acting as proxies, and embedded browsers in other apps can bypass the limited browser coverage. These are accepted phase-1 scope limits. Do not claim comprehensive Internet filtering.
- Chrome URL policy has documented limitations for dynamic pages and ongoing tasks. Permanently apply it before normal use and test existing tabs. It is not a network firewall.
- A controller can be killed or delayed by the OS. Suspension already applied persists, but a crash while apps are allowed does not magically suspend them. Recovery and watchdog behavior must be tested, and any remaining gap reported.
- Someone with recovery/firmware control can ultimately reset the device. This app protects against ordinary on-phone circumvention, not an owner prepared to wipe or reflash it.
- YouTube/Shorts, Facebook, Snapchat, and other services are outside the agreed scope.

## 4. Stack and project layout

Use Kotlin, one Android application module, Jetpack Compose for three simple screens, coroutines, and Room for transactional local state. No dependency injection framework or multi-module architecture. Use Android Studio's installed JDK and compatible stable Android Gradle Plugin/Kotlin/SDK versions; record exact versions in the README and commit the Gradle wrapper. Target a current supported SDK, not an old target to evade restrictions. Set minimum SDK to suit the actual Pixel; API 30 or higher is a reasonable baseline if supported by the device.

Suggested application ID: `dev.personal.sociallimit`. Use a single ID and a stable signing key across updates. Never lose the release signing key. Keep its passwords and private material outside source control and deliverable logs.

Suggested files:

```text
app/src/main/
  AndroidManifest.xml
  java/dev/personal/sociallimit/
    MainActivity.kt
    admin/LimiterAdminReceiver.kt
    admin/PolicyController.kt
    monitor/UsageMonitorService.kt
    monitor/UsageEventReader.kt
    monitor/VisibleTargetTracker.kt
    monitor/BootReceiver.kt
    monitor/PackageChangeReceiver.kt
    monitor/DeadlineReceiver.kt
    domain/BudgetEngine.kt
    domain/DayBoundary.kt
    security/PinManager.kt
    data/LimiterDatabase.kt
    data/LimiterDao.kt
    data/Entities.kt
    config/TargetPackages.kt
    config/BlockedBrowsers.kt
    config/BlockedSites.kt
    ui/StatusScreen.kt
    ui/PinScreen.kt
    ui/AdminScreen.kt
  res/xml/device_admin_receiver.xml
```

`BudgetEngine` must be pure Kotlin with an injected clock so tests can advance time without sleeping. Keep all Android policy calls inside `PolicyController`. Serialize accounting and policy decisions through one coroutine actor/mutex; receivers must not concurrently mutate balances.

## 5. Feasibility gates: complete in this order

### Gate A: inventory and provisioning

Locate Android Studio, SDK platform-tools, and the bundled JDK using environment variables and installed configuration. Do not download duplicate installations blindly. Read-only commands can include:

```powershell
# Replace the path with the discovered platform-tools directory.
$adbPath = 'C:\actual-sdk-path\platform-tools\adb.exe'
& $adbPath devices -l
& $adbPath -s '<verified-serial>' shell getprop ro.product.model
& $adbPath -s '<verified-serial>' shell getprop ro.build.version.release
& $adbPath -s '<verified-serial>' shell getprop ro.build.version.sdk
& $adbPath -s '<verified-serial>' shell pm list users
& $adbPath -s '<verified-serial>' shell dumpsys device_policy
```

Do not dump accounts, messages, or browsing history into logs. Determine whether existing accounts/profiles/management prevent owner provisioning. Prefer an emulator with a comparable Android version for initial owner tests.

Implement the `DeviceAdminReceiver` and its manifest/XML declarations. When provisioning is authorized, Android documents this development command:

```text
adb -s <verified-serial> shell dpm set-device-owner dev.personal.sociallimit/.admin.LimiterAdminReceiver
```

This command changes management state. It can fail on a configured phone; no accounts is a documented development prerequisite, not a guarantee that an arbitrary existing setup qualifies. Do not remove accounts or reset the phone automatically. If necessary, prepare a backup/provisioning plan and wait for explicit reset authorization. Verify success with `isDeviceOwnerApp()` and system policy state.

Gate passes only when owner privileges are demonstrated on a test device and the real Pixel's provisioning path is known.

### Gate B: actual app suspension

Call `DevicePolicyManager.setPackagesSuspended(admin, packages, true/false)`. Inspect its returned failures and verify each installed target's suspension state. Test an already-open target app, not just its launcher icon. Confirm returning through recents, notifications, and deep links cannot keep using an exhausted target. Provide access to the limiter via its launcher and notification; do not depend on opening a custom activity over the blocked app.

### Gate C: Chrome policy without an enterprise backend

Use local device-owner `setApplicationRestrictions()` for `com.android.chrome`. Read Chrome's managed restriction schema and apply `URLBlocklist` as its Android string containing a JSON array, rather than guessing a string-array Bundle type. Candidate:

```kotlin
val restrictions = dpm.getApplicationRestrictions(admin, "com.android.chrome")
restrictions.putString("URLBlocklist", JSONArray(blockedHosts).toString())
dpm.setApplicationRestrictions(admin, "com.android.chrome", restrictions)
```

Preserve unrelated existing restrictions. Inspect `chrome://policy` and verify policy application on the actual Chrome build WITHOUT enrolling a paid management product. Do not assume that setting a Bundle means Chrome accepted it.

Seed hosts: `instagram.com`, `cdninstagram.com`, `tiktok.com`, `tiktokv.com`, `tiktokcdn.com`, `reddit.com`, `redd.it`, `redditmedia.com`, `redditstatic.com`. Verify hostname matching semantics from Chrome documentation: include subdomains, all schemes, and all paths. Test `www`, `m`, `old.reddit.com`, `new.reddit.com`, `vm.tiktok.com`, and `vt.tiktok.com`. Keep the list small and readable; content domains supplement direct-site blocking and do not imply comprehensive embedded-content filtering.

Test normal and incognito tabs, redirects, and a tab open before policy application. During initial setup, have the user close existing target tabs and restart Chrome if needed. Ordinary unrelated browsing must work. If policy cannot be locally enforced on the actual build, stop and explain the gap rather than silently adding a VPN.

### Gate D: automatic usage observation and background survival

Start with `UsageStatsManager.queryEvents()` under explicitly granted Usage Access, polled approximately once a second by a foreground service while the screen is interactive. Use activity lifecycle events and screen/keyguard state, not daily aggregate usage totals. Device ownership does not automatically grant Usage Access; verify its actual grant through AppOps and the relevant Settings screen.

Prototype before choosing the final tracker. Test rapid switching, multiple activities in the same package, screen off/on, lock/unlock, split-screen and picture-in-picture. `ACTIVITY_RESUMED`/`ACTIVITY_PAUSED` are lifecycle signals, not a complete visible-window API. Do not claim exact visibility simply by remembering the latest resumed package. Where possible track activity instance IDs and stopped/destroyed lifecycle state; deduplicate overlapping query windows.

If usage events cannot meet the visibility tests, use a narrowly scoped AccessibilityService for package/window visibility observation, with explicit user setup. Retrieve window metadata only; do not collect text, screenshots, keystrokes, or UI content. Keep UsageEvents for recovery where reliable. This is a conditional fallback, not a reason to build two full trackers upfront. If neither approach passes, explain the limitation and obtain agreement on narrower counting semantics before proceeding.

On modern Android, a device-owner application qualifies for the documented `systemExempted` foreground-service type. Verify the current prerequisites and use `FOREGROUND_SERVICE` plus `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`, declaring the service type on supported versions. Start it only after ownership is confirmed. Provide a persistent, quiet notification with remaining time and an action to open the limiter. Do not misuse `dataSync` or fake media playback.

Test service restarts, boot launch restrictions, task-manager stop behavior, revoked Usage Access, and battery saver. `START_STICKY` is useful but is not a reliability guarantee. Do not use WorkManager as a one-second enforcement timer.

## 6. Accounting algorithm and persistence

Counting rule: charge real elapsed time once whenever at least one target is visible and the screen is unlocked. Two visible targets still consume only one second per second. Screen-off/background audio does not consume allowance. A visible target in split-screen or picture-in-picture should count; gate D must establish whether this is achievable on the Pixel.

Persist these concepts in Room, using explicit schema migrations:

- Settings: daily allowance, extension length, fixed reset timezone, configuration state.
- Current budget: day ID, base allowance for that day, total charged milliseconds, extra granted milliseconds.
- Checkpoint: boot identity, last accounted monotonic time, wall-clock anchor, usage-event cursor, last known target visibility, clean/uncertain state.
- PIN verifier: salt, KDF parameters, verifier; failed-attempt count and next allowed attempt.
- Minimal diagnostic events: protection errors and extension grants, with no PIN or content data.

Use `SystemClock.elapsedRealtime()` for within-boot durations. Never calculate scrolling duration from wall-clock subtraction. UsageEvents timestamps use wall time, so maintain an anchor for conversion and explicitly handle wall-clock jumps. Use the configured timezone for day IDs, not whatever timezone changes to later.

Conceptual event loop:

```text
on start / tick / visibility change / package change / unlock / admin action:
    obtain serialized access
    load and validate state
    reconcile any unaccounted interval exactly once
    split an interval crossing midnight between the correct days
    apply a verified daily rollover, at most once per day
    remaining = max(0, today's base + today's extensions - today's charged)
    persist accounting before allowing further target access
    suspend installed targets if remaining == 0 OR monitoring is unhealthy
    otherwise unsuspend only targets that this app manages
    always suspend installed browsers from the hardcoded list
    maintain permanent Chrome blocklist
    update status/notification and next enforcement deadline
```

Checkpoint while targets are active at most every second initially; measure write/battery impact before optimizing. A missed callback must not mean a missed debit: reconcile intervals, not callback counts. Query a small overlapping event window and deduplicate by stable event fields; do not repeatedly scan all usage history. Handle empty results versus unavailable permission distinctly.

At target entry, maintain a deadline based on remaining allowance as a backup to polling. Cancel/recompute when visibility changes. Use an exact alarm only after checking permission/eligibility and actual OS behavior; do not assume it is granted. An alarm is defense in depth, not a replacement for visibility accounting.

On process restart, reconcile from durable checkpoints and available usage events before unsuspending anything. On reboot, elapsedRealtime resets: never compare monotonic timestamps from different boots. Preserve today's balance. If a gap cannot be reconstructed, keep targets suspended and show a clear recovery state; do not silently award a fresh allowance or guess a large charge. Allow the PIN holder to resolve it.

For early boot, use a direct-boot-aware receiver and a tiny device-protected marker that protection was enabled. Reapply target suspension before credential storage is available where the OS permits. After first unlock, load the database, recover accounting, and restore permitted access. Do not store the PIN in device-protected storage just to simplify boot. Test and report the actual boot gap.

Roll over automatically at the next valid day boundary; also check rollover on every wake/event. Configure automatic system time and prevent ordinary manual time changes through appropriate owner policy if needed. Leave automatic travel timezone behavior alone by using a fixed accounting timezone. Do not grant repeated days when the clock rolls backward then forward. Large unexplained clock anomalies should produce a visible recovery state, not extra time.

## 7. Native app and browser package lists

Use package IDs, never display names or substring matching. Seed target candidates:

```text
Instagram: com.instagram.android
TikTok:    com.zhiliaoapp.musically
Reddit:    com.reddit.frontpage
```

Verify actual installed IDs on the phone. Record regional official variants explicitly if relevant; do not assume the TikTok seed covers every distribution. Reinstalling a target must retain the shared balance and immediately apply its current suspension state.

Hardcoded browser candidate families: Firefox (stable/beta/nightly/Focus), Brave, Edge, Opera/Mini/GX, Samsung Internet, Vivaldi, DuckDuckGo, Tor Browser, and Chrome beta/dev/canary. Verify each package ID against official distribution metadata before adding it. Put a comment with product name/source beside each ID. Do not guess IDs based on the brand name. Chrome stable is allowed; never suspend Android System WebView.

Register manifest receivers for package added/replaced events with the `package` data scheme. Check receiver behavior and package visibility on the target SDK. Declare explicit package queries for the finite lists; do not add broad package inventory permission without a demonstrated need. Reconcile the list at setup, boot, controller update, and detected package changes. Check suspension results and expose failures. If browser installation races with launch, record the measured limitation rather than adding a global installation block.

## 8. PIN and administrative actions

- Exactly six ASCII digits, including leading zeros. Treat it as a string.
- Trusted person enters and confirms it during setup. No default PIN. Do not ask for the real PIN in chat, logs, shell arguments, or source code.
- Use random salt and PBKDF2-HMAC-SHA256 with stored version/parameters. Benchmark a work factor around 200-500 ms on the actual Pixel and verify off the main thread. Constant-time verifier comparison.
- Six digits have limited entropy; offline resistance is limited. Protect app data, disable backup/data extraction for sensitive state, and rely on online throttling plus Android sandboxing. Do not claim hashing makes six digits high entropy.
- Persist attempt counters. After five consecutive wrong attempts, delay further attempts for 30 seconds; double subsequent delays up to 30 minutes. Reboot must not clear the counter or shorten an active cooldown. Wrong attempts must not affect ordinary phone use.
- Mask input, suppress keyboard learning where supported, use FLAG_SECURE on PIN/admin screens, and never display/log the saved PIN.
- A successful request for more time adds one configured extension in a transaction, then reapplies suspension state. Websites stay blocked. Repeated taps must not duplicate the grant. Require a fresh PIN for each new extension.
- A PIN-authenticated admin screen can change limits, replace the PIN, and invoke maintenance/recovery. Clear authentication on leaving the screen or after a short idle timeout; never provide a permanent remembered unlock.
- Store no universal recovery password. Explain that losing the PIN can require deliberate administrator recovery or ultimately a reset.

## 9. Device policies: narrowly scoped

Protect the controller with supported owner policies, including `setUninstallBlocked()` and, where supported, `setUserControlDisabledPackages()` for this controller only. Test their real effects on force-stop, clear-data, and task-manager stop. Do not globally disable app controls or Settings.

After development and recovery tests, consider restrictions on adding/switching users, safe boot, debugging, and manual date/time changes to close demonstrated bypasses. Existing profiles require separate assessment; do not remove them automatically. Never disable USB debugging until the user approves the reviewed final configuration and the signed update/recovery path works.

DO NOT set `DISALLOW_INSTALL_APPS`. DO NOT require a PIN to install unrelated apps. DO NOT blanket-block package installers, Play Store, system settings, VPN apps, camera, calls, SMS, maps, or notifications. DO NOT install factory-reset-protection account policies or erase data.

Maintain a ledger of policy values the app changed and previous values where available. Recovery should restore those values and unsuspend only packages this app suspended. Do not wipe unrelated Chrome restrictions. Implement a PIN-authorized maintenance action to restore debugging when needed and an explicitly confirmed removal path appropriate to the actual Android version. Test removing the development owner on an emulator; do not assume a test-only ADB removal command works for the release build.

## 10. Minimal UI

Status screen: remaining shared time, today's allowance, reset time, the three target apps, protection status, and Request more time. Show Settings behind a PIN. No Start button or motivational feed.

PIN screen: numeric masked entry, submit/cancel, and cooldown feedback.

Admin screen: allowance, extension length, reset timezone, change PIN, and clearly separated maintenance/removal actions. Show the effect of changing today's allowance before applying it. Apply limit changes to today's base without resetting already charged time. If the new total is below usage, suspend immediately.

Setup should show actual readiness: device owner, monitoring permission, notification state, Chrome policy verified, PIN set, and target suspension test. Do not display Protected when a required capability is missing. Diagnostics must identify the failed capability and a practical recovery action.

## 11. Implementation order and deliverables

1. Inventory tools/device and document provisioning prerequisites; no destructive actions.
2. Scaffold a buildable single-module app and pure budget model.
3. Complete gates A-D with minimal UI on an emulator and, when authorized, the Pixel.
4. Implement persistent accounting, rollover, recovery, and actual suspension.
5. Implement permanent Chrome policy and browser-package reconciliation.
6. Add PIN setup, throttling, extensions, and settings.
7. Add narrowly scoped tamper resistance and test maintenance/removal.
8. Execute the test matrix below; fix failures before final hardening.
9. Produce a signed, non-debuggable release APK, source, build instructions, setup/recovery guide, verified package lists, and a short test report listing exact phone/OS/Chrome versions and remaining limitations.

Development builds may be test-only for reversible owner testing. The installed final build must not be debuggable or test-only. Plan the transition before provisioning the real device; do not assume signing/app-ID changes can replace an active owner. Retain the release key for future in-place updates. Installing an update must preserve budget and PIN state.

## 12. Acceptance tests

Use a short test allowance such as 60 seconds. Restore real settings afterward. Pure accounting tests use a fake clock; physical-device checks use a stopwatch and recorded outcomes.

| Test | Required result |
|---|---|
| Open any target without opening limiter | Counting starts automatically |
| Use Instagram 20 s, Reddit 20 s, TikTok 20 s | Shared allowance exhausted; all three suspended |
| Home/unrelated app/locked screen | No ongoing social-time debit |
| Rapid switch and activity transitions | No duplicated or lost intervals; no time created |
| Split-screen and PiP | Visible target charged once; any unsupported case explicitly resolved |
| Expiry while scrolling/playing cached video | Target becomes unusable; initial cutoff goal <= 2 s while monitor healthy |
| Notifications, recents, deep links after expiry | No access to suspended targets |
| Correct PIN | Exactly one extension; native apps available; sites still blocked |
| Wrong PIN, repeated attempts, reboot | No grant; persistent throttling |
| Midnight while app open | Interval split correctly; exactly one reset |
| Timezone/manual time change | No repeatable allowance reset exploit |
| Reboot with exhausted/remaining allowance | Correct balance retained; no fresh daily grant |
| Process death while target open | Reconcile usage and resume enforcement; measure crash gap |
| Usage permission revoked | Visible unhealthy state and target suspension when detected |
| Force-stop/clear-data/uninstall controller | Final configuration prevents ordinary settings bypass; report exceptions |
| Chrome normal/incognito/mobile/old/short URLs | Direct target websites blocked permanently |
| Chrome policy after reboot/update/reinstall where possible | Policy remains effective or is automatically reapplied |
| Target tab open before initial policy | Setup closes/restarts as needed; no claim of retroactive page erasure |
| Install known blocked browser | Installation may finish; app promptly suspended; measure launch race |
| Install unrelated app | Installs and runs without PIN |
| Reinstall target after exhaustion | Remains blocked; balance not reset |
| Calls, SMS, maps, camera, banking, ordinary Chrome sites | Normal operation |
| Battery saver and overnight idle | Monitoring recovers on use; no accidental reset; record battery impact |
| Signed in-place app update | Owner status, PIN, usage, and policies preserved |
| PIN-authorized maintenance/recovery | Restores intended access without corrupting state or unrelated policies |

Do not mark complete solely because Gradle builds. Required checks: unit tests for budget arithmetic/rollover/idempotent grants/recovery, Android lint, a release build, and physical-device evidence for enforcement and Chrome policy. If a <= 2 s cutoff cannot be achieved, report the measured maximum and cause before calling it finished. No consumer Android process guarantees perfect uninterrupted enforcement; state residual gaps clearly.

## 13. Primary references

Check the current documentation against the actual API level during implementation.

- [Device-owner development provisioning and user restrictions](https://developer.android.com/work/dpc/dedicated-devices/cookbook)
- [DevicePolicyManager: suspension, app restrictions, uninstall and user-control protection](https://developer.android.com/reference/android/app/admin/DevicePolicyManager)
- [Android enterprise app suspension behavior](https://developer.android.com/work/dpc/security)
- [UsageStatsManager and unlocked-user limitations](https://developer.android.com/reference/android/app/usage/UsageStatsManager)
- [UsageEvents lifecycle event semantics](https://developer.android.com/reference/android/app/usage/UsageEvents.Event)
- [Foreground-service types and systemExempted prerequisites](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Exact-alarm access and scheduling](https://developer.android.com/develop/background-work/services/alarms)
- [Package installation broadcasts](https://developer.android.com/reference/android/content/Intent#ACTION_PACKAGE_ADDED)
- [Chrome URLBlocklist Android schema and limitations](https://chromeenterprise.google/policies/url-blocklist/)
- [Chrome URL filter pattern syntax](https://support.google.com/chrome/a?p=url_blocklist_filter_format)
- [GrapheneOS usage guide, if later chosen independently](https://grapheneos.org/usage)

## 14. Handoff prompt

> Implement the personal Pixel social-media limiter described in this file. Follow the fixed requirements and feasibility gates. Android Studio is installed and the Pixel is connected via USB debugging. First inspect the environment read-only, then build and test incrementally. Keep normal app installation unrestricted. Do not introduce manual sessions, a VPN, a backend, or blanket device lockdown. Do not wipe the phone, install another OS, remove accounts/profiles, or disable debugging without explicit authorization. Surface platform limitations rather than hiding them. Deliver source, a signed APK, verified setup/recovery instructions, and a concise physical-device test report.
