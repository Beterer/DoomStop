package dev.personal.doomstop.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixtures are the YouTube view IDs actually on screen on the target Pixel 9, YouTube
 * 21.36.45, 2026-09-11, taken with `uiautomator dump` (which reads the same accessibility
 * tree the guard does). IDs only: no screen text was kept.
 */
class ShortsGuardTest {

    private val shortPlaying = listOf(
        "accessibility_layer_container", "action_bar_root", "appbar_layout", "bottom_bar_container",
        "browse_fragment_layout_coordinator_layout", "fab_container", "fragment_container_view",
        "global_slim_status_bar_container", "global_status_bar_view", "image", "more_drawer_container",
        "navigation_bar_divider_frame", "nerd_stats_container", "new_content_dot",
        "next_gen_watch_container_layout", "pane_fragment_container", "pane_fragment_contents",
        "pivot_bar", "pivot_bar_thumbnail", "player_overlay", "reel_player_footer_container",
        "reel_player_overlay_container", "reel_player_overlay_root", "reel_player_page_container",
        "reel_player_page_content", "reel_player_underlay", "reel_recycler",
        "reel_scrim_shorts_while_bottom_gradient", "reel_scrim_shorts_while_top", "reel_time_bar",
        "reel_video_interactions", "reel_watch_fragment_root", "reel_watch_player",
        "reel_watch_refresher", "slim_status_bar_player_container", "text", "thumbnail_layout",
        "toolbar", "toolbar_container", "watch_while_layout_coordinator_layout",
    )

    /** Home feed, with the miniplayer showing. */
    private val homeFeed = listOf(
        "accessibility_layer_container", "action_bar_root", "appbar_layout", "badge_icon_text",
        "bottom_bar_container", "browse_fragment_layout_coordinator_layout", "chip_cloud_chip_modern_text",
        "fab_container", "filter_bar", "floaty_bar_controls_view", "floaty_bar_time_bar_view",
        "global_slim_status_bar_container", "global_status_bar_view", "image", "loading_layout",
        "menu_item_0", "menu_item_1", "menu_item_2", "menu_item_view", "modern_miniplayer_badge_icon",
        "modern_miniplayer_close", "modern_miniplayer_continue_watching_premium",
        "modern_miniplayer_overlay_action_button", "modern_miniplayer_subtitle_bar",
        "modern_miniplayer_subtitle_text", "more_drawer_container", "navigation_bar_divider_frame",
        "new_content_count", "new_content_dot", "next_gen_watch_container_layout",
        "next_gen_watch_layout_no_player_fragment_container", "pane_fragment_background_container",
        "pane_fragment_container", "pane_fragment_contents", "pivot_bar", "pivot_bar_thumbnail",
        "player_fragment_container", "player_overlays", "player_overlays_custom_views_container",
        "player_view", "playerless_miniplayer_controls_view", "playerless_thumbnail", "reel_time_bar",
        "results", "slim_status_bar_player_container", "text", "thumbnail_layout", "toolbar",
        "toolbar_container", "watch_player", "watch_while_layout_coordinator_layout",
        "watch_while_time_bar_view", "youtube_logo",
    )

    /** The ordinary watch player, here showing a live stream with its chat open. */
    private val ordinaryPlayer = listOf(
        "accessibility_layer_container", "action_bar_root", "action_button", "arrow_drag_handle",
        "cinematic_image_background", "cinematic_scrim", "close_button", "conversation_list",
        "creator_goal_animation_view", "edit_text", "edit_text_container", "emoji_picker_icon",
        "empty_state_message", "engagement_panel", "engagement_panel_wrapper",
        "global_slim_status_bar_container", "header_container", "inline_extra_buttons",
        "inline_extra_buttons_container", "input_panel", "inset_controls_overlay_wrapper",
        "inset_overlay_view_layout", "live_chat_action_panel", "live_chat_content",
        "live_chat_drawer_header_shadow", "live_chat_text_field_bar", "live_ephemeral_widget_overlay",
        "modern_subtitle", "modern_title", "more_comments_icon", "more_comments_icon_container",
        "more_drawer_container", "navigation_bar_divider_frame", "next_gen_watch_container_layout",
        "next_gen_watch_layout_no_player_fragment_container", "panel_content",
        "panel_content_touch_wrapper", "panel_header", "panel_header_bottom_border",
        "panel_header_wrapper", "player_fragment_container", "player_overlays",
        "player_overlays_custom_views_container", "player_view", "reaction_control_panel_overlay",
        "reel_time_bar", "scrim", "scrim_view", "slim_status_bar_player_container",
        "thumbnail_and_emoji_picker_container", "title_container", "video_metadata_layout",
        "watch_cinematic_background", "watch_panel", "watch_panel_scrim", "watch_player",
        "watch_status_bar_view", "watch_while_time_bar_view", "watch_while_time_bar_view_overlay",
        "youtube_controls_overlay",
    )

    @Test
    fun aShortPlayingIsRecognised() {
        assertTrue(ShortsGuard.isShortsPlayer(shortPlaying))
    }

    @Test
    fun theHomeFeedIsLeftAlone() {
        assertFalse(ShortsGuard.isShortsPlayer(homeFeed))
    }

    @Test
    fun theOrdinaryPlayerIsLeftAlone() {
        assertFalse(ShortsGuard.isShortsPlayer(ordinaryPlayer))
    }

    @Test
    fun everyMarkerWasMeasuredOnTheShortsScreenAndNowhereElse() {
        for (marker in ShortsGuard.SHORTS_PLAYER_VIEW_IDS) {
            assertTrue("$marker was not on the Shorts screen", marker in shortPlaying)
            assertFalse("$marker is on the home feed", marker in homeFeed)
            assertFalse("$marker is in the ordinary player", marker in ordinaryPlayer)
        }
    }

    @Test
    fun anyOneMarkerIsEnoughSoASingleRenameDoesNotDisableDetection() {
        for (marker in ShortsGuard.SHORTS_PLAYER_VIEW_IDS) {
            assertTrue(marker, ShortsGuard.isShortsPlayer(listOf(marker)))
        }
    }

    @Test
    fun theTimeBarIsNotAMarkerDespiteItsName() {
        // Measured on all three screens, so keying on it would close ordinary videos.
        assertTrue("reel_time_bar" in homeFeed && "reel_time_bar" in ordinaryPlayer)
        assertFalse(ShortsGuard.isShortsPlayer(listOf("reel_time_bar")))
    }

    @Test
    fun qualifiedIdsAreRecognisedTheWayAccessibilityReportsThem() {
        assertEquals("com.google.android.youtube:id/reel_recycler", ShortsGuard.qualified("reel_recycler"))
        assertTrue(ShortsGuard.isShortsPlayer(listOf(ShortsGuard.qualified("reel_watch_player"))))
        assertFalse(ShortsGuard.isShortsPlayer(listOf("com.example.other:id/reel_watch_player")))
    }

    // -- the rule: guard off means YouTube suspended ------------------------------------------

    @Test
    fun nothingIsSuspendedBeforeSetupOrDuringMaintenance() {
        assertFalse(ShortsGuard.youtubeMustBeSuspended(false, enabledInSettings = false, connected = false, notConnectedForMs = 1_000_000))
    }

    @Test
    fun aRunningGuardKeepsYouTubeAvailable() {
        assertFalse(ShortsGuard.youtubeMustBeSuspended(true, enabledInSettings = true, connected = true, notConnectedForMs = 0))
    }

    @Test
    fun switchedOffInSettingsSuspendsAtOnce() {
        assertTrue(ShortsGuard.youtubeMustBeSuspended(true, enabledInSettings = false, connected = false, notConnectedForMs = 0))
    }

    @Test
    fun switchedOnButNotYetBoundGetsTheGraceAndNoMore() {
        val grace = ShortsGuard.BIND_GRACE_MS
        assertFalse(ShortsGuard.youtubeMustBeSuspended(true, enabledInSettings = true, connected = false, notConnectedForMs = grace - 1))
        assertTrue(ShortsGuard.youtubeMustBeSuspended(true, enabledInSettings = true, connected = false, notConnectedForMs = grace))
    }

    @Test
    fun theChromePathFilterIsInTheBlocklistAlongsideTheHosts() {
        assertTrue("youtube.com/shorts" in BlockedSites.FILTERS)
        assertTrue(BlockedSites.FILTERS.containsAll(BlockedSites.HOSTS))
        // A leading dot would restrict a filter to the exact host and miss m.youtube.com.
        assertTrue(BlockedSites.FILTERS.none { it.startsWith(".") || it.contains("://") })
    }
}
