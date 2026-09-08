# DoomStop — setup and recovery

Two people are involved. The **trusted person** chooses and keeps the PIN. The **phone
user** is whoever the limit applies to. If those are the same person, the PIN protects
nothing, and the honest thing is to say so rather than pretend otherwise.

---

## 1. Before you start: the reset question

Device-owner provisioning is not optional. Without it, package suspension and Chrome
policy are both unavailable, and DoomStop will report itself unprotected rather than
pretend to enforce anything.

Android only allows a device owner to be set on a device that has **no accounts** and, once
setup has completed, only one user. On a phone in normal use this means **a factory reset**.

The target Pixel 9 currently reports:

```
device_provisioned  = 1
user_setup_complete = 1
accounts on device  = 20
```

so `dpm set-device-owner` will be refused with `STATUS_ACCOUNTS_NOT_EMPTY` until the phone
is wiped. Nothing in this project will wipe it for you.

**Before wiping:**

1. Back up photos, messages, authenticator seeds and anything else not in the cloud.
   Two-factor authenticator apps are the usual casualty — export them first.
2. Note which apps hold local-only data.
3. Decide the PIN. Six digits, and the phone user must not see it.

## 2. Provisioning

Do this immediately after the reset, **before** signing into any account. The setup wizard
will offer to add a Google account: skip it. You can add accounts afterwards.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

# 1. Confirm the phone is visible and has no accounts yet.
& $adb devices -l
& $adb shell pm list users
& $adb shell dumpsys account | Select-String 'Account \{' | Measure-Object

# 2. Install the release build.
& $adb install -r app\build\outputs\apk\release\app-release.apk

# 3. Make it the device owner.
& $adb shell dpm set-device-owner dev.personal.doomstop/.admin.LimiterAdminReceiver
```

Expected output:

```
Success: Device owner set to package dev.personal.doomstop/.admin.LimiterAdminReceiver
Active admin set to component dev.personal.doomstop/.admin.LimiterAdminReceiver
```

If it fails, the message names the reason. `STATUS_ACCOUNTS_NOT_EMPTY` means an account was
added during setup; `STATUS_USER_SETUP_COMPLETED` combined with more than one user means a
second user or profile exists. Neither is worked around by this app.

Keep USB debugging **on** throughout setup and recovery testing. Turning it off is the last
step, and only after you have confirmed a signed update installs in place.

## 3. Finishing setup on the phone

Open DoomStop. It shows a readiness checklist and will not display "Protected" until every
line passes.

Five lines are **prerequisites**: the button that starts enforcement stays disabled until
they pass, and they are checked again when it is pressed rather than only when the screen
was drawn.

| Line | Prerequisite | How to satisfy it |
|---|---|---|
| Device owner | yes | Provisioned in step 2. |
| Usage access | yes | Tap **Grant Usage access**. Device ownership does not grant this; it must be switched on by hand under Settings → Apps → Special app access → Usage access. |
| Monitor running | yes | Tap **Start the monitor**. |
| PIN set | yes | **The trusted person** taps **Set the six-digit PIN** and enters it twice, out of sight of the phone user. The keypad is drawn inside the app, so the PIN never reaches keyboard prediction or autofill. |
| Accounting history complete | yes | Satisfied on a fresh install. |
| Notification shown | no | Allow notifications when prompted. The notification is silent and permanent, and is a reliable way back into the app when a target is paused. |

The remaining lines are **outcomes** of switching enforcement on, so they cannot be green
beforehand. After pressing the button, check that they are:

| Line | What it means |
|---|---|
| Chrome policy stored and read back | The blocklist DevicePolicyManager holds is the one this app intended to store. **It does not mean Chrome accepted it.** Close any open Instagram / TikTok / Reddit tabs, restart Chrome, then confirm by opening one of the sites and at `chrome://policy`. |
| Target suspension applied | Every installed target and blocked browser reads back in the state that was asked for. |
| This app cannot be uninstalled | Uninstall blocking is in force. Without it the limiter can simply be removed, so a red line here means you are not protected regardless of what else is green. |
| Task-manager controls disabled for this app | Force-stop and clear-data are blocked. Not supported on every platform build; reported separately for that reason. |

Then press **Finish setup and start enforcing**. Nothing is enforced before this point,
though metering already runs, so the first enforced day starts from a monitor known to work.
The app reports "Protected" only when every line above is green.

Optionally verify Chrome independently: open `chrome://policy` and look for `URLBlocklist`.

### Defaults

30 minutes a day and 10 minutes per extension are **proposed defaults, not decisions you
have already confirmed**. The reset is at midnight in the timezone captured during setup.
All three are editable behind the PIN. Unused time does not roll over, and extra time
expires at the next reset.

## 4. Day-to-day

- Opening Instagram, TikTok or Reddit starts counting. There is no Start button.
- Switching away, locking the phone or turning the screen off stops counting.
- Two of them visible at once still costs one second per second.
- An app that pauses without stopping — the picture-in-picture case — keeps costing time for
  up to ten minutes. Past that the app cannot tell whether it is still on screen, so it stops
  guessing: the targets are paused and the PIN holder is asked (see section 9). Turning the
  screen off resolves it without any of that.
- When the shared allowance runs out, all three are paused. Android shows **"Blocked by
  work policy"**.
- **Request more time** asks for the PIN and adds exactly one extension. A fresh PIN entry
  is needed for each one, and repeated taps cannot duplicate a grant.
- The three websites stay blocked in Chrome at all times, including while time remains and
  including in incognito.
- Installing apps needs no PIN. Calls, SMS, maps, camera, banking and ordinary browsing are
  untouched.

## 5. If the PIN is lost

There is no recovery password anywhere in this design, deliberately. A universal override
would be a bypass by another name.

What is left, in order of preference:

1. **Settings → Maintenance**, if you can still get in — but that needs the PIN.
2. `adb uninstall` — refused while DoomStop is the device owner
   (`DELETE_FAILED_DEVICE_POLICY_MANAGER`).
3. **Factory reset.** This works because recovery is below the OS, and it is also the reason
   this app never claims to stop someone determined to wipe the phone.

Wrong attempts are throttled: five free, then a 30-second lock that doubles up to 30
minutes. The counter and the deadline are stored on disk, so rebooting does not clear them,
and the deadline is recorded against **both** clocks — moving the date backwards *or*
forwards does not shorten a lock. Wrong attempts never affect ordinary phone use.

The trusted person's session is short by design. It ends after two minutes of inactivity,
when you leave the settings screen, and as soon as DoomStop stops being the app on screen —
pressing Home, switching apps or locking the phone all end it. Handing the phone back
therefore does not hand back an open settings screen. Rotating the phone does not sign you
out.

## 6. Removing DoomStop cleanly

Settings → PIN → **Maintenance and removal**. Two options, both confirmed explicitly:

- **Restore access, keep device ownership** — unsuspends the apps and browsers this app
  suspended, puts Chrome's blocklist back to its recorded previous value, restores the user
  restrictions and the automatic-time setting to what they were, and drops its own uninstall
  protection. DoomStop stays the device owner, so protection can be switched back on without
  another reset.
- **Restore access and release device ownership** — as above, then gives up ownership. After
  this, the app can be uninstalled normally. **Regaining device ownership later requires
  another factory reset**, because provisioning needs a device that has not finished setup.

Only what DoomStop changed is restored. Unrelated Chrome restrictions, other apps' entries in
the task-manager control list, and packages it never suspended are all left alone; the
previous values come from a ledger written **before** the app first changed each setting, not
from guesswork.

### If a step fails

Restore runs as a sequence of verified steps, and stops at the first one that does not
verify. When that happens:

- **Device ownership is kept**, and is not even attempted. Releasing it is the one action
  that cannot be retried, so it only happens after every earlier step has succeeded.
- The device is left in **maintenance mode**: DoomStop deliberately stops asserting any
  policy, so the monitor cannot fight the restore. **The phone is unprotected while this
  lasts.** The status screen says so in plain words.
- The settings screen names the step that failed. Fix the cause and press the same button
  again, or press **Cancel maintenance and resume enforcing** to put things back as they were.

If a package's suspension state before DoomStop existed could not be read, restore says so
and leaves that package alone rather than guessing. A checkbox appears offering to release
those as well; that is your decision to make, not the app's.

## 7. Updating

Build a release signed with the **same key** and `adb install -r`. Owner status, PIN,
balance and applied policies all survive, because they live in the app's database and in
platform policy rather than in the process.

Losing the signing key means no future version can be installed over the provisioned app,
and recovering from that needs a factory reset. Back the key up somewhere outside this
repository.

### Creating the release key

Run this yourself — the password must not pass through a transcript or a build log:

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v `
  -keystore doomstop-release.jks -alias doomstop `
  -keyalg RSA -keysize 4096 -validity 10000
```

Then create `keystore.properties` in the repository root (already gitignored):

```properties
storeFile=../doomstop-release.jks
storePassword=...
keyAlias=doomstop
keyPassword=...
```

and build:

```powershell
.\gradlew.bat :app:assembleRelease
```

Without `keystore.properties` this fails on purpose, naming the missing file: an unsigned
release APK cannot be installed over the provisioned app, so producing one quietly would be
worse than not producing one. `-PallowUnsignedRelease=true` exists for inspecting an
unsigned artifact during development and must not be used for the phone's build.

## 8. Optional extra restrictions

Under Settings, all off by default. Each closes a specific bypass at a cost:

| Restriction | Closes | Costs |
|---|---|---|
| Block adding users and profiles | A second user with unsuspended apps | No guest mode or work profile |
| Block safe boot | Booting without the controller running | No safe boot |
| Block manual date and time changes | Clock games *across a reboot* — within one boot they are already refused, because accounting advances by measured elapsed time rather than by the phone's clock | Time must stay automatic |
| Block USB debugging | `adb` removal of policy | **Do not enable until you have confirmed a signed update installs in place.** Losing debugging before that is the one mistake with no easy way back. |

App installation is never restricted, and no blanket lockdown of settings, the Play Store,
VPNs, camera, calls or notifications is applied.

## 9. When something looks wrong

Setup & diagnostics names the failing capability and what to do about it. Three states are
worth understanding:

- **"Monitoring unavailable — apps paused."** Usage access was revoked or the monitor is not
  running. The apps are paused deliberately: an allowance nobody is counting is not an
  allowance. Re-grant usage access and start the monitor.
- **"Maintenance — not enforcing."** A restore was started and did not finish. Nothing is
  being enforced. Retry it or cancel it from Settings (section 6).
- **"Needs attention — apps paused."** A stretch of time could not be reconstructed, so the
  app refuses to guess. This is **latched**: it does not clear itself when the problem goes
  away, because a later successful reading says nothing about the interval that was missed.
  Settings → **Acknowledge and resume** is the only thing that clears it.

The acknowledgement screen names what happened and over what period, so you are not being
asked to approve an anonymous error. The usual causes are:

| What it says | What happened |
|---|---|
| the usage-event source was unreadable for a stretch of time | Usage access was revoked, or the query failed for longer than one poll. |
| the system clock jumped forward / backwards relative to elapsed time | The date was changed, or the clock was corrected by more than five seconds. |
| a target stayed paused without stopping for longer than the observer can interpret | See section 4: usually picture-in-picture, or a lost `STOPPED` event. |
| a target was on screen when the monitor stopped and its state could not be recovered | The saved observer state was unreadable, which should only happen after an upgrade from a much older build. |
| the clock advanced further across a reboot than a power-off explains | The phone was off for more than a day, or the date was changed while it was off. |

Acknowledging re-anchors accounting from now. Time already charged today is kept; no missing
time is invented and no fresh allowance is granted. One caveat worth knowing: acknowledging
also adopts the phone's **current** clock, and the anchor is what decides which accounting
day is current. **If the clock is wrong, correct it before acknowledging.**
