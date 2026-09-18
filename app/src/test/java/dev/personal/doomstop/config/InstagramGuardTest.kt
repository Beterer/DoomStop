package dev.personal.doomstop.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules plus measured fixtures. The device-captured sets below come from the Pixel 9 (tokay),
 * Instagram 447.0.0.55.81, 2026-09-17 -- the inbox and feed from a clean `uiautomator dump`
 * (accessibility-visible IDs), the thread from the app's own view hierarchy because its window
 * is FLAG_SECURE and `uiautomator dump` returns a null root for it. See
 * [InstagramGuard.DM_SCREEN_VIEW_IDS] for the capture detail. These pin that the guard's
 * allow-list fires on both DM screens and stays silent on the feed (including the feed's
 * `direct_tab` bottom-nav icon, which must NOT be read as "in messages").
 */
class InstagramGuardTest {

    // -- measured device fixtures (Pixel 9, IG 447.0.0.55.81, 2026-09-17) ----------------------

    /** Accessibility-visible IDs on the DM inbox (a representative subset of the real dump). */
    private val dmInboxViewIds = listOf(
        "action_bar_root", "direct_inbox_action_bar", "inbox_refreshable_thread_list_recyclerview",
        "direct_tab", "feed_tab", "profile_tab", "tab_bar", "avatar",
    )

    /** IDs on an open DM thread (from the FLAG_SECURE thread's own view hierarchy). */
    private val dmThreadViewIds = listOf(
        "thread_view_root", "direct_thread_header", "message_list", "message_list_refresh_container",
        "row_thread_composer_container", "row_thread_composer_edittext", "row_thread_composer_button_send",
    )

    /** Accessibility-visible IDs on the home feed (a representative subset of the real dump). */
    private val feedViewIds = listOf(
        "main_feed_action_bar", "feed_tab", "clips_tab", "search_tab", "direct_tab", "profile_tab",
        "reels_tray_container", "row_feed_photo_profile_name", "tab_bar", "refreshable_container",
    )

    @Test
    fun theInboxIsRecognisedAsDirectMessages() {
        assertTrue(InstagramGuard.isDirectMessages(dmInboxViewIds))
        assertTrue(InstagramGuard.isDirectMessages(dmInboxViewIds.map { InstagramGuard.qualified(it) }))
    }

    @Test
    fun anOpenThreadIsRecognisedAsDirectMessages() {
        assertTrue(InstagramGuard.isDirectMessages(dmThreadViewIds))
        assertTrue(InstagramGuard.isDirectMessages(dmThreadViewIds.map { InstagramGuard.qualified(it) }))
    }

    @Test
    fun theFeedIsNotDirectMessagesEvenThoughItCarriesTheDirectTabIcon() {
        // direct_tab is the messages icon in the bottom nav; it is present on the feed and must
        // never be treated as being IN messages. The guard keys on inbox/thread views, not the
        // nav entry, so the feed reads as not-DMs.
        assertFalse(InstagramGuard.isDirectMessages(feedViewIds))
        assertTrue("direct_tab" in feedViewIds)
    }

    // -- allow-list matching (synthetic IDs) --------------------------------------------------

    @Test
    fun aDmViewIsRecognised() {
        val dmView = InstagramGuard.DM_SCREEN_VIEW_IDS.first()
        assertTrue(InstagramGuard.isDirectMessages(listOf(dmView, "some_feed_view")))
    }

    @Test
    fun screensWithoutAnyDmViewAreNotRecognised() {
        assertFalse(InstagramGuard.isDirectMessages(listOf("feed_recycler", "reels_root", "profile_header")))
    }

    @Test
    fun anEmptyScreenIsNotDirectMessages() {
        assertFalse(InstagramGuard.isDirectMessages(emptyList()))
    }

    @Test
    fun qualifiedIdsAreRecognisedTheWayAccessibilityReportsThem() {
        val id = InstagramGuard.DM_SCREEN_VIEW_IDS.first()
        assertEquals("com.instagram.android:id/$id", InstagramGuard.qualified(id))
        assertTrue(InstagramGuard.isDirectMessages(listOf(InstagramGuard.qualified(id))))
        // Same short id under a different package must not match.
        assertFalse(InstagramGuard.isDirectMessages(listOf("com.example.other:id/$id")))
    }

    @Test
    fun anyOneDmMarkerIsEnoughSoASingleRenameDoesNotDisableDetection() {
        for (marker in InstagramGuard.DM_SCREEN_VIEW_IDS) {
            assertTrue(marker, InstagramGuard.isDirectMessages(listOf(marker)))
        }
    }

    // -- the launch timer ---------------------------------------------------------------------

    @Test
    fun underTheLimitTheGuardNeverActs() {
        // messaging mode inactive: even far past the grace with no DM on screen, do nothing.
        assertFalse(
            InstagramGuard.shouldBackOut(
                messagingModeActive = false,
                directMessagesOnScreen = false,
                msSinceDirectMessagesAllowed = 10 * InstagramGuard.LAUNCH_GRACE_MS,
            ),
        )
    }

    @Test
    fun beingInDirectMessagesIsAlwaysAllowed() {
        assertFalse(
            InstagramGuard.shouldBackOut(
                messagingModeActive = true,
                directMessagesOnScreen = true,
                msSinceDirectMessagesAllowed = 10 * InstagramGuard.LAUNCH_GRACE_MS,
            ),
        )
    }

    @Test
    fun theGraceIsHonouredThenTheGuardBacksOut() {
        val grace = InstagramGuard.LAUNCH_GRACE_MS
        assertFalse(
            InstagramGuard.shouldBackOut(
                messagingModeActive = true,
                directMessagesOnScreen = false,
                msSinceDirectMessagesAllowed = grace - 1,
            ),
        )
        assertTrue(
            InstagramGuard.shouldBackOut(
                messagingModeActive = true,
                directMessagesOnScreen = false,
                msSinceDirectMessagesAllowed = grace,
            ),
        )
    }

    // -- the teeth: guard off at the limit means Instagram suspended --------------------------

    @Test
    fun underTheLimitInstagramIsNeverSuspendedByThisRule() {
        assertFalse(
            InstagramGuard.instagramMustBeSuspended(
                limitReached = false,
                enabledInSettings = false,
                connected = false,
                notConnectedForMs = 1_000_000,
            ),
        )
    }

    @Test
    fun aRunningGuardKeepsInstagramAvailableAtTheLimit() {
        assertFalse(
            InstagramGuard.instagramMustBeSuspended(
                limitReached = true,
                enabledInSettings = true,
                connected = true,
                notConnectedForMs = 0,
            ),
        )
    }

    @Test
    fun switchedOffInSettingsSuspendsAtOnceAtTheLimit() {
        assertTrue(
            InstagramGuard.instagramMustBeSuspended(
                limitReached = true,
                enabledInSettings = false,
                connected = false,
                notConnectedForMs = 0,
            ),
        )
    }

    @Test
    fun switchedOnButNotYetBoundGetsTheGraceAndNoMore() {
        val grace = InstagramGuard.BIND_GRACE_MS
        assertFalse(
            InstagramGuard.instagramMustBeSuspended(
                limitReached = true,
                enabledInSettings = true,
                connected = false,
                notConnectedForMs = grace - 1,
            ),
        )
        assertTrue(
            InstagramGuard.instagramMustBeSuspended(
                limitReached = true,
                enabledInSettings = true,
                connected = false,
                notConnectedForMs = grace,
            ),
        )
    }
}
