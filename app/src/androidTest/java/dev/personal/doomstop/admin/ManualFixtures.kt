package dev.personal.doomstop.admin

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Marks a "test" that exists to put a device into a known state for a MANUAL check, rather
 * than to assert anything by itself.
 *
 * These are excluded from ordinary runs (see `testInstrumentationRunnerArguments.notAnnotation`
 * in the build file) because they deliberately leave policy applied afterwards. They are the
 * repeatable way to reach the states the acceptance matrix asks a person to look at: an
 * exhausted allowance, a suspended app, an installed-then-suspended browser.
 */
annotation class ManualFixture

@RunWith(AndroidJUnit4::class)
class ManualFixtures {

    private val policy = PolicyController(ApplicationProvider.getApplicationContext())

    /** Leave every target suspended, so launching one can be observed by hand. */
    @ManualFixture
    @Test
    fun suspendTargetsAndLeaveThemSuspended() {
        assumeTrue(policy.isDeviceOwner)
        val report = policy.applyEnforcement(suspendTargets = true, suspendYouTube = false)
        println("DOOMSTOP-FIXTURE suspended=${report.installed.map { it.packageName to it.actualSuspended }}")
    }

    /** Put everything back. */
    @ManualFixture
    @Test
    fun releaseEverything() {
        assumeTrue(policy.isDeviceOwner)
        val report = policy.release(policy.manageablePackages())
        println("DOOMSTOP-FIXTURE released=${report.installed.map { it.packageName to it.actualSuspended }}")
    }

    /** Apply the permanent Chrome blocklist and leave it in force. */
    @ManualFixture
    @Test
    fun applyChromePolicyAndLeaveItApplied() {
        assumeTrue(policy.isDeviceOwner)
        val report = policy.applyChromeBlocklist()
        println("DOOMSTOP-FIXTURE chromeStored=${report.storedPolicyVerified} value=${report.verifiedValue}")
    }
}
