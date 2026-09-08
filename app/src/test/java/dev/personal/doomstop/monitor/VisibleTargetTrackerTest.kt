package dev.personal.doomstop.monitor

import dev.personal.doomstop.config.TargetPackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleTargetTrackerTest {

    private val targets = TargetPackages.ALL
    private val instagram = TargetPackages.INSTAGRAM
    private val reddit = TargetPackages.REDDIT
    private val launcher = "com.google.android.apps.nexuslauncher"

    private fun tracker(pausedVisibleMs: Long = VisibleTargetTracker.DEFAULT_PAUSED_VISIBLE_MS) =
        VisibleTargetTracker(targets, pausedVisibleMs)

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

    // -- paused-but-visible, i.e. picture-in-picture -------------------------------------

    @Test
    fun `a paused activity keeps being metered well past the old ninety-second cut-off`() {
        // The defect: after ninety seconds the tracker silently decided "not visible", so a
        // target that was genuinely still on screen stopped costing anything.
        val tracker = tracker()
        tracker.apply(listOf(event(1_000, instagram, TrackedEventType.ACTIVITY_RESUMED)), 1_500)
        tracker.apply(listOf(event(2_000, instagram, TrackedEventType.ACTIVITY_PAUSED)), 3_000)

        tracker.apply(emptyList(), 2_000 + 91_000)
        assertTrue("still metered two minutes into a paused-and-visible session", tracker.isVisible)

        tracker.apply(emptyList(), 2_000 + 5 * 60_000)
        assertTrue("and five minutes in", tracker.isVisible)
    }

    @Test
    fun `a paused activity that never stops becomes unresolved rather than free`() {
        val tracker = tracker(pausedVisibleMs = 5_000)
        tracker.apply(listOf(event(1_000, instagram, TrackedEventType.ACTIVITY_RESUMED)), 1_500)
        tracker.apply(listOf(event(2_000, instagram, TrackedEventType.ACTIVITY_PAUSED)), 3_000)
        assertTrue(tracker.isVisible)
        assertFalse(tracker.hasUnresolved)

        val transitions = tracker.apply(emptyList(), 9_000)
        assertFalse(tracker.isVisible)
        assertTrue("the state is declared unresolved, not decided", tracker.hasUnresolved)
        assertEquals(1, transitions.size)
        assertEquals("visibility drops exactly at the deadline", 7_000L, transitions[0].atWallMs)
    }

    @Test
    fun `turning the screen off clears a paused activity, so a lost stop event self-heals`() {
        val tracker = tracker(pausedVisibleMs = 5_000)
        tracker.apply(listOf(event(1_000, instagram, TrackedEventType.ACTIVITY_RESUMED)), 1_500)
        // Paused, and the STOPPED never arrives.
        tracker.apply(listOf(event(2_000, instagram, TrackedEventType.ACTIVITY_PAUSED)), 2_500)
        tracker.apply(listOf(event(3_000, "android", TrackedEventType.SCREEN_NON_INTERACTIVE)), 3_500)
        assertFalse(tracker.isVisible)

        tracker.apply(listOf(event(4_000, "android", TrackedEventType.SCREEN_INTERACTIVE)), 20_000)
        assertFalse("the phantom did not come back with the screen", tracker.isVisible)
        assertFalse("and nothing is left unresolved", tracker.hasUnresolved)
    }

    @Test
    fun `a resumed activity survives the screen going off and on`() {
        val tracker = tracker()
        tracker.apply(listOf(event(1_000, instagram, TrackedEventType.ACTIVITY_RESUMED)), 1_500)
        tracker.apply(listOf(event(2_000, "android", TrackedEventType.SCREEN_NON_INTERACTIVE)), 2_500)
        assertFalse(tracker.isVisible)
        tracker.apply(listOf(event(3_000, "android", TrackedEventType.SCREEN_INTERACTIVE)), 3_500)
        assertTrue(tracker.isVisible)
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

    // -- surviving a restart ---------------------------------------------------------------

    @Test
    fun `an app left open across a restart keeps being metered`() {
        // The defect this defends: a new process built an EMPTY observer while the durable
        // checkpoint said a target was on screen, so the rest of that same session was free.
        val before = tracker()
        before.apply(listOf(event(1_000, instagram, TrackedEventType.ACTIVITY_RESUMED)), 2_000)
        assertTrue(before.isVisible)

        val encoded = before.exportState().encode()
        val after = tracker()
        after.restore(requireNotNull(TrackerState.decode(encoded)))

        assertTrue("the restarted observer knows the app is still open", after.isVisible)
        val transitions = after.apply(emptyList(), 10_000)
        assertTrue("and no phantom transition is emitted", transitions.isEmpty())
        assertTrue(after.isVisible)
    }

    @Test
    fun `a restart followed by leaving the app stops metering at the right instant`() {
        val before = tracker()
        before.apply(listOf(event(1_000, instagram, TrackedEventType.ACTIVITY_RESUMED)), 2_000)
        val after = tracker()
        after.restore(requireNotNull(TrackerState.decode(before.exportState().encode())))

        val transitions = after.apply(
            listOf(
                event(4_000, instagram, TrackedEventType.ACTIVITY_PAUSED),
                event(4_200, instagram, TrackedEventType.ACTIVITY_STOPPED),
            ),
            5_000,
        )
        assertFalse(after.isVisible)
        assertEquals(1, transitions.size)
        assertEquals(4_200L, transitions.single().atWallMs)
    }

    @Test
    fun `a restart while the phone is locked does not start metering`() {
        val before = tracker()
        before.apply(listOf(event(1_000, instagram, TrackedEventType.ACTIVITY_RESUMED)), 1_500)
        before.observeScreenState(interactive = false, keyguardLocked = true, atWallMs = 2_000)

        val after = tracker()
        after.restore(requireNotNull(TrackerState.decode(before.exportState().encode())))
        assertFalse(after.isVisible)
    }

    @Test
    fun `an unresolved paused activity survives a restart as unresolved`() {
        val before = tracker(pausedVisibleMs = 5_000)
        before.apply(listOf(event(1_000, instagram, TrackedEventType.ACTIVITY_RESUMED)), 1_500)
        before.apply(listOf(event(2_000, instagram, TrackedEventType.ACTIVITY_PAUSED)), 9_000)
        assertTrue(before.hasUnresolved)

        val after = tracker(pausedVisibleMs = 5_000)
        after.restore(requireNotNull(TrackerState.decode(before.exportState().encode())))
        assertTrue(after.hasUnresolved)
        assertFalse(after.isVisible)
    }

    @Test
    fun `observer state round-trips through its encoding`() {
        val tracker = tracker()
        tracker.apply(
            listOf(
                event(1_000, instagram, TrackedEventType.ACTIVITY_RESUMED),
                event(1_500, reddit, TrackedEventType.ACTIVITY_RESUMED),
                event(2_000, reddit, TrackedEventType.ACTIVITY_PAUSED),
            ),
            2_500,
        )
        val state = tracker.exportState()
        assertEquals(state, TrackerState.decode(state.encode()))
    }

    @Test
    fun `unreadable observer state decodes to nothing rather than to something wrong`() {
        assertNull(TrackerState.decode(null))
        assertNull(TrackerState.decode(""))
        assertNull(TrackerState.decode("not a serialized observer"))
        // A version tag this build does not understand is refused rather than half-read.
        val fromTheFuture = "9" + TrackerState(true, false, 5_000, true, emptyList()).encode()
        assertNull(TrackerState.decode(fromTheFuture))
    }
}
