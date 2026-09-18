package dev.personal.doomstop.config

/**
 * Instagram messaging-only mode: Direct Messages stay usable while the rest of the app does
 * not, using the same accessibility machinery as the [ShortsGuard] but INVERTED.
 *
 * The Shorts guard detects a BANNED screen (the Shorts player) and backs out of it. This
 * guard detects the ALLOWED screen (the DM inbox or an open thread) and backs out of
 * everything else. The consequences of that inversion, stated honestly:
 *
 *  - Two independent things are built on the one accessibility service:
 *      1. **Metering exemption (always on).** While a DM screen is on top, Instagram is not
 *         charged against the daily allowance. Messaging is free whether or not the limit has
 *         been reached; only scrolling counts. This is why the guard reports its DM state to
 *         the coordinator on every pass, not just once the limit is hit.
 *      2. **The launch timer (only once the limit is reached).** When the allowance is used
 *         up, Instagram is left runnable instead of suspended, and this guard gives a short
 *         grace ([LAUNCH_GRACE_MS]) to reach the inbox. Sit on the feed, reels, explore or a
 *         profile past that grace and the guard presses Back, then Home -- the app closes.
 *         Reaching a DM screen cancels the timer; leaving one restarts it.
 *
 *  - This is BEST-EFFORT, exactly like the Shorts guard, and slightly more fragile because it
 *    is an ALLOW-LIST. Detection keys on the resource IDs of Instagram's own DM views, and
 *    Instagram can rename them in any update. The failure direction is the safe one: if the
 *    DM views stop being recognised the guard OVER-blocks (it boots you out of DMs too) and
 *    stops exempting DM time -- annoying, never a bypass. It never mistakes the feed for a DM
 *    screen, so it cannot accidentally hand back scrolling. Re-measure after a major update.
 *
 *  - No firmer mechanism exists, for the same reasons spelled out in [ShortsGuard]: package
 *    suspension is all-or-nothing per app, and a network filter cannot tell a DM from a feed
 *    load.
 *
 *  - What gives it teeth is [instagramMustBeSuspended]: while the limit is reached and the
 *    guard is NOT running, the whole Instagram app is suspended (the current hard limit).
 *    Turning the guard off does not buy the feed; it costs Instagram entirely.
 *
 *  - Only [TargetPackages.INSTAGRAM] (com.instagram.android) is covered. Instagram Lite is a
 *    different package the guard does not watch, so it stays fully suspended when the limit is
 *    reached, like every other target.
 *
 * Privacy: the service is scoped to the Instagram package, and the only thing ever inspected
 * is whether one of a handful of view IDs is on screen. No messages, names, or anything typed
 * is read, stored or logged.
 */
object InstagramGuard {

    /** Instagram, Meta. The only package this guard watches. */
    const val INSTAGRAM_PACKAGE = TargetPackages.INSTAGRAM

    /**
     * View IDs that identify the Direct Messages inbox or an open DM thread, and are absent
     * from the feed, reels, explore, the camera and profiles.
     *
     * MEASURED on the Pixel 9 (tokay), Instagram 447.0.0.55.81, 2026-09-17. The two DM screens
     * live in different places, so the set covers both:
     *
     *  - The **inbox** is the `direct_tab` pane inside `com.instagram.android.activity`
     *    `.MainTabActivity`. `direct_inbox_action_bar` and
     *    `inbox_refreshable_thread_list_recyclerview` were present in a clean `uiautomator dump`
     *    of the inbox and absent from the same dump of the feed, so accessibility reports them
     *    only while the inbox is actually on screen (MainTabActivity keeps the inbox fragment
     *    alive off-screen, but accessibility prunes it -- confirmed against the feed dump).
     *  - An **open thread** is a separate `com.instagram.modal.ModalActivity`, and that window
     *    is **FLAG_SECURE**: it is black in a screenshot and `uiautomator dump` returns a null
     *    root for it. Its view IDs were read from the app's own view hierarchy instead
     *    (`adb shell dumpsys activity <component>`, which is unaffected by FLAG_SECURE).
     *    `message_list`, `row_thread_composer_edittext`, `direct_thread_header` and
     *    `thread_view_root` are the thread's core views (not the many `*_stub` placeholders).
     *
     * IMPORTANT, still unverified: FLAG_SECURE blocks screenshots, not accessibility (TalkBack
     * reads secure windows), so the bound guard is EXPECTED to see these thread IDs at runtime
     * -- but that has NOT been proven end-to-end on a device, because 0.4.0 cannot be installed
     * over the release-signed device owner on the phone. If a bound AccessibilityService turns
     * out not to receive the secure thread's nodes, the guard would bounce the user from an
     * open thread back to the inbox (which it does recognise). Verify on a throwaway AVD with a
     * test Instagram account, or briefly with TalkBack on the phone, before fully trusting it.
     *
     * Re-measure after a major Instagram update, exactly as for [ShortsGuard]. It is an
     * allow-list, so the failure direction is safe: unrecognised DM views make the guard
     * over-block and stop exempting DM time; it never mistakes the feed for a DM screen.
     */
    val DM_SCREEN_VIEW_IDS: Set<String> = setOf(
        // DM inbox (MainTabActivity, direct_tab) -- validated present on inbox, absent on feed.
        "direct_inbox_action_bar",
        "inbox_refreshable_thread_list_recyclerview",
        // Open DM thread (com.instagram.modal.ModalActivity, FLAG_SECURE).
        "message_list",
        "row_thread_composer_edittext",
        "direct_thread_header",
        "thread_view_root",
    )

    /** How long a launch, or a return to a non-DM screen, is tolerated before backing out. */
    const val LAUNCH_GRACE_MS = 3_000L

    /**
     * How long the guard may be enabled in Settings but not yet bound before it counts as
     * off, mirroring [ShortsGuard.BIND_GRACE_MS]: the system binds accessibility services
     * asynchronously after boot and after an update.
     */
    const val BIND_GRACE_MS = 15_000L

    /** The fully qualified form AccessibilityNodeInfo uses for an Instagram view ID. */
    fun qualified(viewId: String): String = "$INSTAGRAM_PACKAGE:id/$viewId"

    /** True when the visible view IDs, qualified or not, include a DM inbox or thread view. */
    fun isDirectMessages(visibleViewIds: Collection<String>): Boolean =
        visibleViewIds.any { it.removePrefix("$INSTAGRAM_PACKAGE:id/") in DM_SCREEN_VIEW_IDS }

    /**
     * The launch-timer verdict for one inspection of Instagram.
     *
     * Pure and timing-only so it can be unit-tested: the service supplies the elapsed time
     * since a DM screen was last on top (or since Instagram came to the foreground, whichever
     * is later). Nothing happens unless messaging mode is actually active -- under the limit
     * Instagram is ordinary and this guard leaves it completely alone.
     */
    fun shouldBackOut(
        messagingModeActive: Boolean,
        directMessagesOnScreen: Boolean,
        msSinceDirectMessagesAllowed: Long,
    ): Boolean = when {
        !messagingModeActive -> false
        directMessagesOnScreen -> false
        else -> msSinceDirectMessagesAllowed >= LAUNCH_GRACE_MS
    }

    /**
     * The rule that gives the guard teeth, the mirror of [ShortsGuard.youtubeMustBeSuspended].
     *
     * Only consulted while the allowance is exhausted (the coordinator decides that and passes
     * it as [limitReached]); every other reason to suspend targets suspends Instagram outright
     * through the ordinary path. Under the limit Instagram is available. At the limit: a
     * running guard keeps it available in DM-only mode; a guard switched off in Settings
     * suspends it at once; a guard enabled but not yet bound gets [BIND_GRACE_MS] and no more,
     * which also covers a guard that keeps crashing.
     */
    fun instagramMustBeSuspended(
        limitReached: Boolean,
        enabledInSettings: Boolean,
        connected: Boolean,
        notConnectedForMs: Long,
    ): Boolean = when {
        !limitReached -> false
        connected -> false
        !enabledInSettings -> true
        else -> notConnectedForMs >= BIND_GRACE_MS
    }
}
