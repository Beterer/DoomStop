package dev.personal.doomstop.security

import dev.personal.doomstop.data.LimiterDao
import dev.personal.doomstop.data.PinAttemptsEntity
import dev.personal.doomstop.data.PinVerifierEntity

/**
 * The narrow slice of persistence the PIN needs.
 *
 * Keeping this separate from the full DAO is what lets the KDF, the constant-time
 * comparison and the throttling schedule be exercised by ordinary JVM unit tests, without
 * a device and without stubbing thirty unrelated queries.
 */
interface PinStore {
    suspend fun verifier(): PinVerifierEntity?
    suspend fun saveVerifier(verifier: PinVerifierEntity)
    suspend fun attempts(): PinAttemptsEntity?
    suspend fun saveAttempts(attempts: PinAttemptsEntity)
}

class DaoPinStore(private val dao: LimiterDao) : PinStore {
    override suspend fun verifier(): PinVerifierEntity? = dao.pinVerifier()
    override suspend fun saveVerifier(verifier: PinVerifierEntity) = dao.upsertPinVerifier(verifier)
    override suspend fun attempts(): PinAttemptsEntity? = dao.pinAttempts()
    override suspend fun saveAttempts(attempts: PinAttemptsEntity) = dao.upsertPinAttempts(attempts)
}
