# DoomStop — test report

Prepared 2026-09-08, revised 2026-09-09 after the code review in section 7. This records
what was actually run, on what, and what has not been verified. Where a result is a
measurement it is the measured number, not a target.

### How to read a "pass" here

Four kinds of evidence appear below and they are **not** interchangeable. Every row in the
acceptance matrix names which one it rests on.

| Class | What it means | What it cannot show |
|---|---|---|
| **engine** | A JVM unit test of the pure accounting core against an injected clock. | Anything about persistence, the platform, or several passes in sequence. |
| **coordinator** | An instrumented test: real Room database, several sequential passes, and a coordinator rebuilt from the persisted state the way a restarted process rebuilds it. Device policy is faked so failures can be injected. | That the real DevicePolicyManager behaves as the fake does. |
| **emulator** | An instrumented test against a genuinely provisioned device owner on AVD `doomstop36`, with stub packages standing in for the three apps. | Hardware behaviour, one API level up, with the real apps. |
| **device** | Run on the Pixel 9. | — |

**No row in this report is class "device".** The phone has not been provisioned.

---

## 1. Environment

### Build host

| | |
|---|---|
| OS | Windows 11 Home 10.0.26200 |
| Android Studio | 2026.1.4 (AI-261.26222.65.2614.16204760) |
| JDK | OpenJDK 25.0.3 (Android Studio JBR) |
| Gradle / AGP | 9.6.0 / 9.4.0 |
| Kotlin (AGP built-in) / Compose plugin | 2.2.10 / 2.2.10 |
| KSP / Room | 2.2.10-2.0.2 / 2.8.4 |
| Compose BOM | 2026.08.00 |
| SDK platform / build-tools | android-37.0 / 36.0.0 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 34 |

### Devices

**A. Target phone — Pixel 9 (`tokay`), serial 48090DLAQ001Z2**

| | |
|---|---|
| Android | 17 (API 37), build `CP2A.260705.006` |
| Security patch | 2026-07-05 |
| Build type | user, verified boot `green` |
| Launch API level | 34 |
| Chrome | 152.0.7977.76 |
| Users | 1 |
| Accounts | 20 |
| `device_provisioned` / `user_setup_complete` | 1 / 1 |
| Targets installed | `com.instagram.android`, `com.zhiliaoapp.musically`, `com.reddit.frontpage` |
| Other browsers installed | `org.mozilla.firefox`, `org.torproject.torbrowser` |

**Status: read-only inventory only.** Nothing has been installed on this phone and it has
not been provisioned. See section 3.

**B. Emulator — AVD `doomstop36`**

| | |
|---|---|
| Image | `system-images;android-36;google_apis_playstore;x86_64` |
| Android | 16 (API 36) |
| Hardware profile | Pixel 9 |
| Chrome | 133.0.6943.137 |
| Device owner | `dev.personal.doomstop` — provisioned |

The emulator is one API level behind the phone because no API 37 system image is published
yet. That difference is the main reason the gate results below are not a substitute for
repeating them on the phone.

### Test fixtures

The three target apps are not installable on an emulator, so **stub APKs** were built —
trivial single-activity apps of my own that claim the real application IDs — so suspension
could be exercised against the actual package names rather than a stand-in. They exist only
in the scratch directory, are never distributed, and were never installed on the phone.

---

## 2. Automated tests

| Suite | Count | Result |
|---|---|---|
| Unit (`testDebugUnitTest`), no device — class **engine** | 86 | all pass |
| Instrumented (`connectedDebugAndroidTest`) — classes **coordinator** and **emulator** | 55 | all pass, none skipped |
| Android lint (`lintDebug`, `lintRelease`) | — | clean |
| Release build without a key | — | **fails**, by design: `verifyReleaseSigning` refuses to produce an uninstallable artifact |
| Release build with `-PallowUnsignedRelease=true` | — | `app-release-unsigned.apk`, not debuggable, not test-only, `allowBackup=false` |

Unit tests cover budget arithmetic, midnight splitting, a DST day that skips local midnight,
rollover idempotence, boot/clock/monotonic anomalies, latched recovery, the settled
accounting window, observer serialization and visibility semantics, and PIN throttling
against both clocks.

Instrumented tests split into three files:

- `data/LimiterDaoTest` and `data/MigrationTest` — database guarantees and the version 1 to
  version 2 upgrade, including that an unresolved history is **not** resolved by an app update.
- `core/CoordinatorRegressionTest` — the regressions from section 7, exercised the way they
  actually occurred: many sequential passes against persisted state, a coordinator rebuilt
  from that state, and injected policy failures.
- `admin/PolicyControllerTest` and `core/EnforcementIntegrationTest` — the real
  DevicePolicyManager against a provisioned emulator.

**Four defects were found by tests or measurement rather than by inspection**: two in
section 6, and two more that only surfaced once the review's regression tests existed
(section 7.1).

---

## 3. Gate A — provisioning

**Emulator: PASS.**

```
Success: Device owner set to package dev.personal.doomstop/.admin.LimiterAdminReceiver
Active admin set to component dev.personal.doomstop/.admin.LimiterAdminReceiver
```

`isDeviceOwnerApp()` returns true, and the foreground service runs with
`types=0x00000400` (`FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED`), confirming the device-owner
exemption is real rather than assumed.

**Pixel 9: BLOCKED, not attempted.**

The phone reports `device_provisioned=1`, `user_setup_complete=1`, one user and 20 accounts.
Android's ADB provisioning path applies the account and user checks once setup has
completed, so `dpm set-device-owner` will be refused with `STATUS_ACCOUNTS_NOT_EMPTY`. The
command was **not run** on the phone, no accounts were removed, and nothing was wiped.

In practice this means a factory reset, with provisioning done before any account is added.
That is the user's decision and it has not been authorised. Account names were never printed
anywhere; only a count was taken.

---

## 4. Gate B — actual app suspension

**Emulator: PASS.**

| Check | Result |
|---|---|
| `setPackagesSuspended` applied and **read back** per package | pass — asserted against `isPackageSuspended`, not the call's return value |
| Release restores availability | pass |
| Browsers stay suspended while allowance remains | pass |
| Chrome stable and every WebView package never suspended | pass |
| Re-applying is idempotent (the reinstall path) | pass |
| Launching a suspended target | **blocked** — system dialog "Blocked by work policy"; resumed activity is `com.android.settings/.enterprise.ActionDisabledByAdminDialog`, not the target |
| Uninstalling the controller | refused: `Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]` |
| Self-protection scope | `getUserControlDisabledPackages` contains this package **only**; no other app's controls affected |
| `DISALLOW_INSTALL_APPS` | never set — asserted explicitly |

Not yet checked on hardware: returning through recents, notifications and deep links after
expiry. The mechanism is the platform's own suspension, which the dialog above shows is
enforced at launch, but the specific routes have not been walked on the phone.

---

## 5. Gate C — Chrome policy without an enterprise backend

**Emulator: PASS**, with no management product and no enrolment.

The restriction schema was read out of the **installed Chrome APK** rather than guessed:
`URLBlocklist` is declared with `restrictionType=6` (`RestrictionEntry.TYPE_STRING`), so the
value is a String containing a JSON array. The legacy `URLBlacklist` key does not exist in
Chrome 152. Only `URLBlocklist` is written.

Chrome reports "Your browser is managed by your organization" and renders:

> **www.instagram.com is blocked** — Your organization doesn't allow you to view this site

| URL | Expected | Result |
|---|---|---|
| `https://www.instagram.com/` | blocked | blocked |
| `https://m.instagram.com/` | blocked | blocked |
| `https://old.reddit.com/` | blocked | blocked |
| `https://new.reddit.com/` | blocked | blocked |
| `https://vm.tiktok.com/` | blocked | blocked |
| `https://vt.tiktok.com/` | blocked | blocked |
| `https://redd.it/abc` | blocked | blocked |
| `http://tiktok.com/` (http scheme) | blocked | blocked |
| `https://old.reddit.com/` **in incognito** | blocked | blocked |
| `https://example.org/` | loads | loads |
| `https://en.wikipedia.org/wiki/Android` | loads | loads |

This confirms the documented filter rule — a bare host matches its subdomains across all
schemes and paths — which is why the list carries no leading dots and does not enumerate
`www`/`m`/`old`/`new`/`vm`/`vt`.

Also verified: applying the blocklist **preserves unrelated Chrome restrictions** (a probe
`HomepageLocation` survived), and restoring removes only the one key.

Not verified: behaviour across a Chrome update or reinstall, and a target tab that was
already open before the policy was first applied. Setup instructs the user to close such
tabs and restart Chrome; the policy is not claimed to erase a loaded page.

---

## 6. Gate D — usage observation and background survival

**Emulator: PASS** for the core behaviour, using real `UsageStatsManager` events (not the
test fake).

| Check | Result |
|---|---|
| Opening a target **without opening the limiter first** starts counting | pass — 21 s charged for ~20 s foreground |
| Going Home stops counting | pass |
| Foreground service type | `systemExempted`, granted because the app is device owner |
| Cut-off when the allowance expires while the app is open | **683 ms** overshoot against a ≤ 2 s goal |
| Newly installed hardcoded browser suspended | **366 ms** after install completes |

The 21 s for 20 s of use is the 1-second poll granularity plus launch time; the error is
toward charging, which is the intended direction.

The cut-off and browser-race figures both include measurement overhead (app launch, a
100 ms poll interval, and `dumpsys` latency), so the true values are slightly smaller.

### Defect 1 — the browser install race was 12× worse than it needed to be

First measurement: **4574 ms** from install completing to suspension. Investigation showed
the manifest `PACKAGE_ADDED` receiver **never fires on API 36** — modern implicit-broadcast
restrictions — so re-application was falling through to the 30-second periodic resync. The
runtime receiver registered by the foreground service *does* receive the broadcast.

Fixed by forcing an immediate policy re-application on package-change, boot and admin
triggers instead of waiting for the timer. Re-measured: **366 ms**.

The residual window is inherent and is not engineered away: device-owner APIs expose no
per-package pre-install denylist, so this blocks *use* after installation. A blanket
installation restriction would close it and is forbidden by requirement 8.

### Defect 2 — killing the monitor and continuing to scroll was nearly free

The first draft clamped any inherited-visibility segment to two minutes, so a process death
while a target was open could only ever be charged two minutes. A unit test written against
the intended behaviour failed and exposed it.

Corrected: while the usage-event source is *available*, silence is evidence rather than
ignorance — leaving an app emits `ACTIVITY_PAUSED`/`STOPPED` and locking emits
`SCREEN_NON_INTERACTIVE`, so their absence is positive evidence the app stayed on screen,
and the gap is charged in full. Only past a four-hour unbroken claim does the pass refuse
outright: nothing charged, checkpoint `UNCERTAIN`, targets suspended, PIN holder resolves it.
No large charge is ever guessed.

### Platform constraint

`UsageEvents.Event.getInstanceId()` is **not public API** on API 37 (verified against
`android.jar`), so the plan's suggestion of keying on activity instance IDs is unavailable.
Activities are keyed by package + class name instead. The hole that opens — a `STOPPED` from
an older instance of the same class killing a live session — is closed by the lifecycle
contract: `onPause` always precedes `onStop`, so a `STOPPED` arriving while the tracked key
is `RESUMED` belongs to an older instance and is ignored.

### Not yet measured

Split-screen and picture-in-picture on real hardware; screen-off and battery-saver
behaviour over a full night; task-manager stop and restart timing; the direct-boot window
between `LOCKED_BOOT_COMPLETED` and first unlock; battery impact of the one-second poll.

---

## 7. Code review of 2026-09-08 — findings F1 to F8

An external review of commit `cdbd789` reproduced five defects against the compiled classes
and identified three more by reading the source. All eight are fixed. Each has a regression
test that fails against the old behaviour.

| # | Finding | Fix | Covered by |
|---|---|---|---|
| F1 | A blind pass produced `UNCERTAIN`, but the next successful poll re-anchored as `CLEAN`, so losing Usage Access briefly discarded the interval **and** reopened the apps. | Recovery is a latched `RecoveryRequest` on the checkpoint, carrying its reason and time range, cleared only by the PIN-authorized acknowledgement. A single failed query inside a poll interval is a transient read, not a hole. | engine: `a lost interval stays lost until it is acknowledged`; coordinator: `losingTheUsageSourceLeavesRecoveryOutstandingAcrossManyHealthyPolls`, `anOutstandingRecoveryRequestSurvivesACoordinatorRestart`, `acknowledgingRecoveryResumesAccessAccordingToTheRetainedBalance` |
| F2 | A restarted process built an **empty** observer while the checkpoint said a target was on screen; the inherited interval was charged once and visibility then recorded as false, so the rest of that session was free. | The observer's state is serialized and written in the same transaction as the checkpoint, and restored before any accounting runs. If it cannot be reconstructed while the checkpoint claimed visibility, recovery is latched instead of a guess being made. | engine: `an app left open across a restart keeps being metered` and three others; coordinator: `anAppLeftOpenAcrossAProcessRestartKeepsBeingCharged`, `leavingTheAppAfterARestartStopsTheChargeAtTheRightMoment` |
| F3 | A large forward wall-clock jump only produced a diagnostic; the day was then selected from the new time, so an exhausted user could reach an unvisited date with a full allowance. PIN cooldowns had the same weakness in the other direction. | Accounting runs on an **accepted** clock that advances by monotonic duration; the system clock is adopted only while it agrees, per pass (5 s) and cumulatively (2 min). Beyond that the jump is refused and recovery is latched. PIN cooldowns now carry a monotonic deadline alongside the wall-clock one. | engine: `a wall-clock jump forward is refused...`, `repeated small corrections cannot be accumulated into a large one`, `winding the clock forwards does not end a lockout early either`; coordinator: `aForwardWallClockJumpDoesNotCreateANewSpendableDay`, `ordinaryMidnightRolloverStillGrantsExactlyOneAllowance` |
| F4 | The admin session survived the app going to the background, so a trusted person could authenticate, hand the phone back, and the phone's user could return to an open settings screen. PIN replacement went through the same unguarded call as first-time creation. | The session is revoked on real backgrounding (a configuration change is distinguished), has a monotonic expiry checked at every protected operation, and is re-checked at commit time. Creating the first PIN and replacing one are separate operations, and replacement re-authorizes after the key is derived. | engine: `a first PIN cannot be created once one exists`, `replacing a PIN without authorization changes nothing`, `authorization that lapses while the key is derived does not save the new PIN` |
| F5 | Late events were applied to the observer but filtered out of accounting, so an interval could be charged at 2000 ms when only 600 ms was visible. | Accounting trails now by 30 s. The unsettled tail is recomputed from durable retained events on every pass, so a correction replaces an estimate; only the settled window is written, and only once. | engine: `only the settled part of the window is charged`, `consecutive settled windows charge each interval exactly once`, `a correction inside the open window replaces an estimate...`; coordinator: `aLateArrivingPauseCorrectsTheChargeInsteadOfBeingIgnored`, `deliveringTheSameEventsLateProducesTheSameDayTotal`, `replayingTheOverlapWindowNeverChargesAnIntervalTwice` |
| F6 | A paused-but-visible activity stopped being metered after 90 seconds, so a still-displayed target became free. | Metering continues for ten minutes; a screen-off clears a paused activity (which bounds a lost `STOPPED` to one screen-on session); past ten minutes the observer declares the state **unresolved**, which latches recovery rather than deciding either way. | engine: `a paused activity keeps being metered well past the old ninety-second cut-off`, `a paused activity that never stops becomes unresolved rather than free`, `turning the screen off clears a paused activity...` |
| F7 | `restoreDevice` continued to clear self-protection and relinquish ownership after a failed step, and the UI announced success while ignoring two of the three results. | Restore is staged; each stage verifies; nothing downstream of a failure runs; ownership is released only when every earlier stage passed. A failure leaves a persisted maintenance state that stops the poll loop asserting policy, names the failed step, and can be retried or cancelled. | coordinator: `aFailedUnsuspendStopsTheRestoreAndKeepsOwnership`, `aFailedChromeRestoreStopsBeforeOwnershipIsReleased`, `retryingAfterTheInjectedFailureIsClearedCompletesTheRestore`, `maintenanceModeStopsThePollLoopFightingTheRestore` |
| F8 | The ledger was written **after** the policy call and recorded `previousValue = null` for every package, so restore unsuspended a hardcoded list and could not tell "was not suspended" from "could not tell". | The ledger is write-ahead and records `ABSENT` / `VALUE` / `UNKNOWN` distinctly, for suspension, Chrome's blocklist, the user-control list, uninstall blocking, user restrictions and the automatic-time setting. Restore uses it, merges rather than overwrites list policies, and refuses to release a package whose prior state is unknown without an explicit decision. | coordinator: `theLedgerRecordsEachPackagesStateBeforeDoomStopTouchedIt`, `theLedgerRecordsChromesOriginalBlocklistNotDoomStopsOwn`, `aSuccessfulRestorePutsBackWhatWasThereBeforeAndOnlyThat`, `packagesWhosePriorStateIsUnknownAreNotReleasedWithoutAnExplicitDecision` |

Also corrected from the review's delivery notes: `completeSetup()` now validates its
prerequisites at the operation boundary and keeps the self-protection result, so a failed
anti-removal control is an unsatisfied readiness line rather than something hidden behind a
green "Protected"; and `ChromePolicyReport.satisfied` was renamed `storedPolicyVerified`,
with the UI saying plainly that it describes the value DevicePolicyManager holds and not
Chrome's acceptance of it.

### 7.1 Two further defects the regression tests exposed

**Rebooting was worth up to half a minute of free use.** Once accounting trailed the present
(F5), the window still open at shutdown had never been written, and the pass after a reboot
starts from "nothing on screen" — so it was silently discarded, repeatably and on demand.
Fixed by settling that tail from its own retained events before the boot gap is considered
at all. The same flush now runs before a refused clock reading and before a blind pass, for
the same reason. Covered by `anAppOpenAtShutdownIsChargedUpToTheRebootRatherThanForgiven`
and `acknowledgingRecoveryResumesAccessAccordingToTheRetainedBalance`.

**Carrying the live observer between passes replayed the tail on top of itself.** The first
attempt kept one observer across ticks; because the unsettled window is recomputed each
pass, its events were applied twice and the observer's logical clock refused to rewind, so a
late event landed on a state it had already been applied to. The observer is now rebuilt
from the settled snapshot on every pass. This was caught by
`aLateArrivingPauseCorrectsTheChargeInsteadOfBeingIgnored` failing with a charge of 0.

### 7.2 Live re-check after the fixes (emulator, real clock)

The fixes change how time is committed, so the whole path was re-run once by hand against
the provisioned emulator with no fake clock anywhere — real `UsageStatsManager` events, real
suspension, a one-minute allowance, and the build installed **in place over the version 1
database** so the migration ran for real.

| Moment | Notification |
|---|---|
| target opened | `41 s left today` · *Counting now* |
| 20 s later | `21 s left today` · *Counting now* |
| 25 s later | `Social apps paused for today` |
| Home pressed | *Not counting* |

Twenty seconds of use cost exactly twenty seconds, so the thirty-second commit lag does not
show up as a lag in what the user sees. At exhaustion the platform reported
`suspended=true` for all three targets and for the installed blocked browser, and launching
a target resumed `com.android.settings/.enterprise.ActionDisabledByAdminDialog` rather than
the app. The in-place update kept the existing database and the device-owner state.

### 7.3 What the review asked for that is still not done

- **Picture-in-picture on real hardware.** F6's rule is the conservative interim answer, not
  a measured one. None of the three target apps is believed to offer PiP on Android, but that
  has not been checked on the phone, and the ten-minute unresolved bound is a judgement call
  that should be revisited with a hardware measurement of `ACTIVITY_STOPPED` latency.
- **A signed release.** The build now refuses to produce an unsigned one, but the key itself
  has to be created by its owner and the in-place update path is still unverified.

---

## 8. Acceptance matrix

| Test | Required result | Evidence | Status |
|---|---|---|---|
| Open any target without opening limiter | counting starts automatically | emulator, real `UsageStatsManager` events | **pass** — 21 s charged for ~20 s |
| Instagram 20 s + Reddit 20 s + TikTok 20 s | shared allowance exhausted, all three suspended | emulator, real suspension read back | **pass** |
| Home / unrelated app / locked screen | no ongoing debit | emulator + coordinator | **pass** |
| Rapid switching and activity transitions | no duplicated or lost intervals | engine + coordinator | **pass** |
| Split-screen | visible target charged once | engine (multi-resume) + emulator | **pass** |
| Picture-in-picture | still-visible target keeps being charged | engine only | **partial** — metering no longer stops at 90 s and ends in an explicit unresolved state; **not exercised on hardware**, and whether the three apps offer PiP at all is unchecked |
| Expiry while app open | unusable; cut-off ≤ 2 s | emulator | **pass — 683 ms** |
| Notifications / recents / deep links after expiry | no access | emulator (launch only) | **partial** — launch blocked; specific routes not walked |
| Correct PIN | exactly one extension, sites still blocked | emulator + UI flow | **pass** |
| Wrong PIN, repeated, reboot | no grant, persistent throttling | engine | **pass** — rewind *and* forward jump covered |
| Admin session ends when the app is backgrounded | PIN needed again | source only | **not verified** — no UI-lifecycle test; the logic is unit-covered only where it is testable off-device |
| Midnight while app open | interval split, exactly one reset | engine + coordinator | **pass** |
| Manual date change forward | no new spendable day | coordinator + emulator | **pass** — jump refused, day unchanged, no new day row created |
| Manual date change backward | no repeatable reset exploit | coordinator + emulator | **pass** — refused; the day stays exhausted |
| Timezone change on the device | accounting day unaffected | source only | **not verified** — the accounting zone is captured at setup and never follows the device, but this has not been exercised |
| Reboot with exhausted/remaining allowance | balance retained, no fresh grant | emulator + coordinator | **pass** — including the window still open at shutdown |
| Process death while target open | reconcile and resume | coordinator (real database, rebuilt coordinator) | **pass** — this is now a restart test, not only an engine test |
| Usage permission revoked | unhealthy state, suspension, recovery latched | coordinator | **pass** — and the latch survives a restart |
| Force-stop / clear-data / uninstall controller | settings bypass prevented | emulator (uninstall) | **partial** — uninstall refused and user-control disabled for this package only; force-stop and clear-data not exercised by hand |
| Chrome normal / incognito / mobile / old / short URLs | blocked permanently | emulator, real Chrome | **pass** |
| Chrome policy after reboot / update / reinstall | remains effective or reapplied | — | **not verified** |
| Target tab open before initial policy | setup closes/restarts; no retroactive claim | — | **not verified**; documented in setup |
| Install known blocked browser | suspended promptly; race measured | emulator | **pass — 366 ms** |
| Install unrelated app | installs and runs without PIN | emulator | **pass** |
| Reinstall target after exhaustion | remains blocked, balance not reset | emulator | **pass** |
| Calls, SMS, maps, camera, banking, ordinary Chrome | normal | emulator (browsing only) | **partial** — telephony not testable on this emulator |
| Battery saver and overnight idle | recovers; no accidental reset; battery recorded | — | **not verified** |
| Signed in-place update | owner status, PIN, usage, policies preserved | — | **not verified** — needs the release key |
| Database upgrade | balance and outstanding recovery survive | coordinator (`MigrationTest`) | **pass** — an `UNCERTAIN` history stays `UNCERTAIN` across the upgrade |
| PIN-authorized restore, every step succeeding | previous values restored, only what this app changed | coordinator, injected policy | **pass** |
| PIN-authorized restore, a step failing | ownership kept, failure named, retry works | coordinator, injected policy | **pass** |
| Releasing device ownership | app becomes removable | coordinator, injected policy | **partial** — the staged path and its refusal-to-proceed are tested; the real `clearDeviceOwnerApp` has not been run |

---

## 9. Residual gaps, stated plainly

1. **The phone is not protected.** Everything above is emulator work. Until the Pixel is
   reset and provisioned, DoomStop enforces nothing on it.
2. **One API level of drift.** Verification ran on API 36; the phone is API 37.
3. **The 366 ms browser window is real.** A blocked browser can be launched in that window.
4. **Browser coverage is a fixed list.** Anything not on it, including WebView-based apps and
   in-app browsers, is not blocked. This is not Internet filtering.
5. **Chrome policy is not a firewall** and does not erase an already-loaded page.
6. **Six digits is not a strong secret.** The protection is the sandbox, disabled backup and
   online throttling — not the KDF.
7. **No consumer Android process is guaranteed uninterrupted.** `START_STICKY` is not a
   promise. Gaps are reconciled from usage events; an unreconstructable gap suspends the apps
   rather than forgiving the time, but a crash while apps are allowed does not suspend them
   by itself.
8. **Anyone with recovery or firmware access can wipe the device**, and that is the intended
   escape hatch if the PIN is lost.
9. **There is no signed release yet.** The build refuses to produce an unsigned one, but the
   key has to be created by its owner; the password must not pass through a build log or a
   transcript, and the in-place update path stays unverified until it exists.
10. **Picture-in-picture is unmeasured.** Section 7.3 states the interim rule and why it is
    conservative rather than correct.
11. **Accounting commits on a thirty-second lag.** Enforcement uses the live estimate, so
    cut-off is unaffected, but a usage event delivered more than thirty seconds late is
    dropped with a diagnostic rather than applied to an interval that has already been
    charged. Nothing observed so far arrives that late; the reader's own overlap is ten
    seconds.
12. **A clock correction larger than five seconds needs the PIN holder.** That is the price
    of refusing a clock jump outright. With automatic time on it should not happen; when it
    does, the acknowledgement screen names the reason and the range, and adopting the new
    clock is an authorized act because the anchor decides which day is current.
