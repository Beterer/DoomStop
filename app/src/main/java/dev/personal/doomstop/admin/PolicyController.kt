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

/** Result of writing and verifying Chrome's managed URL blocklist. */
data class ChromePolicyReport(
    val chromeInstalled: Boolean,
    /** Value of URLBlocklist before this app touched it; null when unset. Ledger input. */
    val previousValue: String?,
    val appliedValue: String?,
    /** Read back from Chrome's restrictions afterwards, not assumed from the write. */
    val verifiedValue: String?,
    val satisfied: Boolean,
    val error: String? = null,
)

/** Result of the narrowly scoped self-protection policies. */
data class SelfProtectionReport(
    val uninstallBlocked: Boolean,
    val userControlDisabled: Boolean,
    val error: String? = null,
)

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
)

/**
 * Every DevicePolicyManager call in the app. Nothing else touches policy, so the set of
 * things this app can do to the device is exactly the public surface of this class.
 *
 * Honest boundary (plan section 3): device-owner privileges are required for all of this.
 * Ordinary device-administrator permission is not enough, and without ownership every
 * method here reports failure rather than pretending to enforce anything.
 */
class PolicyController(private val context: Context) {

    private val dpm: DevicePolicyManager =
        context.getSystemService(DevicePolicyManager::class.java)

    private val admin = ComponentName(context, LimiterAdminReceiver::class.java)

    val isDeviceOwner: Boolean
        get() = runCatching { dpm.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)

    val isAdminActive: Boolean
        get() = runCatching { dpm.isAdminActive(admin) }.getOrDefault(false)

    // -- package suspension --------------------------------------------------------------

    /**
     * Apply the whole enforcement picture in one pass: targets follow [suspendTargets],
     * and the hardcoded browsers are ALWAYS suspended regardless of allowance, because
     * they exist to route around the permanent Chrome policy rather than to consume time.
     */
    fun applyEnforcement(suspendTargets: Boolean): SuspensionReport {
        val outcomes = buildList {
            addAll(setSuspended(TargetPackages.ALL, suspendTargets))
            addAll(setSuspended(BlockedBrowsers.ALL, true))
        }
        return SuspensionReport(outcomes)
    }

    /** Release everything this app suspended. Used only by PIN-authorized recovery. */
    fun releaseAll(): SuspensionReport = SuspensionReport(
        setSuspended(TargetPackages.ALL, false) + setSuspended(BlockedBrowsers.ALL, false)
    )

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

    private fun readSuspended(packageName: String): Boolean? = try {
        dpm.isPackageSuspended(admin, packageName)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    } catch (e: SecurityException) {
        Log.w(TAG, "cannot read suspension state for $packageName", e)
        null
    }

    fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun installedTargets(): List<String> = TargetPackages.ALL.filter { isInstalled(it) }

    fun installedBlockedBrowsers(): List<String> = BlockedBrowsers.ALL.filter { isInstalled(it) }

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
     * the read-back and the [ChromePolicyReport.satisfied] flag rather than a bare
     * "applied" boolean.
     */
    fun applyChromeBlocklist(): ChromePolicyReport {
        if (!isInstalled(BlockedSites.CHROME_PACKAGE)) {
            return ChromePolicyReport(false, null, null, null, satisfied = false, error = "Chrome is not installed")
        }
        if (!isDeviceOwner) {
            return ChromePolicyReport(true, null, null, null, satisfied = false, error = "not device owner")
        }

        return try {
            val existing: Bundle = dpm.getApplicationRestrictions(admin, BlockedSites.CHROME_PACKAGE) ?: Bundle()
            val previous = existing.getString(BlockedSites.KEY_URL_BLOCKLIST)
            val desired = BlockedSites.blocklistJson()

            val updated = Bundle(existing)
            updated.putString(BlockedSites.KEY_URL_BLOCKLIST, desired)
            dpm.setApplicationRestrictions(admin, BlockedSites.CHROME_PACKAGE, updated)

            val verified = dpm.getApplicationRestrictions(admin, BlockedSites.CHROME_PACKAGE)
                ?.getString(BlockedSites.KEY_URL_BLOCKLIST)

            ChromePolicyReport(
                chromeInstalled = true,
                previousValue = previous,
                appliedValue = desired,
                verifiedValue = verified,
                satisfied = BlockedSites.isSatisfiedBy(verified),
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Chrome restrictions refused", e)
            ChromePolicyReport(true, null, null, null, satisfied = false, error = e.message ?: "SecurityException")
        }
    }

    /** Current managed value, for diagnostics and for verifying it survived a reboot or update. */
    fun readChromeBlocklist(): String? = runCatching {
        dpm.getApplicationRestrictions(admin, BlockedSites.CHROME_PACKAGE)?.getString(BlockedSites.KEY_URL_BLOCKLIST)
    }.getOrNull()

    /**
     * Restore Chrome's blocklist to what it was before this app touched it, leaving every
     * other restriction alone. A null [previousValue] means the key was absent, so it is
     * removed rather than set to an empty list.
     */
    fun restoreChromeBlocklist(previousValue: String?): Boolean = runCatching {
        val existing = dpm.getApplicationRestrictions(admin, BlockedSites.CHROME_PACKAGE) ?: Bundle()
        val updated = Bundle(existing)
        if (previousValue == null) updated.remove(BlockedSites.KEY_URL_BLOCKLIST)
        else updated.putString(BlockedSites.KEY_URL_BLOCKLIST, previousValue)
        dpm.setApplicationRestrictions(admin, BlockedSites.CHROME_PACKAGE, updated)
        true
    }.getOrDefault(false)

    // -- protecting the controller itself ------------------------------------------------

    /**
     * Narrowly scoped protection for THIS package only: block its uninstall, and where
     * supported stop force-stop and clear-data from the task manager.
     *
     * Scope matters -- [DevicePolicyManager.setUserControlDisabledPackages] replaces the
     * whole list, so passing only this package is what keeps every other app's controls
     * working normally.
     */
    fun protectSelf(enabled: Boolean = true): SelfProtectionReport {
        if (!isDeviceOwner) return SelfProtectionReport(false, false, "not device owner")
        var uninstallBlocked = false
        var userControlDisabled = false
        var error: String? = null
        try {
            dpm.setUninstallBlocked(admin, context.packageName, enabled)
            uninstallBlocked = dpm.isUninstallBlocked(admin, context.packageName) == enabled

            dpm.setUserControlDisabledPackages(
                admin,
                if (enabled) listOf(context.packageName) else emptyList(),
            )
            userControlDisabled =
                dpm.getUserControlDisabledPackages(admin).contains(context.packageName) == enabled
        } catch (e: SecurityException) {
            error = e.message ?: "SecurityException"
            Log.e(TAG, "self-protection refused", e)
        } catch (e: UnsupportedOperationException) {
            error = e.message ?: "unsupported on this platform version"
            Log.w(TAG, "self-protection unsupported", e)
        }
        return SelfProtectionReport(uninstallBlocked, userControlDisabled, error)
    }

    // -- optional hardening ---------------------------------------------------------------

    /**
     * Apply exactly the restrictions requested and clear the ones that are not. Returns the
     * restrictions actually in force afterwards, read back from the platform.
     */
    fun applyHardening(options: HardeningOptions): Set<String> {
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
        // A fixed accounting timezone plus automatic system time is what keeps day
        // boundaries honest without freezing the clock or fighting travel.
        runCatching { dpm.setAutoTimeEnabled(admin, true) }
            .onFailure { Log.w(TAG, "setAutoTimeEnabled not applied", it) }
        return activeRestrictions()
    }

    fun activeRestrictions(): Set<String> = runCatching {
        val bundle = dpm.getUserRestrictions(admin)
        MANAGED_RESTRICTIONS.filterTo(mutableSetOf()) { bundle.getBoolean(it, false) }
    }.getOrDefault(emptySet())

    // -- removal ---------------------------------------------------------------------------

    /**
     * Give up device ownership. Only reachable behind the PIN and an explicit confirmation.
     *
     * The caller is responsible for having already unsuspended packages and restored
     * Chrome's restrictions -- once ownership is gone none of that can be undone from here.
     * Whether this succeeds is a platform question that must be tested on an emulator
     * before it is relied on; the plan explicitly says not to assume a test-only ADB
     * removal path stands in for the release build.
     */
    @Suppress("DEPRECATION")
    fun relinquishDeviceOwnership(): Result<Unit> = runCatching {
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
