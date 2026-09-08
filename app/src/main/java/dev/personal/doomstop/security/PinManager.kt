package dev.personal.doomstop.security

import dev.personal.doomstop.data.PinAttemptsEntity
import dev.personal.doomstop.data.PinVerifierEntity
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.min
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A single-use authorization produced by a correct PIN entry. */
data class AuthorizationTicket(val token: String, val issuedAtWallMs: Long)

sealed interface PinResult {
    data class Success(val ticket: AuthorizationTicket) : PinResult
    data class Wrong(val consecutiveFailures: Int, val nextAllowedWallMs: Long) : PinResult
    data class Throttled(val remainingMs: Long) : PinResult
    data object NotSet : PinResult
    data class Malformed(val reason: String) : PinResult
}

data class ThrottleState(val consecutiveFailures: Int, val lockedForMs: Long) {
    val isLocked: Boolean get() = lockedForMs > 0
}

/**
 * The throttling schedule, as pure arithmetic so it can be tested exhaustively.
 *
 * No delay for the first [FAILURES_BEFORE_THROTTLING] wrong attempts, then 30 seconds,
 * doubling with each further failure up to a 30-minute ceiling. Both the counter and the
 * deadline are persisted, so a reboot neither clears the count nor shortens an active
 * cooldown.
 */
object PinThrottle {

    const val FAILURES_BEFORE_THROTTLING = 5
    const val BASE_LOCK_MS = 30_000L
    const val MAX_LOCK_MS = 30L * 60L * 1000L

    fun lockDurationMs(consecutiveFailures: Int): Long {
        if (consecutiveFailures < FAILURES_BEFORE_THROTTLING) return 0
        val steps = consecutiveFailures - FAILURES_BEFORE_THROTTLING
        // Clamp before shifting so a large failure count cannot overflow into a short lock.
        val scaled = if (steps >= 32) MAX_LOCK_MS else BASE_LOCK_MS shl steps
        return min(scaled, MAX_LOCK_MS)
    }

    /**
     * How long a cooldown still has to run.
     *
     * Two clocks are compared, not one: a cooldown is also treated as active while "now" is
     * EARLIER than the last recorded failure. Winding the clock backwards therefore cannot
     * end a lockout early.
     */
    fun remainingMs(attempts: PinAttemptsEntity, nowWallMs: Long): Long {
        if (attempts.consecutiveFailures < FAILURES_BEFORE_THROTTLING) return 0
        if (nowWallMs < attempts.lastFailureWallMs) return lockDurationMs(attempts.consecutiveFailures)
        return (attempts.nextAllowedWallMs - nowWallMs).coerceAtLeast(0L)
    }
}

/**
 * Six-digit PIN storage and verification.
 *
 * What this does and does not claim: six digits is a million possibilities, which is not a
 * lot. PBKDF2 raises the cost of an OFFLINE attack against a stolen database but does not
 * turn six digits into a strong secret. The real protections are the Android sandbox,
 * backup and data extraction being disabled for this app, and the online throttling above.
 * Nothing here should be read as "hashing makes the PIN strong".
 *
 * The PIN is a String throughout, never an Int, so leading zeros survive.
 */
class PinManager(
    private val store: PinStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    suspend fun isPinSet(): Boolean = store.verifier() != null

    /**
     * Choose the iteration count on THIS device by measuring it, rather than shipping a
     * number that means something different on every phone. Always runs off the main thread.
     */
    suspend fun calibrateIterations(targetMs: Long = TARGET_DERIVE_MS): Int = withContext(dispatcher) {
        val probeIterations = 20_000
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val started = System.nanoTime()
        derive("000000", salt, probeIterations)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        val scaled = if (elapsedMs <= 0.0) MAX_ITERATIONS else (probeIterations * (targetMs / elapsedMs)).toInt()
        scaled.coerceIn(MIN_ITERATIONS, MAX_ITERATIONS)
    }

    /**
     * Store a new PIN. Replacing an existing PIN also clears the throttling counters,
     * because those exist to slow down guessing at the OLD secret.
     */
    suspend fun setPin(pin: String, iterations: Int? = null): Result<Unit> {
        validate(pin)?.let { return Result.failure(IllegalArgumentException(it)) }
        val rounds = iterations ?: calibrateIterations()
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val verifier = withContext(dispatcher) { derive(pin, salt, rounds) }
        store.saveVerifier(
            PinVerifierEntity(
                version = VERIFIER_VERSION,
                algorithm = ALGORITHM,
                iterations = rounds,
                saltBase64 = Base64.getEncoder().encodeToString(salt),
                verifierBase64 = Base64.getEncoder().encodeToString(verifier),
            )
        )
        store.saveAttempts(PinAttemptsEntity(consecutiveFailures = 0, lastFailureWallMs = 0, nextAllowedWallMs = 0))
        return Result.success(Unit)
    }

    /** Current cooldown, for the PIN screen's countdown, without spending an attempt. */
    suspend fun throttleState(nowWallMs: Long): ThrottleState {
        val attempts = store.attempts() ?: return ThrottleState(0, 0)
        return ThrottleState(attempts.consecutiveFailures, PinThrottle.remainingMs(attempts, nowWallMs))
    }

    /**
     * Verify a PIN attempt.
     *
     * A wrong PIN costs an attempt and never affects ordinary phone use. A correct PIN
     * returns a single-use ticket; the caller redeems it for exactly one action, and a new
     * PIN entry is required for the next one.
     */
    suspend fun verify(pin: String, nowWallMs: Long): PinResult {
        val stored = store.verifier() ?: return PinResult.NotSet
        validate(pin)?.let { return PinResult.Malformed(it) }

        val attempts = store.attempts()
            ?: PinAttemptsEntity(consecutiveFailures = 0, lastFailureWallMs = 0, nextAllowedWallMs = 0)
        val locked = PinThrottle.remainingMs(attempts, nowWallMs)
        if (locked > 0) return PinResult.Throttled(locked)

        val salt = Base64.getDecoder().decode(stored.saltBase64)
        val expected = Base64.getDecoder().decode(stored.verifierBase64)
        val actual = withContext(dispatcher) { derive(pin, salt, stored.iterations) }

        // Constant-time comparison: MessageDigest.isEqual does not short-circuit.
        return if (MessageDigest.isEqual(expected, actual)) {
            store.saveAttempts(attempts.copy(consecutiveFailures = 0, nextAllowedWallMs = 0))
            PinResult.Success(AuthorizationTicket(UUID.randomUUID().toString(), nowWallMs))
        } else {
            val failures = attempts.consecutiveFailures + 1
            val nextAllowed = nowWallMs + PinThrottle.lockDurationMs(failures)
            store.saveAttempts(
                attempts.copy(
                    consecutiveFailures = failures,
                    lastFailureWallMs = nowWallMs,
                    nextAllowedWallMs = nextAllowed,
                )
            )
            PinResult.Wrong(failures, nextAllowed)
        }
    }

    private fun validate(pin: String): String? = when {
        pin.length != PIN_LENGTH -> "The PIN must be exactly $PIN_LENGTH digits"
        !pin.all { it in '0'..'9' } -> "The PIN must contain digits only"
        else -> null
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val PIN_LENGTH = 6

        private const val ALGORITHM = "PBKDF2withHmacSHA256"
        private const val VERIFIER_VERSION = 1
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256

        /** Plan section 8 asks for a work factor around 200-500 ms on the actual device. */
        private const val TARGET_DERIVE_MS = 300L
        private const val MIN_ITERATIONS = 50_000
        private const val MAX_ITERATIONS = 2_000_000
    }
}
