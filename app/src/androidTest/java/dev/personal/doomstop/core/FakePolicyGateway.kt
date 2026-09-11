package dev.personal.doomstop.core

import dev.personal.doomstop.admin.ChromePolicyReport
import dev.personal.doomstop.admin.DevicePolicyGateway
import dev.personal.doomstop.admin.HardeningOptions
import dev.personal.doomstop.admin.PackageOutcome
import dev.personal.doomstop.admin.SelfProtectionReport
import dev.personal.doomstop.admin.SuspensionReport
import dev.personal.doomstop.config.BlockedBrowsers
import dev.personal.doomstop.config.BlockedSites
import dev.personal.doomstop.config.ShortsGuard
import dev.personal.doomstop.config.TargetPackages

/**
 * A device-owner stand-in that behaves like a correct platform by default and can be made
 * to fail one step at a time.
 *
 * The real [dev.personal.doomstop.admin.PolicyController] is exercised against an actual
 * provisioned device in its own suite; what this exists for is the coordinator's behaviour
 * AROUND those calls -- the write-ahead ledger, the staged restore, and above all what
 * happens when one stage fails, which cannot be produced on demand from a real device.
 */
class FakePolicyGateway(
    installed: Set<String> = TargetPackages.ALL + BlockedBrowsers.ALL + BlockedSites.CHROME_PACKAGE,
) : DevicePolicyGateway {

    override var isDeviceOwner: Boolean = true

    private val installedPackages = installed.toMutableSet()
    val suspended = mutableMapOf<String, Boolean>()

    var chromeBlocklist: String? = null
    var uninstallBlocked = false
    var userControlDisabled = mutableListOf<String>()
    var restrictions = mutableSetOf<String>()
    var autoTime: Boolean? = false

    // Failure injection, one stage at a time.
    var failRelease = false
    var failChromeApply = false
    var failChromeRestore = false
    var failHardeningRestore = false
    var failProtectSelf = false
    var failRelinquish = false
    var unreadableSuspension = false
    var unreadableChrome = false

    var relinquishCalls = 0
        private set

    override fun applyEnforcement(suspendTargets: Boolean, suspendYouTube: Boolean): SuspensionReport {
        val outcomes = TargetPackages.ALL.map { outcome(it, suspendTargets) } +
            BlockedBrowsers.ALL.map { outcome(it, true) } +
            outcome(ShortsGuard.YOUTUBE_PACKAGE, suspendYouTube)
        return SuspensionReport(outcomes)
    }

    override fun release(packages: Set<String>): SuspensionReport =
        SuspensionReport(packages.map { outcome(it, false, forceFailure = failRelease) })

    private fun outcome(packageName: String, wanted: Boolean, forceFailure: Boolean = false): PackageOutcome {
        if (packageName !in installedPackages) {
            return PackageOutcome(packageName, installed = false, requestedSuspended = null, actualSuspended = null)
        }
        if (forceFailure) {
            return PackageOutcome(packageName, true, wanted, suspended[packageName], "injected failure")
        }
        suspended[packageName] = wanted
        return PackageOutcome(packageName, true, wanted, wanted)
    }

    override fun manageablePackages(): Set<String> =
        TargetPackages.ALL + BlockedBrowsers.ALL + ShortsGuard.YOUTUBE_PACKAGE

    override fun readSuspended(packageName: String): Boolean? = when {
        unreadableSuspension -> null
        packageName !in installedPackages -> null
        else -> suspended[packageName] ?: false
    }

    override fun isInstalled(packageName: String): Boolean = packageName in installedPackages

    override fun installedTargets(): List<String> = TargetPackages.ALL.filter { it in installedPackages }

    override fun installedBlockedBrowsers(): List<String> = BlockedBrowsers.ALL.filter { it in installedPackages }

    override fun applyChromeBlocklist(): ChromePolicyReport {
        if (failChromeApply) {
            return ChromePolicyReport(true, null, chromeBlocklist, false, "injected failure")
        }
        chromeBlocklist = BlockedSites.blocklistJson()
        return ChromePolicyReport(true, chromeBlocklist, chromeBlocklist, true)
    }

    override fun readChromeBlocklist(): Result<String?> =
        if (unreadableChrome) Result.failure(IllegalStateException("injected")) else Result.success(chromeBlocklist)

    override fun restoreChromeBlocklist(previousValue: String?): Result<Unit> =
        if (failChromeRestore) {
            Result.failure(IllegalStateException("injected failure"))
        } else {
            chromeBlocklist = previousValue
            Result.success(Unit)
        }

    override fun readUninstallBlocked(): Boolean? = uninstallBlocked

    override fun readUserControlDisabledPackages(): List<String>? = userControlDisabled.toList()

    override fun protectSelf(enabled: Boolean, otherPackages: List<String>): SelfProtectionReport {
        if (failProtectSelf) {
            return SelfProtectionReport(enabled, uninstallBlocked, userControlDisabled.contains(SELF), "injected failure")
        }
        uninstallBlocked = enabled
        userControlDisabled = (if (enabled) otherPackages + SELF else otherPackages).toMutableList()
        return SelfProtectionReport(enabled, uninstallBlocked, userControlDisabled.contains(SELF))
    }

    override fun applyHardening(options: HardeningOptions): Set<String> {
        restrictions = mutableSetOf<String>().apply {
            if (options.disallowAddUser) add(android.os.UserManager.DISALLOW_ADD_USER)
            if (options.disallowSafeBoot) add(android.os.UserManager.DISALLOW_SAFE_BOOT)
            if (options.disallowManualDateTime) add(android.os.UserManager.DISALLOW_CONFIG_DATE_TIME)
            if (options.disallowDebuggingFeatures) add(android.os.UserManager.DISALLOW_DEBUGGING_FEATURES)
        }
        if (options.disallowManualDateTime) autoTime = true
        return restrictions
    }

    override fun restoreHardening(previousRestrictions: Set<String>?, previousAutoTime: Boolean?): Result<Unit> {
        if (failHardeningRestore) return Result.failure(IllegalStateException("injected failure"))
        restrictions = (previousRestrictions ?: emptySet()).toMutableSet()
        if (previousAutoTime != null) autoTime = previousAutoTime
        return Result.success(Unit)
    }

    override fun activeRestrictions(): Set<String> = restrictions.toSet()

    override fun readAutoTime(): Boolean? = autoTime

    override fun relinquishDeviceOwnership(): Result<Unit> {
        relinquishCalls++
        if (failRelinquish) return Result.failure(IllegalStateException("injected failure"))
        isDeviceOwner = false
        return Result.success(Unit)
    }

    private companion object {
        const val SELF = "dev.personal.doomstop"
    }
}
