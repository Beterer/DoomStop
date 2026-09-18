package dev.personal.doomstop.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import dev.personal.doomstop.config.BlockedBrowsers
import dev.personal.doomstop.config.BlockedSites
import dev.personal.doomstop.config.ShortsGuard
import dev.personal.doomstop.config.TargetPackages

/** What happened to one package when suspension was applied and then verified. */
data class PackageOutcome(
    val packageName: String,
    val installed: Boolean,
    /** Null when the package is not installed and nothing was requested. */
    val requestedSuspended: Boolean?,
    /** Suspension state read back AFTER the call, not what the call was asked to do. */
    val actualSuspended: Boolean?,
    val error: String? = null,
) {
    val isFailure: Boolean
        get() = installed && (error != null || (requestedSuspended != null && actualSuspended != requestedSuspended))
}

data class SuspensionReport(val outcomes: List<PackageOutcome>) {
    val failures: List<PackageOutcome> get() = outcomes.filter { it.isFailure }
    val installed: List<PackageOutcome> get() = outcomes.filter { it.installed }
    val allApplied: Boolean get() = failures.isEmpty()
}

/**
 * Result of writing and verifying Chrome's managed URL blocklist.
 *
 * [storedPolicyVerified] means exactly one thing: the value read back out of
 * DevicePolicyManager is the value this app intended to store. It is NOT evidence that
 * Chrome has parsed, accepted or applied it -- that question can only be answered by
 * navigating to a blocked host in the browser, which is a separate, manual check. The two
 * are named differently on purpose, because conflating them is how a policy that Chrome
 * silently ignored gets reported as protection.
 */
data class ChromePolicyReport(
    val chromeInstalled: Boolean,
    val appliedValue: String?,
    /** Read back from the managed restrictions afterwards, not assumed from the write. */
    val verifiedValue: String?,
    val storedPolicyVerified: Boolean,
    val error: String? = null,
)

/**
 * Result of the narrowly scoped self-protection policies.
 *
 * Both booleans are the STATE read back afterwards, not "the call did what it was told".
 * The difference matters at exactly one point and used to be wrong there: when protection
 * is being switched OFF, success means the controls are no longer in force, and a report
 * that meant "matched the request" made a cleared control look identical to a set one.
 */
data class SelfProtectionReport(
    val requested: Boolean,
    val uninstallBlocked: Boolean,
    val userControlDisabled: Boolean,
    val error: String? = null,
) {
    val matchesRequest: Boolean
        get() = error == null && uninstallBlocked == requested && userControlDisabled == requested
}

/**
 * Optional, narrowly scoped hardening. Every flag defaults to off and is applied only
 * after the PIN holder chooses it, because each one closes a bypass at some cost to
 * ordinary phone use, and the plan forbids blanket lockdown.
 *
 * `DISALLOW_INSTALL_APPS` is deliberately absent and must never be added: normal app
 * installation stays available without a PIN (requirement 8).
 */
data class HardeningOptions(
    val disallowAddUser: Boolean = false,
    val disallowSafeBoot: Boolean = false,
    val disallowManualDateTime: Boolean = false,
    /**
     * Never enabled automatically. Turning this on removes USB debugging, which the plan
     * forbids until the user approves the reviewed final configuration and a signed
     * update/recovery path is known to work.
     */
    val disallowDebuggingFeatures: Boolean = false,
) {
    val any: Boolean
        get() = disallowAddUser || disallowSafeBoot || disallowManualDateTime || disallowDebuggingFeatures

    companion object {
        fun fromRestrictions(active: Set<String>) = HardeningOptions(
            disallowAddUser = UserManager.DISALLOW_ADD_USER in active,
            disallowSafeBoot = UserManager.DISALLOW_SAFE_BOOT in active,
            disallowManualDateTime = UserManager.DISALLOW_CONFIG_DATE_TIME in active,
            disallowDebuggingFeatures = UserManager.DISALLOW_DEBUGGING_FEATURES in active,
        )
    }
}

/**
 * Every DevicePolicyManager call in the app. Nothing else touches policy, so the set of
 * things this app can do to the device is exactly the public surface of this class.
 *
 * Honest boundary (plan section 3): device-owner privileges are required for all of this.
 * Ordinary device-administrator permission is not enough, and without ownership every
 * method here reports failure rather than pretending to enforce anything.
 *
 * Reads are separated from writes throughout, so the caller can record a value in its
 * write-ahead ledger BEFORE changing it. Recording afterwards was a real defect: a crash in
 * between could persist this app's own value as the "original".
 */
class PolicyController(private val context: Context) : DevicePolicyGateway {

    private val dpm: DevicePolicyManager =
        context.getSystemService(DevicePolicyManager::class.java)

    private val admin = ComponentName(context, LimiterAdminReceiver::class.java)

    override val isDeviceOwner: Boolean
        get() = runCatching { dpm.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)

    val isAdminActive: Boolean
        get() = runCatching { dpm.isAdminActive(admin) }.getOrDefault(false)

    // -- package suspension --------------------------------------------------------------

    /**
     * Apply the whole enforcement picture in one pass: targets other than Instagram follow
     * [suspendTargets], and the hardcoded browsers are ALWAYS suspended regardless of
     * allowance, because they exist to route around the permanent Chrome policy rather than to
     * consume time. YouTube follows [suspendYouTube], true only while the Shorts guard is off.
     * Instagram follows [suspendInstagram] so it can be left in DM-only messaging mode while
     * the guard is watching it and hard-suspended when the guard is not.
     */
    override fun applyEnforcement(
        suspendTargets: Boolean,
        suspendYouTube: Boolean,
        suspendInstagram: Boolean,
    ): SuspensionReport {
        val outcomes = buildList {
            addAll(setSuspended(TargetPackages.ALL - TargetPackages.INSTAGRAM, suspendTargets))
            addAll(setSuspended(setOf(TargetPackages.INSTAGRAM), suspendInstagram))
            addAll(setSuspended(BlockedBrowsers.ALL, true))
            addAll(setSuspended(setOf(ShortsGuard.YOUTUBE_PACKAGE), suspendYouTube))
        }
        return SuspensionReport(outcomes)
    }

    /**
     * Unsuspend exactly the packages named. Recovery passes the ones its ledger says this
     * app suspended, so a package that was already suspended by someone else is left alone.
     */
    override fun release(packages: Set<String>): SuspensionReport =
        SuspensionReport(setSuspended(packages, false))

    /** Every package this app is capable of suspending, for reporting and for tests. */
    override fun manageablePackages(): Set<String> =
        TargetPackages.ALL + BlockedBrowsers.ALL + ShortsGuard.YOUTUBE_PACKAGE

    /** Suspension state as the platform reports it. Null when it will not say. */
    override fun readSuspended(packageName: String): Boolean? = if (!isInstalled(packageName)) {
        null
    } else {
        try {
            dpm.isPackageSuspended(admin, packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot read suspension state for $packageName", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "cannot read suspension state for $packageName", e)
            null
        }
    }

    /**
     * Suspend or unsuspend a set of packages and then VERIFY each one, because
     * setPackagesSuspended reporting no failure is not the same as the state having
     * changed.
     */
    private fun setSuspended(packages: Set<String>, suspended: Boolean): List<PackageOutcome> {
        require(packages.none { it in BlockedBrowsers.NEVER_SUSPEND }) {
            "Refusing to suspend a protected package (Chrome stable or a WebView provider)"
        }

        val installed = packages.filter { isInstalled(it) }
        val absent = packages.filterNot { it in installed }.map {
            PackageOutcome(it, installed = false, requestedSuspended = null, actualSuspended = null)
        }
        if (installed.isEmpty()) return absent

        if (!isDeviceOwner) {
            return absent + installed.map {
                PackageOutcome(it, true, suspended, null, "not device owner")
            }
        }

        val rejected: Set<String> = try {
            dpm.setPackagesSuspended(admin, installed.toTypedArray(), suspended)?.toSet().orEmpty()
        } catch (e: SecurityException) {
            Log.e(TAG, "setPackagesSuspended refused", e)
            return absent + installed.map { PackageOutcome(it, true, suspended, null, e.message ?: "SecurityException") }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "setPackagesSuspended rejected the request", e)
            return absent + installed.map { PackageOutcome(it, true, suspended, null, e.message ?: "IllegalArgumentException") }
        }

        return absent + installed.map { pkg ->
            PackageOutcome(
                packageName = pkg,
                installed = true,
                requestedSuspended = suspended,
                actualSuspended = readSuspended(pkg),
                error = if (pkg in rejected) "platform refused to change suspension" else null,
            )
        }
    }

    override fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    override fun installedTargets(): List<String> = TargetPackages.ALL.filter { isInstalled(it) }

    override fun installedBlockedBrowsers(): List<String> = BlockedBrowsers.ALL.filter { isInstalled(it) }

    // -- Chrome URL policy ---------------------------------------------------------------

    /**
     * Write the permanent URL blocklist into Chrome's managed configuration and read it
     * back.
     *
     * Unrelated restrictions are preserved: the existing Bundle is fetched, only
     * URLBlocklist is replaced, and everything else is written back untouched.
     *
     * Chrome's own restriction schema declares URLBlocklist as TYPE_STRING, so the value
     * is a JSON array encoded in a String. Writing a String[] here would be silently
     * ignored by Chrome, which is exactly the failure mode the plan warns about -- hence
     * the read-back and [ChromePolicyReport.storedPolicyVerified] rather than a bare
     * "applied" boolean. That flag still says nothing about Chrome's own behaviour; see
     * the type's documentation.
     */
    override fun applyChromeBlocklist(): ChromePolicyReport {
        if (!isInstalled(BlockedSites.CHROME_PACKAGE)) {
            return ChromePolicyReport(false, null, null, storedPolicyVerified = false, error = "Chrome is not installed")
        }
        if (!isDeviceOwner) {
            return ChromePolicyReport(true, null, null, storedPolicyVerified = false, error = "not device owner")
        }

        return try {
            val existing: Bundle = dpm.getApplicationRestrictions(admin, BlockedSites.CHROME_PACKAGE) ?: Bundle()
            val desired = BlockedSites.blocklistJson()

            val updated = Bundle(existing)
            updated.putString(BlockedSites.KEY_URL_BLOCKLIST, desired)
            dpm.setApplicationRestrictions(admin, BlockedSites.CHROME_PACKAGE, updated)

            val verified = dpm.getApplicationRestrictions(admin, BlockedSites.CHROME_PACKAGE)
                ?.getString(BlockedSites.KEY_URL_BLOCKLIST)

            ChromePolicyReport(
                chromeInstalled = true,
                appliedValue = desired,
                verifiedValue = verified,
                storedPolicyVerified = BlockedSites.isSatisfiedBy(verified),
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Chrome restrictions refused", e)
            ChromePolicyReport(true, null, null, storedPolicyVerified = false, error = e.message ?: "SecurityException")
        }
    }

    /**
     * Current managed value, for the write-ahead ledger and for verifying the policy
     * survived a reboot or an update.
     *
     * The `Result` distinguishes "read successfully, and there was no value" from "could
     * not read", which the ledger must not conflate: only the first justifies removing the
     * key during a restore.
     */
    override fun readChromeBlocklist(): Result<String?> = runCatching {
        check(isDeviceOwner) { "not device owner" }
        dpm.getApplicationRestrictions(admin, BlockedSites.CHROME_PACKAGE)?.getString(BlockedSites.KEY_URL_BLOCKLIST)
    }

    /**
     * Restore Chrome's blocklist to what it was before this app touched it, leaving every
     * other restriction alone. A null [previousValue] means the key was absent, so it is
     * removed rather than set to an empty list.
     */
    override fun restoreChromeBlocklist(previousValue: String?): Result<Unit> = runCatching {
        check(isDeviceOwner) { "not device owner" }
        val existing = dpm.getApplicationRestrictions(admin, BlockedSites.CHROME_PACKAGE) ?: Bundle()
        val updated = Bundle(existing)
        if (previousValue == null) updated.remove(BlockedSites.KEY_URL_BLOCKLIST)
        else updated.putString(BlockedSites.KEY_URL_BLOCKLIST, previousValue)
        dpm.setApplicationRestrictions(admin, BlockedSites.CHROME_PACKAGE, updated)

        val verified = dpm.getApplicationRestrictions(admin, BlockedSites.CHROME_PACKAGE)
            ?.getString(BlockedSites.KEY_URL_BLOCKLIST)
        check(verified == previousValue) { "Chrome policy read back as something other than the recorded original" }
    }

    // -- protecting the controller itself ------------------------------------------------

    override fun readUninstallBlocked(): Boolean? = runCatching {
        dpm.isUninstallBlocked(admin, context.packageName)
    }.getOrNull()

    override fun readUserControlDisabledPackages(): List<String>? = runCatching {
        dpm.getUserControlDisabledPackages(admin)
    }.getOrNull()

    /**
     * Narrowly scoped protection for THIS package only: block its uninstall, and where
     * supported stop force-stop and clear-data from the task manager.
     *
     * Scope matters -- [DevicePolicyManager.setUserControlDisabledPackages] replaces the
     * whole list, so [otherPackages] carries back whatever was on it before this app first
     * wrote to it. Every other app's controls keep working, and disabling protection puts
     * that list back rather than emptying it.
     */
    override fun protectSelf(enabled: Boolean, otherPackages: List<String>): SelfProtectionReport {
        if (!isDeviceOwner) return SelfProtectionReport(enabled, false, false, "not device owner")
        var uninstallBlocked = false
        var userControlDisabled = false
        var error: String? = null
        try {
            dpm.setUninstallBlocked(admin, context.packageName, enabled)
            uninstallBlocked = dpm.isUninstallBlocked(admin, context.packageName)

            val others = otherPackages.filter { it != context.packageName }
            val wanted = if (enabled) others + context.packageName else others
            dpm.setUserControlDisabledPackages(admin, wanted)
            userControlDisabled = dpm.getUserControlDisabledPackages(admin).contains(context.packageName)
        } catch (e: SecurityException) {
            error = e.message ?: "SecurityException"
            Log.e(TAG, "self-protection refused", e)
        } catch (e: UnsupportedOperationException) {
            error = e.message ?: "unsupported on this platform version"
            Log.w(TAG, "self-protection unsupported", e)
        }
        return SelfProtectionReport(enabled, uninstallBlocked, userControlDisabled, error)
    }

    // -- optional hardening ---------------------------------------------------------------

    /**
     * Apply exactly the restrictions requested and clear the ones that are not. Returns the
     * restrictions actually in force afterwards, read back from the platform.
     *
     * Automatic system time is only forced on when manual date and time are being blocked,
     * which is the one case where the two must agree. Turning it on unconditionally -- as
     * an earlier version did, including from the restore path -- was a change to the
     * device's settings that this app had never been asked to make.
     */
    override fun applyHardening(options: HardeningOptions): Set<String> {
        if (!isDeviceOwner) return emptySet()
        val wanted = buildSet {
            if (options.disallowAddUser) add(UserManager.DISALLOW_ADD_USER)
            if (options.disallowSafeBoot) add(UserManager.DISALLOW_SAFE_BOOT)
            if (options.disallowManualDateTime) add(UserManager.DISALLOW_CONFIG_DATE_TIME)
            if (options.disallowDebuggingFeatures) add(UserManager.DISALLOW_DEBUGGING_FEATURES)
        }
        for (restriction in MANAGED_RESTRICTIONS) {
            runCatching {
                if (restriction in wanted) dpm.addUserRestriction(admin, restriction)
                else dpm.clearUserRestriction(admin, restriction)
            }.onFailure { Log.w(TAG, "user restriction $restriction not applied", it) }
        }
        if (options.disallowManualDateTime) setAutoTime(true)
        return activeRestrictions()
    }

    /** Put the user restrictions and the automatic-time setting back as the ledger recorded them. */
    override fun restoreHardening(previousRestrictions: Set<String>?, previousAutoTime: Boolean?): Result<Unit> = runCatching {
        check(isDeviceOwner) { "not device owner" }
        val wanted = previousRestrictions ?: emptySet()
        for (restriction in MANAGED_RESTRICTIONS) {
            if (restriction in wanted) dpm.addUserRestriction(admin, restriction)
            else dpm.clearUserRestriction(admin, restriction)
        }
        if (previousAutoTime != null && readAutoTime() != previousAutoTime) setAutoTime(previousAutoTime)
        val remaining = activeRestrictions()
        check(remaining == wanted) { "user restrictions read back as $remaining rather than $wanted" }
    }

    override fun activeRestrictions(): Set<String> = runCatching {
        val bundle = dpm.getUserRestrictions(admin)
        MANAGED_RESTRICTIONS.filterTo(mutableSetOf()) { bundle.getBoolean(it, false) }
    }.getOrDefault(emptySet())

    override fun readAutoTime(): Boolean? = runCatching { dpm.getAutoTimeEnabled(admin) }.getOrNull()

    private fun setAutoTime(enabled: Boolean) {
        runCatching { dpm.setAutoTimeEnabled(admin, enabled) }
            .onFailure { Log.w(TAG, "setAutoTimeEnabled($enabled) not applied", it) }
    }

    // -- removal ---------------------------------------------------------------------------

    /**
     * Give up device ownership. Only reachable behind the PIN, an explicit confirmation,
     * and a restore in which every step verified.
     *
     * The caller is responsible for having already unsuspended packages and restored
     * Chrome's restrictions -- once ownership is gone none of that can be undone from here,
     * which is exactly why a partial failure must stop short of calling this.
     */
    @Suppress("DEPRECATION")
    override fun relinquishDeviceOwnership(): Result<Unit> = runCatching {
        check(isDeviceOwner) { "not device owner" }
        dpm.clearDeviceOwnerApp(context.packageName)
    }

    private companion object {
        const val TAG = "DoomStopPolicy"

        /** The complete set of user restrictions this app is ever allowed to touch. */
        val MANAGED_RESTRICTIONS = listOf(
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
        )
    }
}
