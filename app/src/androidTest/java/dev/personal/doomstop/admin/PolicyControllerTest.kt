package dev.personal.doomstop.admin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.personal.doomstop.config.BlockedBrowsers
import dev.personal.doomstop.config.BlockedSites
import dev.personal.doomstop.config.ShortsGuard
import dev.personal.doomstop.config.TargetPackages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gate B and Gate C, executed against a real provisioned device.
 *
 * These assert the state the PLATFORM reports back, never the return value of the call
 * that asked for it: setPackagesSuspended reporting no failure is not evidence that
 * anything changed, and setApplicationRestrictions succeeding is not evidence that Chrome
 * accepted the value.
 *
 * Every test is skipped rather than failed when the device is not provisioned, so the
 * suite stays meaningful on an unprovisioned phone instead of producing noise.
 */
@RunWith(AndroidJUnit4::class)
class PolicyControllerTest {

    private lateinit var context: Context
    private lateinit var policy: PolicyController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        policy = PolicyController(context)
        assumeTrue("requires a provisioned device owner", policy.isDeviceOwner)
    }

    @After
    fun tearDown() {
        // Leave the device usable regardless of how a test ended.
        if (policy.isDeviceOwner) policy.release(policy.manageablePackages())
    }

    // -- Gate B -------------------------------------------------------------------------

    @Test
    fun suspendingTargetsChangesTheStateThePlatformReports() {
        val installed = policy.installedTargets()
        assumeTrue("no target packages installed to suspend", installed.isNotEmpty())

        val suspended = policy.applyEnforcement(suspendTargets = true, suspendYouTube = false)
        assertTrue("failures: ${suspended.failures.map { it.packageName to it.error }}", suspended.allApplied)
        for (packageName in installed) {
            val outcome = suspended.outcomes.first { it.packageName == packageName }
            assertEquals("$packageName should read back as suspended", true, outcome.actualSuspended)
        }

        val released = policy.applyEnforcement(suspendTargets = false, suspendYouTube = false)
        assertTrue(released.allApplied)
        for (packageName in installed) {
            val outcome = released.outcomes.first { it.packageName == packageName }
            assertEquals("$packageName should read back as available", false, outcome.actualSuspended)
        }
    }

    @Test
    fun browsersStaySuspendedEvenWhileAllowanceRemains() {
        val installedBrowsers = policy.installedBlockedBrowsers()
        assumeTrue("no hardcoded browser installed", installedBrowsers.isNotEmpty())

        // suspendTargets = false is the "time remaining" case; browsers must not follow it.
        val report = policy.applyEnforcement(suspendTargets = false, suspendYouTube = false)
        for (packageName in installedBrowsers) {
            val outcome = report.outcomes.first { it.packageName == packageName }
            assertEquals("$packageName must stay suspended", true, outcome.actualSuspended)
        }
    }

    @Test
    fun chromeAndWebViewAreNeverSuspended() {
        policy.applyEnforcement(suspendTargets = true, suspendYouTube = true)
        for (packageName in BlockedBrowsers.NEVER_SUSPEND) {
            assertFalse(
                "$packageName must never appear in an enforcement report",
                policy.applyEnforcement(suspendTargets = true, suspendYouTube = true).outcomes.any { it.packageName == packageName },
            )
        }
        // And Chrome must still be usable.
        assumeTrue(policy.isInstalled(BlockedSites.CHROME_PACKAGE))
        val launch = context.packageManager.getLaunchIntentForPackage(BlockedSites.CHROME_PACKAGE)
        assertNotNull("Chrome must remain launchable", launch)
    }

    @Test
    fun reinstallingATargetPicksUpTheCurrentSuspensionState() {
        val installed = policy.installedTargets()
        assumeTrue(installed.isNotEmpty())
        // Re-applying enforcement is what a package-added broadcast triggers; it must be
        // idempotent and must reassert, not toggle.
        repeat(3) {
            val report = policy.applyEnforcement(suspendTargets = true, suspendYouTube = true)
            assertTrue(report.allApplied)
        }
        assertTrue(policy.applyEnforcement(suspendTargets = true, suspendYouTube = true).installed.all { it.actualSuspended == true })
    }

    @Test
    fun youtubeFollowsTheShortsGuardRuleNotTheAllowance() {
        assumeTrue("YouTube is not installed", policy.isInstalled(ShortsGuard.YOUTUBE_PACKAGE))

        // Guard off while allowance remains: YouTube goes, targets stay.
        val off = policy.applyEnforcement(suspendTargets = false, suspendYouTube = true)
        assertTrue("failures: ${off.failures.map { it.packageName to it.error }}", off.allApplied)
        assertEquals(true, off.outcomes.first { it.packageName == ShortsGuard.YOUTUBE_PACKAGE }.actualSuspended)

        // Guard back on with the allowance spent: YouTube returns, targets stay suspended.
        val on = policy.applyEnforcement(suspendTargets = true, suspendYouTube = false)
        assertTrue(on.allApplied)
        assertEquals(false, on.outcomes.first { it.packageName == ShortsGuard.YOUTUBE_PACKAGE }.actualSuspended)
    }

    // -- Gate C -------------------------------------------------------------------------

    @Test
    fun chromeBlocklistIsWrittenAsAStringAndReadsBackComplete() {
        assumeTrue("Chrome is not installed", policy.isInstalled(BlockedSites.CHROME_PACKAGE))

        val report = policy.applyChromeBlocklist()
        assertTrue("error: ${report.error}", report.chromeInstalled)
        assertNotNull("nothing was read back", report.verifiedValue)
        assertTrue(
            "read-back did not contain every required host: ${report.verifiedValue}",
            report.storedPolicyVerified,
        )
        // Parsed rather than searched as text: Android's org.json writes "/" as "\/", so the
        // path filter never appears verbatim in the stored string.
        val stored = org.json.JSONArray(report.verifiedValue!!)
        val present = (0 until stored.length()).map { stored.getString(it) }
        for (filter in BlockedSites.FILTERS) {
            assertTrue("missing $filter", filter in present)
        }
    }

    @Test
    fun applyingTheBlocklistPreservesUnrelatedRestrictions() {
        assumeTrue(policy.isInstalled(BlockedSites.CHROME_PACKAGE))
        val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        val admin = android.content.ComponentName(context, LimiterAdminReceiver::class.java)

        val existing = dpm.getApplicationRestrictions(admin, BlockedSites.CHROME_PACKAGE) ?: android.os.Bundle()
        val probed = android.os.Bundle(existing).apply { putString("HomepageLocation", "https://example.org/") }
        dpm.setApplicationRestrictions(admin, BlockedSites.CHROME_PACKAGE, probed)

        policy.applyChromeBlocklist()

        val after = dpm.getApplicationRestrictions(admin, BlockedSites.CHROME_PACKAGE)
        assertEquals(
            "an unrelated Chrome restriction was wiped",
            "https://example.org/",
            after?.getString("HomepageLocation"),
        )

        // Put Chrome's restrictions back the way this test found them.
        dpm.setApplicationRestrictions(admin, BlockedSites.CHROME_PACKAGE, existing)
    }

    @Test
    fun restoringTheBlocklistRemovesOnlyThatKey() {
        assumeTrue(policy.isInstalled(BlockedSites.CHROME_PACKAGE))
        policy.applyChromeBlocklist()
        assertTrue(policy.restoreChromeBlocklist(previousValue = null).isSuccess)
        assertEquals(null, policy.readChromeBlocklist().getOrThrow())
    }

    // -- self protection ------------------------------------------------------------------

    @Test
    fun selfProtectionAppliesOnlyToThisPackage() {
        val report = policy.protectSelf(enabled = true)
        assertTrue("error: ${report.error}", report.uninstallBlocked)
        assertTrue(report.userControlDisabled)

        val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        val admin = android.content.ComponentName(context, LimiterAdminReceiver::class.java)
        assertEquals(
            "user control must be disabled for this package alone",
            listOf(context.packageName),
            dpm.getUserControlDisabledPackages(admin),
        )
        // Unrelated apps keep their normal controls.
        for (packageName in TargetPackages.ALL) {
            assertFalse(dpm.isUninstallBlocked(admin, packageName))
        }

        policy.protectSelf(enabled = false)
    }

    @Test
    fun hardeningAppliesExactlyWhatWasAskedForAndNothingElse() {
        val active = policy.applyHardening(HardeningOptions(disallowSafeBoot = true))
        assertEquals(setOf(android.os.UserManager.DISALLOW_SAFE_BOOT), active)

        // Notably absent, and required to stay absent: app installation is never blocked.
        val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        val admin = android.content.ComponentName(context, LimiterAdminReceiver::class.java)
        val restrictions = dpm.getUserRestrictions(admin)
        assertFalse(
            "app installation must never be restricted",
            restrictions.getBoolean(android.os.UserManager.DISALLOW_INSTALL_APPS, false),
        )
        assertFalse(
            "debugging must not be disabled without an explicit choice",
            restrictions.getBoolean(android.os.UserManager.DISALLOW_DEBUGGING_FEATURES, false),
        )

        assertTrue(policy.applyHardening(HardeningOptions()).isEmpty())
    }
}
