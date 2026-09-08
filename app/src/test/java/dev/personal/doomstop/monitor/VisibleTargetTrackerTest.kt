package dev.personal.doomstop.monitor

import dev.personal.doomstop.config.TargetPackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleTargetTrackerTest {

    private val targets = TargetPackages.ALL
    private val instagram = TargetPackages.INSTAGRAM
    private val reddit = TargetPackages.REDDIT
    private val launcher = "com.google.android.apps.nexuslauncher"

    private fun tracker(graceMs: Long = VisibleTargetTracker.DEFAULT_PAUSED_GRACE_MS) =
        VisibleTargetTracker(targets, graceMs)

    private fun event(
        t: Long,
        pkg: String,
        type: TrackedEventType,
        cls: String = "$pkg.MainActivity",
    ) = TrackedEvent(t, pkg, cls, type)

    @Test
    fun `opening a target makes it visible`() {
        val tracker = tracker()
        val transitions = tracker.apply(listOf(event(1_000, instagram, TrackedEventType.ACTIVITY_RESUMED)), 2_000)
        assertTrue(tracker.isVisible)
        assertEquals(1, transitions.size)
        assertTrue(transitions[0].visible)
        assertEquals(1_000L, transitions[0].atWallMs)
    }

    @Test
    fun `a non-target app is never visible`() {
        val tracker = tracker()
        tracker.apply(listOf(event(1_000, launcher, TrackedEventType.ACTIVITY_RESUMED)), 2_000)
        assertFalse(tracker.isVisible)
    }

    @Test
    fun `leaving the app stops counting when it is stopped`() {
        val tracker = tracker()
        tracker.apply(listOf(event(1_000, instagram, TrackedEventType.ACTIVITY_RESUMED)), 1_500)
        val transitions = tracker.apply(
            listOf(
                event(2_000, instagram, TrackedEventType.ACTIVITY_PAUSED),
                event(2_300, instagram, TrackedEventType.ACTIVITY_STOPPED),
            ),
            3_000,
        )
        assertFalse(tracker.isVisible)
        assertEquals(1, transitions.size)
        assertEquals(2_300L, transitions[0].atWallMs)
    }

    @Test
    fun `a paused activity keeps counting until the grace expires`() {
        val tracker = tracker(graceMs = 5_000)
        tracker.apply(listOf(event(1_000, instagram, TrackedEventType.ACTIVITY_RESUMED)), 1_500)
        // Paused and never stopped, as happens in picture-in-picture.
        tracker.apply(listOf(event(2_000, instagram, TrackedEventType.ACTIVITY_PAUSED)), 3_000)
        assertTrue("still visible inside the grace", tracker.isVisible)

        val transitions = tracker.apply(emptyList(), 9_000)
        assertFalse("grace expired", tracker.isVisible)
        assertEquals(1, transitions.size)
        assertEquals("visibility drops exactly at the grace deadline", 7_000L, transitions[0].atWallMs)
    }

    @Test
    fun `a stopped event for an older instance of the same class does not blind the tracker`() {
        // The platform guarantees onPause before onStop, so a STOPPED arriving while this
        // key is RESUMED belongs to a previous instance. Without instance IDs this rule is
        // the only thing preventing a lost session.
        val tracker = tracker()
        tracker.apply(
            listOf(
                event(1_000, instagram, TrackedEventType.ACTIVITY_RESUMED),
                event(2_000, instagram, TrackedEventType.ACTIVITY_PAUSED),
                event(2_100, instagram, TrackedEventType.ACTIVITY_RESUMED), // the new instance
                event(2_200, instagram, TrackedEventType.ACTIVITY_STOPPED), // the old one
            ),
            3_000,
        )
        assertTrue(tracker.isVisible)
    }

    @Test
    fun `switching between two targets stays continuously visible`() {
        val tracker = tracker()
        val transitions = tracker.apply(
            listOf(
                event(1_000, instagram, TrackedEventType.ACTIVITY_RESUMED),
                event(2_000, instagram, TrackedEventType.ACTIVITY_PAUSED),
                event(2_050, reddit, TrackedEventType.ACTIVITY_RESUMED),
                event(2_400, instagram, TrackedEventType.ACTIVITY_STOPPED),
            ),
            3_000,
        )
        assertTrue(tracker.isVisible)
        assertEquals("one transition into visible, none out", 1, transitions.size)
    }

    @Test
    fun `split screen keeps counting once, not twice`() {
        val tracker = tracker()
        val transitions = tracker.apply(
            listOf(
                event(1_000, instagram, TrackedEventType.ACTIVITY_RESUMED),
                event(1_200, reddit, TrackedEventType.ACTIVITY_RESUMED), // multi-resume
            ),
            2_000,
        )
        assertTrue(tracker.isVisible)
        assertEquals(1, transitions.count { it.visible })
    }

    @Test
    fun `screen off stops counting immediately`() {
        val tracker = tracker()
        tracker.apply(listOf(event(1_000, instagram, TrackedEventType.ACTIVITY_RESUMED)), 1_500)
        val transitions = tracker.apply(
            listOf(event(2_000, "android", TrackedEventType.SCREEN_NON_INTERACTIVE)),
            3_000,
        )
        assertFalse(tracker.isVisible)
        assertEquals(2_000L, transitions.single().atWallMs)
    }

    @Test
    fun `a locked keyguard stops counting even with the screen on`() {
        val tracker = tracker()
        tracker.apply(listOf(event(1_000, instagram, TrackedEventType.ACTIVITY_RESUMED)), 1_500)
        tracker.apply(listOf(event(2_000, "android", TrackedEventType.KEYGUARD_SHOWN)), 2_500)
        assertFalse(tracker.isVisible)
        tracker.apply(listOf(event(3_000, "android", TrackedEventType.KEYGUARD_HIDDEN)), 3_500)
        assertTrue(tracker.isVisible)
    }

    @Test
    fun `live screen state overrides the event stream`() {
        val tracker = tracker()
        tracker.apply(listOf(event(1_000, instagram, TrackedEventType.ACTIVITY_RESUMED)), 1_500)
        assertTrue(tracker.isVisible)
        val transitions = tracker.observeScreenState(interactive = false, keyguardLocked = true, atWallMs = 2_000)
        assertFalse(tracker.isVisible)
        assertEquals(1, transitions.size)
    }

    @Test
    fun `device shutdown clears visibility`() {
        val tracker = tracker()
        tracker.apply(listOf(event(1_000, instagram, TrackedEventType.ACTIVITY_RESUMED)), 1_500)
        tracker.apply(listOf(event(2_000, "android", TrackedEventType.DEVICE_SHUTDOWN)), 2_500)
        assertFalse(tracker.isVisible)
    }

    @Test
    fun `replaying an overlapping window leaves the same final state`() {
        // De-duplication is the reader's job, so the tracker may legitimately see an event
        // twice. What must hold is that a replay cannot leave visibility in a different
        // state, and cannot manufacture a net change; the engine separately ignores
        // transitions that do not alter the state, so no time is double-charged.
        val tracker = tracker()
        val batch = listOf(
            event(1_000, instagram, TrackedEventType.ACTIVITY_RESUMED),
            event(2_000, instagram, TrackedEventType.ACTIVITY_PAUSED),
            event(2_200, instagram, TrackedEventType.ACTIVITY_STOPPED),
        )
        val first = tracker.apply(batch, 3_000)
        assertEquals(2, first.size)
        assertFalse(tracker.isVisible)

        val replayed = tracker.apply(batch, 3_000)
        assertFalse("state is unchanged by the replay", tracker.isVisible)
        assertEquals("a replay nets out to no change", 0, replayed.sumOf { if (it.visible) 1 else -1 })
    }

    @Test
    fun `out-of-order events do not rewind the tracker`() {
        val tracker = tracker()
        tracker.apply(listOf(event(5_000, instagram, TrackedEventType.ACTIVITY_RESUMED)), 6_000)
        val transitions = tracker.apply(listOf(event(1_000, instagram, TrackedEventType.ACTIVITY_STOPPED)), 6_100)
        assertTrue(tracker.isVisible)
        assertTrue(transitions.isEmpty())
    }

    @Test
    fun `the snapshot describes what the tracker believes`() {
        val tracker = tracker()
        tracker.apply(listOf(event(1_000, instagram, TrackedEventType.ACTIVITY_RESUMED)), 4_000)
        val snapshot = tracker.snapshot()
        assertTrue(snapshot.visible)
        assertEquals(1, snapshot.activities.size)
        assertEquals(instagram, snapshot.activities[0].packageName)
        assertEquals("resumed", snapshot.activities[0].state)
        assertEquals(3_000L, snapshot.activities[0].ageMs)
    }
}
