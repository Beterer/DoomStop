package dev.personal.doomstop.config

/**
 * YouTube Shorts, blocked inside the YouTube app while ordinary videos stay available.
 *
 * An accessibility service ([dev.personal.doomstop.monitor.ShortsGuardService]) watches only
 * the YouTube app and backs out of the Shorts player when it appears. Scope, stated honestly:
 *
 *  - This is BEST-EFFORT, not a hard limit. Detection keys on the resource IDs of YouTube's
 *    own views, and YouTube can rename them in any update; when that happens the guard stops
 *    recognising Shorts without reporting any error. Re-measure after a major YouTube update.
 *  - There is no firmer mechanism available. Device-owner suspension is per package, so it
 *    cannot remove one screen from an app. A network filter cannot tell Shorts from ordinary
 *    videos either: both use the same hosts, and the API path that differs is inside TLS.
 *    Reading it would need a system certificate, which needs root, which the plan forbids
 *    and which would let anyone switch this app off anyway.
 *  - What gives the guard teeth is [youtubeMustBeSuspended]: while it is not running, the
 *    whole YouTube app is suspended. Switching the guard off does not buy Shorts; it costs
 *    YouTube.
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

    /**
     * How long the guard may be switched on in Settings but not yet connected before it is
     * treated as off. The system binds accessibility services asynchronously, after boot and
     * after an update; without this allowance YouTube would be suspended and released again
     * on every restart of this app.
     */
    const val BIND_GRACE_MS = 15_000L

    /**
     * The rule that makes the guard worth having. Switched off in Settings means suspended
     * at once; switched on but not connected means suspended once the grace has run out,
     * which also covers a guard that keeps crashing.
     */
    fun youtubeMustBeSuspended(
        enforcing: Boolean,
        enabledInSettings: Boolean,
        connected: Boolean,
        notConnectedForMs: Long,
    ): Boolean = when {
        !enforcing -> false
        connected -> false
        !enabledInSettings -> true
        else -> notConnectedForMs >= BIND_GRACE_MS
    }
}
