package dev.personal.doomstop.monitor

/** One tracked activity, flattened for storage. */
data class TrackedActivityState(
    val packageName: String,
    val className: String,
    val resumed: Boolean,
    val sinceMs: Long,
    val unresolved: Boolean,
)

/**
 * The visibility observer's state, in a form that survives process death.
 *
 * This exists because the durable checkpoint used to record "a target was visible" while
 * the observer that produced that fact was rebuilt empty on the next process start. The
 * inherited interval was then charged once and visibility immediately recorded as false,
 * so an app left open across a restart became free. The state is now written in the same
 * transaction as the checkpoint it belongs to, and read back before any accounting runs.
 *
 * Encoding is hand-rolled rather than JSON on purpose: the accounting path must stay pure
 * Kotlin so it can be exercised by ordinary JVM unit tests, and `org.json` on the unit-test
 * classpath is a stub that silently returns nothing. The format is a version tag, one
 * header record, then one record per activity, with unit and record separators that cannot
 * occur in a package or class name.
 */
data class TrackerState(
    val screenInteractive: Boolean,
    val keyguardShown: Boolean,
    val logicalNowMs: Long,
    val lastVisible: Boolean,
    val activities: List<TrackedActivityState>,
    /**
     * Packages temporarily excluded from metering, e.g. Instagram while a DM screen is on
     * top. Written into the header so the exemption survives a restart the same way the rest
     * of the observer does; empty for any state written before this field existed.
     */
    val maskedPackages: List<String> = emptyList(),
) {

    fun encode(): String = buildString {
        append(VERSION).append(UNIT)
        append(screenInteractive.asFlag()).append(UNIT)
        append(keyguardShown.asFlag()).append(UNIT)
        append(logicalNowMs).append(UNIT)
        append(lastVisible.asFlag()).append(UNIT)
        append(maskedPackages.joinToString(MASK_SEPARATOR.toString()) { it.sanitized() })
        for (activity in activities) {
            append(RECORD)
            append(activity.packageName.sanitized()).append(UNIT)
            append(activity.className.sanitized()).append(UNIT)
            append(activity.resumed.asFlag()).append(UNIT)
            append(activity.sinceMs).append(UNIT)
            append(activity.unresolved.asFlag())
        }
    }

    companion object {
        /**
         * 1: header of five fields. 2: adds a sixth header field, the comma-joined masked
         * packages. Both are still readable; a version-1 row simply has no masked packages.
         */
        const val VERSION = 2

        private val UNIT: Char = Char(31)
        private val RECORD: Char = Char(30)

        /** Joins masked package names inside the single header field that holds them. */
        private const val MASK_SEPARATOR = ','

        /**
         * Returns null for anything this version cannot read with certainty -- absent,
         * truncated, or written by a newer format. The caller treats null as "no observer
         * state", which is safe precisely because it is not silently treated as "nothing was
         * on screen": the coordinator latches recovery if the checkpoint claimed visibility.
         *
         * Both header layouts this app has ever written are accepted, so an in-place update
         * does not throw away a live observer (and spuriously latch recovery) just because the
         * masked-packages field was added.
         */
        fun decode(text: String?): TrackerState? {
            if (text.isNullOrEmpty()) return null
            val records = text.split(RECORD)
            val header = records.first().split(UNIT)
            val version = header[0].toIntOrNull() ?: return null
            val maskedPackages = when {
                version == 1 && header.size == 5 -> emptyList()
                version == 2 && header.size == 6 ->
                    header[5].split(MASK_SEPARATOR).filter { it.isNotBlank() }
                else -> return null
            }
            val logicalNowMs = header[3].toLongOrNull() ?: return null

            val activities = ArrayList<TrackedActivityState>(records.size - 1)
            for (index in 1 until records.size) {
                val fields = records[index].split(UNIT)
                if (fields.size != 5) return null
                activities += TrackedActivityState(
                    packageName = fields[0],
                    className = fields[1],
                    resumed = fields[2] == "1",
                    sinceMs = fields[3].toLongOrNull() ?: return null,
                    unresolved = fields[4] == "1",
                )
            }
            return TrackerState(
                screenInteractive = header[1] == "1",
                keyguardShown = header[2] == "1",
                logicalNowMs = logicalNowMs,
                lastVisible = header[4] == "1",
                activities = activities,
                maskedPackages = maskedPackages,
            )
        }

        private fun Boolean.asFlag(): String = if (this) "1" else "0"

        /** Package and class names cannot contain these; a corrupt one must not corrupt the row. */
        private fun String.sanitized(): String =
            map { if (it == UNIT || it == RECORD || it == MASK_SEPARATOR) '?' else it }.joinToString("")
    }
}
