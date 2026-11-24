package dev.personal.doomstop.domain

/** Three grants in each consecutive 30-day period, with no balance carried into the next. */
data class ExtensionQuota(val periodStartWallMs: Long, val used: Int = 0) {
    val remaining: Int get() = GRANTS_PER_PERIOD - used
    val resetsAtWallMs: Long get() = periodStartWallMs + PERIOD_MS

    /** Keep the original cadence after downtime and never reopen a past period. */
    fun at(nowWallMs: Long): ExtensionQuota {
        val periodsElapsed = (nowWallMs - periodStartWallMs) / PERIOD_MS
        return if (periodsElapsed > 0) ExtensionQuota(periodStartWallMs + periodsElapsed * PERIOD_MS) else this
    }

    companion object {
        const val GRANTS_PER_PERIOD = 3
        const val GRANT_DURATION_MS = 10L * 60_000L
        const val PERIOD_MS = 30L * 24 * 60 * 60_000L
    }
}
