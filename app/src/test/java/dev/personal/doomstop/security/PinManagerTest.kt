package dev.personal.doomstop.security

import dev.personal.doomstop.data.PinAttemptsEntity
import dev.personal.doomstop.data.PinVerifierEntity
import dev.personal.doomstop.domain.ClockSource
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

/** Both clocks, owned by the test, so a cooldown can be attacked from either direction. */
private class FakeClock(
    var wallMs: Long = 1_757_318_400_000L, // 2025-09-08T08:00:00Z, arbitrary fixed instant
    var elapsedMs: Long = 900_000L,
    var boot: String = "boot:3",
) : ClockSource {
    override fun elapsedRealtimeMs(): Long = elapsedMs
    override fun wallTimeMs(): Long = wallMs
    override fun bootId(): String = boot

    fun advance(ms: Long) {
        wallMs += ms
        elapsedMs += ms
    }
}

class PinManagerTest {

    /** Low iteration count: these tests exercise the logic, not the work factor. */
    private val iterations = 1_000

    private val clock = FakeClock()
    private val now get() = clock.wallMs

    private fun manager(store: PinStore, withClock: FakeClock = clock) = PinManager(store, withClock)

    private suspend fun PinManager.seed(pin: String) = setInitialPin(pin, iterations)

    @Test
    fun `the correct PIN verifies`() = runTest {
        val manager = manager(FakePinStore())
        manager.seed("013705")
        assertTrue(manager.verify("013705") is PinResult.Success)
    }

    @Test
    fun `leading zeros are significant`() = runTest {
        val manager = manager(FakePinStore())
        manager.seed("000042")
        assertTrue(manager.verify("000042") is PinResult.Success)
        // Would collide if the PIN were ever treated as a number.
        assertTrue(manager.verify("420000") is PinResult.Wrong)
    }

    @Test
    fun `the stored verifier is not the PIN and is salted`() = runTest {
        val a = FakePinStore()
        val b = FakePinStore()
        manager(a).seed("123456")
        manager(b).seed("123456")
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
        manager.seed("111111")
        assertTrue(manager.verify("12345") is PinResult.Malformed)
        assertTrue(manager.verify("12345a") is PinResult.Malformed)
        assertEquals(0, store.attempts?.consecutiveFailures ?: 0)
    }

    @Test
    fun `five wrong attempts are free, the sixth locks for thirty seconds`() = runTest {
        val manager = manager(FakePinStore())
        manager.seed("111111")

        repeat(4) {
            assertTrue(manager.verify("222222") is PinResult.Wrong)
            assertEquals(0L, manager.throttleState().lockedForMs)
        }
        assertTrue(manager.verify("222222") is PinResult.Wrong)
        assertEquals(PinThrottle.BASE_LOCK_MS, manager.throttleState().lockedForMs)

        // While locked, even the correct PIN is refused rather than consumed.
        clock.advance(1_000)
        assertTrue(manager.verify("111111") is PinResult.Throttled)
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
            lockBootId = "boot:3",
            nextAllowedElapsedMs = clock.elapsedMs + 60_000L,
        )
        assertEquals(60_000L, PinThrottle.remainingMs(attempts, now, clock.elapsedMs, "boot:3"))
        // Clock moved back a year: still locked, for the full penalty.
        assertEquals(
            60_000L,
            PinThrottle.remainingMs(attempts, now - 365L * 24 * 3600_000L, clock.elapsedMs, "boot:3"),
        )
        // Only genuine elapsed time clears it.
        assertEquals(
            0L,
            PinThrottle.remainingMs(attempts, now + 60_000L, clock.elapsedMs + 60_000L, "boot:3"),
        )
    }

    @Test
    fun `winding the clock forwards does not end a lockout early either`() = runTest {
        val manager = manager(FakePinStore())
        manager.seed("111111")
        repeat(5) { manager.verify("222222") }
        assertTrue(manager.throttleState().isLocked)

        // The date is set an hour ahead while no real time passes at all.
        clock.wallMs += 3600_000L
        assertTrue("the monotonic deadline is untouched by the date settings", manager.throttleState().isLocked)
        assertTrue(manager.verify("111111") is PinResult.Throttled)

        // Real time passing does clear it.
        clock.advance(PinThrottle.BASE_LOCK_MS)
        assertFalse(manager.throttleState().isLocked)
        assertTrue(manager.verify("111111") is PinResult.Success)
    }

    @Test
    fun `a reboot preserves the counter because it lives in the database`() = runTest {
        val store = FakePinStore()
        manager(store).seed("111111")
        // The sixth attempt is refused by the cooldown rather than counted, which is the
        // point: guessing cannot continue at full speed.
        repeat(6) { manager(store).verify("222222") }
        assertEquals(5, store.attempts?.consecutiveFailures)

        // A fresh manager over the same store, and a new boot, is what a restart sees.
        val rebooted = FakeClock(wallMs = clock.wallMs, elapsedMs = 4_000, boot = "boot:4")
        val afterReboot = manager(store, rebooted)
        assertEquals(5, afterReboot.throttleState().consecutiveFailures)
        assertTrue("the cooldown survives the restart", afterReboot.throttleState().isLocked)
        assertEquals(PinThrottle.BASE_LOCK_MS, afterReboot.throttleState().lockedForMs)
    }

    @Test
    fun `a correct PIN clears the failure counter`() = runTest {
        val store = FakePinStore()
        val manager = manager(store)
        manager.seed("111111")
        repeat(3) { manager.verify("222222") }
        assertEquals(3, store.attempts?.consecutiveFailures)
        assertTrue(manager.verify("111111") is PinResult.Success)
        assertEquals(0, store.attempts?.consecutiveFailures)
    }

    @Test
    fun `every success issues a distinct single-use ticket`() = runTest {
        val manager = manager(FakePinStore())
        manager.seed("111111")
        val first = (manager.verify("111111") as PinResult.Success).ticket
        val second = (manager.verify("111111") as PinResult.Success).ticket
        assertNotEquals(first.token, second.token)
    }

    // -- creating versus replacing ------------------------------------------------------

    @Test
    fun `a first PIN cannot be created once one exists`() = runTest {
        val manager = manager(FakePinStore())
        assertTrue(manager.seed("111111").isSuccess)
        val second = manager.setInitialPin("999999", iterations)
        assertTrue("replacing must go through the authorized path", second.isFailure)
        assertTrue(manager.verify("111111") is PinResult.Success)
    }

    @Test
    fun `replacing a PIN without authorization changes nothing`() = runTest {
        val manager = manager(FakePinStore())
        manager.seed("111111")
        val result = manager.replacePin("999999", iterations) { false }
        assertTrue(result.isFailure)
        assertTrue("the old PIN still works", manager.verify("111111") is PinResult.Success)
        assertTrue(manager.verify("999999") is PinResult.Wrong)
    }

    @Test
    fun `authorization that lapses while the key is derived does not save the new PIN`() = runTest {
        // The session is valid when the request is made and gone by the time the verifier
        // would be written -- the phone changed hands, or the idle timeout fired.
        val manager = manager(FakePinStore())
        manager.seed("111111")
        var calls = 0
        val result = manager.replacePin("999999", iterations) { calls++ == 0 }
        assertTrue(result.isFailure)
        assertEquals(2, calls)
        assertTrue(manager.verify("111111") is PinResult.Success)
    }

    @Test
    fun `an authorized replacement invalidates the old PIN and clears throttling`() = runTest {
        val manager = manager(FakePinStore())
        manager.seed("111111")
        repeat(6) { manager.verify("222222") }
        assertTrue(manager.throttleState().isLocked)

        assertTrue(manager.replacePin("999999", iterations) { true }.isSuccess)
        assertFalse(manager.throttleState().isLocked)
        assertTrue(manager.verify("999999") is PinResult.Success)
        assertTrue(manager.verify("111111") is PinResult.Wrong)
    }

    @Test
    fun `replacement is refused when no PIN has been set`() = runTest {
        assertTrue(manager(FakePinStore()).replacePin("999999", iterations) { true }.isFailure)
    }

    @Test
    fun `verification fails cleanly when no PIN has been set`() = runTest {
        assertTrue(manager(FakePinStore()).verify("123456") is PinResult.NotSet)
    }
}
