# DoomStop — test report

Prepared 2026-09-08. This records what was actually run, on what, and what has not been
verified. Where a result is a measurement it is the measured number, not a target.

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
| Unit (`testDebugUnitTest`), no device | 61 | all pass |
| Instrumented (`connectedDebugAndroidTest`), emulator as device owner | 28 | all pass |
| Android lint (`lintDebug`, `lintRelease`) | — | clean (2 informational notices about deliberately pinned versions) |
| Release build | — | `app-release-unsigned.apk`, 23.7 MB, not debuggable, not test-only, `allowBackup=false` |

Unit tests cover budget arithmetic, midnight splitting, a DST day that skips local midnight,
rollover idempotence, boot/clock/monotonic anomalies, recovery states, tracker visibility
semantics, and PIN throttling. Instrumented tests cover the database guarantees and the
gates below.

**Two defects were found by tests rather than by inspection**, both described in section 6.

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

## 7. Acceptance matrix

| Test | Required result | Status |
|---|---|---|
| Open any target without opening limiter | counting starts automatically | **pass** (emulator, real events) |
| Instagram 20 s + Reddit 20 s + TikTok 20 s | shared allowance exhausted, all three suspended | **pass** (integration test, real suspension) |
| Home / unrelated app / locked screen | no ongoing debit | **pass** |
| Rapid switching and activity transitions | no duplicated or lost intervals | **pass** (unit + integration) |
| Split-screen and PiP | visible target charged once | **partial** — multi-resume split-screen covered by test; not exercised on hardware |
| Expiry while app open | unusable; cut-off ≤ 2 s | **pass — 683 ms** |
| Notifications / recents / deep links after expiry | no access | **partial** — launch blocked; specific routes not walked |
| Correct PIN | exactly one extension, sites still blocked | **pass** (UI flow end-to-end + integration test) |
| Wrong PIN, repeated, reboot | no grant, persistent throttling | **pass** (unit tests; clock-rewind covered) |
| Midnight while app open | interval split, exactly one reset | **pass** (unit + integration) |
| Timezone / manual time change | no repeatable reset exploit | **pass** (integration: forward then back grants nothing) |
| Reboot with exhausted/remaining allowance | balance retained, no fresh grant | **pass** (integration) |
| Process death while target open | reconcile and resume | **pass** (unit); crash gap not measured on hardware |
| Usage permission revoked | unhealthy state and suspension | **pass** |
| Force-stop / clear-data / uninstall controller | settings bypass prevented | **partial** — uninstall refused, user-control disabled; force-stop and clear-data not exercised |
| Chrome normal / incognito / mobile / old / short URLs | blocked permanently | **pass** |
| Chrome policy after reboot / update / reinstall | remains effective or reapplied | **not verified** |
| Target tab open before initial policy | setup closes/restarts; no retroactive claim | **not verified**; documented in setup |
| Install known blocked browser | suspended promptly; race measured | **pass — 366 ms** |
| Install unrelated app | installs and runs without PIN | **pass** (stub APKs installed freely throughout) |
| Reinstall target after exhaustion | remains blocked, balance not reset | **pass** (idempotent re-apply; balance is day-keyed) |
| Calls, SMS, maps, camera, banking, ordinary Chrome | normal | **partial** — ordinary browsing verified; telephony not testable on this emulator |
| Battery saver and overnight idle | recovers; no accidental reset; battery recorded | **not verified** |
| Signed in-place update | owner status, PIN, usage, policies preserved | **not verified** — needs the release key |
| PIN-authorized maintenance / recovery | restores access without corrupting state | **partial** — restore path unit/instrumented; ownership relinquish not exercised |

---

## 8. Residual gaps, stated plainly

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
9. **The release APK is unsigned.** The signing key has to be created by its owner; the
   password must not pass through a build log or a transcript.
