package dev.personal.doomstop.admin

/**
 * Everything the coordinator is allowed to ask the platform to do.
 *
 * This is the same surface [PolicyController] already had; naming it as an interface does
 * two things. It states, in one place, the complete set of changes this app can make to the
 * device -- nothing outside these methods is reachable. And it lets the coordinator's own
 * behaviour (accounting, latched recovery, staged restore, the write-ahead ledger) be tested
 * against injected outcomes, including failures that cannot be produced on demand from a
 * real device owner, without weakening what the real implementation verifies.
 *
 * Reads are separate from writes throughout, so a caller can record a value in its ledger
 * before changing it.
 */
interface DevicePolicyGateway {

    val isDeviceOwner: Boolean

    // -- package suspension ---------------------------------------------------------------

    fun applyEnforcement(suspendTargets: Boolean): SuspensionReport
    fun release(packages: Set<String>): SuspensionReport
    fun manageablePackages(): Set<String>
    fun readSuspended(packageName: String): Boolean?
    fun isInstalled(packageName: String): Boolean
    fun installedTargets(): List<String>
    fun installedBlockedBrowsers(): List<String>

    // -- Chrome URL policy ----------------------------------------------------------------

    fun applyChromeBlocklist(): ChromePolicyReport
    fun readChromeBlocklist(): Result<String?>
    fun restoreChromeBlocklist(previousValue: String?): Result<Unit>

    // -- this app's own protection ---------------------------------------------------------

    fun readUninstallBlocked(): Boolean?
    fun readUserControlDisabledPackages(): List<String>?
    fun protectSelf(enabled: Boolean = true, otherPackages: List<String> = emptyList()): SelfProtectionReport

    // -- optional hardening -----------------------------------------------------------------

    fun applyHardening(options: HardeningOptions): Set<String>
    fun restoreHardening(previousRestrictions: Set<String>?, previousAutoTime: Boolean?): Result<Unit>
    fun activeRestrictions(): Set<String>
    fun readAutoTime(): Boolean?

    // -- removal ------------------------------------------------------------------------------

    fun relinquishDeviceOwnership(): Result<Unit>
}
