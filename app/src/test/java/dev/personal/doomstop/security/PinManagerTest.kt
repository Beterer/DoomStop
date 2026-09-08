package dev.personal.doomstop.security

import dev.personal.doomstop.data.PinAttemptsEntity
import dev.personal.doomstop.data.PinVerifierEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [PinStore]; the real one is a two-row table, so this is a faithful stand-in. */
private class FakePinStore : PinStore {
    var verifier: PinVerifierEntity? = null
    var attempts: PinAttemptsEntity? = null

    override suspend fun verifier(): PinVerifierEntity? = verifier
    override suspend fun saveVerifier(verifier: PinVerifierEntity) {
        this.verifier = verifier
    }

    override suspend fun attempts(): PinAttemptsEntity? = attempts
    override suspend fun saveAttempts(attempts: PinAttemptsEntity) {
        this.attempts = attempts
    }
}

class PinManagerTest {

    /** Low iteration count: these tests exercise the logic, not the work factor. */
    private val iterations = 1_000
    private val now = 1_757_318_400_000L // 2025-09-08T08:00:00Z, arbitrary fixed instant

    private fun manager(store: PinStore) = PinManager(store)

    @Test
    fun `the correct PIN verifies`() = runTest {
        val store = FakePinStore()
        val manager = manager(store)
        manager.setPin("013705", iterations)
        assertTrue(manager.verify("013705", now) is PinResult.Success)
    }

    @Test
    fun `leading zeros are significant`() = runTest {
        val store = FakePinStore()
        val manager = manager(store)
        manager.setPin("000042", iterations)
        assertTrue(manager.verify("000042", now) is PinResult.Success)
        // Would collide if the PIN were ever treated as a number.
        assertTrue(manager.verify("420000", now) is PinResult.Wrong)
    }

    @Test
    fun `the stored verifier is not the PIN and is salted`() = runTest {
        val a = FakePinStore()
        val b = FakePinStore()
        manager(a).setPin("123456", iterations)
        manager(b).setPin("123456", iterations)
        val first = requireNotNull(a.verifier)
        val second = requireNotNull(b.verifier)
        assertNotEquals("same PIN must not produce the same salt", first.saltBase64, second.saltBase64)
        assertNotEquals("same PIN must not produce the same verifier", first.verifierBase64, second.verifierBase64)
        assertFalse(first.verifierBase64.contains("123456"))
    }

    @Test
    fun `a malformed PIN is rejected without spending an attempt`() = runTest {
        val store = FakePinStore()
        val manager = manager(store)
        manager.setPin("111111", iterations)
        assertTrue(manager.verify("12345", now) is PinResult.Malformed)
        assertTrue(manager.verify("12345a", now) is PinResult.Malformed)
        assertEquals(0, store.attempts?.consecutiveFailures ?: 0)
    }

    @Test
    fun `five wrong attempts are free, the sixth locks for thirty seconds`() = runTest {
        val store = FakePinStore()
        val manager = manager(store)
        manager.setPin("111111", iterations)

        repeat(4) {
            assertTrue(manager.verify("222222", now) is PinResult.Wrong)
            assertEquals(0L, manager.throttleState(now).lockedForMs)
        }
        assertTrue(manager.verify("222222", now) is PinResult.Wrong)
        assertEquals(PinThrottle.BASE_LOCK_MS, manager.throttleState(now).lockedForMs)

        // While locked, even the correct PIN is refused rather than consumed.
        assertTrue(manager.verify("111111", now + 1_000) is PinResult.Throttled)
    }

    @Test
    fun `the lock doubles and is capped at thirty minutes`() {
        assertEquals(0L, PinThrottle.lockDurationMs(4))
        assertEquals(30_000L, PinThrottle.lockDurationMs(5))
        assertEquals(60_000L, PinThrottle.lockDurationMs(6))
        assertEquals(120_000L, PinThrottle.lockDurationMs(7))
        assertEquals(PinThrottle.MAX_LOCK_MS, PinThrottle.lockDurationMs(12))
        assertEquals("no overflow at absurd counts", PinThrottle.MAX_LOCK_MS, PinThrottle.lockDurationMs(9_999))
    }

    @Test
    fun `winding the clock backwards does not end a lockout early`() {
        val attempts = PinAttemptsEntity(
            consecutiveFailures = 6,
            lastFailureWallMs = now,
            nextAllowedWallMs = now + 60_000L,
        )
        assertEquals(60_000L, PinThrottle.remainingMs(attempts, now))
        // Clock moved back a year: still locked, for the full penalty.
        assertEquals(60_000L, PinThrottle.remainingMs(attempts, now - 365L * 24 * 3600_000L))
        // Only genuine elapsed time clears it.
        assertEquals(0L, PinThrottle.remainingMs(attempts, now + 60_000L))
    }

    @Test
    fun `a reboot preserves the counter because it lives in the database`() = runTest {
        val store = FakePinStore()
        manager(store).setPin("111111", iterations)
        // The sixth attempt is refused by the cooldown rather than counted, which is the
        // point: guessing cannot continue at full speed.
        repeat(6) { manager(store).verify("222222", now) }
        assertEquals(5, store.attempts?.consecutiveFailures)

        // A fresh manager over the same store is exactly what a restarted process sees.
        val afterReboot = manager(store)
        assertEquals(5, afterReboot.throttleState(now).consecutiveFailures)
        assertTrue("the cooldown survives the restart", afterReboot.throttleState(now).isLocked)
        assertEquals(PinThrottle.BASE_LOCK_MS, afterReboot.throttleState(now).lockedForMs)
    }

    @Test
    fun `a correct PIN clears the failure counter`() = runTest {
        val store = FakePinStore()
        val manager = manager(store)
        manager.setPin("111111", iterations)
        repeat(3) { manager.verify("222222", now) }
        assertEquals(3, store.attempts?.consecutiveFailures)
        assertTrue(manager.verify("111111", now) is PinResult.Success)
        assertEquals(0, store.attempts?.consecutiveFailures)
    }

    @Test
    fun `every success issues a distinct single-use ticket`() = runTest {
        val store = FakePinStore()
        val manager = manager(store)
        manager.setPin("111111", iterations)
        val first = (manager.verify("111111", now) as PinResult.Success).ticket
        val second = (manager.verify("111111", now) as PinResult.Success).ticket
        assertNotEquals(first.token, second.token)
    }

    @Test
    fun `changing the PIN invalidates the old one and clears throttling`() = runTest {
        val store = FakePinStore()
        val manager = manager(store)
        manager.setPin("111111", iterations)
        repeat(6) { manager.verify("222222", now) }
        assertTrue(manager.throttleState(now).isLocked)

        manager.setPin("999999", iterations)
        assertFalse(manager.throttleState(now).isLocked)
        assertTrue(manager.verify("999999", now) is PinResult.Success)
        assertTrue(manager.verify("111111", now) is PinResult.Wrong)
    }

    @Test
    fun `verification fails cleanly when no PIN has been set`() = runTest {
        assertTrue(manager(FakePinStore()).verify("123456", now) is PinResult.NotSet)
    }
}
