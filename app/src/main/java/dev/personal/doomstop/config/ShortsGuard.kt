package dev.personal.doomstop.config

/**
 * YouTube Shorts use the shared allowance; ordinary YouTube stays unmetered.
 *
 * An accessibility service ([dev.personal.doomstop.monitor.ShortsGuardService]) watches only
 * the YouTube app, reports Shorts visibility and exits Shorts when the allowance is unavailable.
 *
 *  - This is BEST-EFFORT, not a hard limit. Detection keys on the resource IDs of YouTube's
 *    own views, and YouTube can rename them in any update; when that happens the guard stops
 *    recognising Shorts without reporting any error. Re-measure after a major YouTube update.
 *  - There is no firmer mechanism available. Device-owner suspension is per package, so it
 *    cannot remove one screen from an app. A network filter cannot tell Shorts from ordinary
 *    videos either: both use the same hosts, and the API path that differs is inside TLS.
 *    Reading it would need a system certificate, which needs root, which the plan forbids
 *    and which would let anyone switch this app off anyway.
 *  - If the guard stops, YouTube remains available. Shorts cannot then be recognised,
 *    metered or closed. This is an intentional availability choice.
 *  - A patched or differently packaged YouTube client is not covered.
 *  - Chrome is covered only partly, by the `youtube.com/shorts` entry in
 *    [BlockedSites.PATH_FILTERS]: a Shorts link is blocked, a Short reached by tapping around
 *    inside YouTube's website is not. The guard deliberately does not watch Chrome.
 */
object ShortsGuard {

    /** YouTube, Google. Preinstalled on the target Pixel 9. */
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"

    /**
     * View IDs present in the Shorts player and absent everywhere else. Measured on the
     * target Pixel 9 with YouTube 21.36.45 on 2026-09-11, by dumping the accessibility tree
     * of a Short playing, the home feed, and the ordinary watch player. Any one of them is
     * enough, so a single rename does not disable detection on its own.
     *
     * `reel_time_bar` is deliberately NOT here: despite the name it was present on the home
     * feed and in the ordinary player as well.
     */
    val SHORTS_PLAYER_VIEW_IDS: Set<String> = setOf(
        "reel_watch_player",
        "reel_recycler",
        "reel_watch_fragment_root",
        "reel_player_page_container",
    )

    /** The fully qualified form AccessibilityNodeInfo uses for a YouTube view ID. */
    fun qualified(viewId: String): String = "$YOUTUBE_PACKAGE:id/$viewId"

    /** True when the visible view IDs, qualified or not, include the Shorts player. */
    fun isShortsPlayer(visibleViewIds: Collection<String>): Boolean =
        visibleViewIds.any { it.removePrefix("$YOUTUBE_PACKAGE:id/") in SHORTS_PLAYER_VIEW_IDS }

    /** Only recognised Shorts are closed, and only while the shared allowance is unavailable. */
    fun shouldBackOut(blockingShorts: Boolean, shortsVisible: Boolean): Boolean =
        blockingShorts && shortsVisible
}
