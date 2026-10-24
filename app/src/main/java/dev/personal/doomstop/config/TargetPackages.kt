package dev.personal.doomstop.config

import dev.personal.doomstop.domain.LimiterSettings

/**
 * The social-media applications metered against the shared daily allowance.
 *
 * Package IDs only. Never display names, never substring matching: the target Pixel has
 * `net.zedge.android` (Zedge, a wallpaper app) installed, which contains the substring
 * "edge" and would be caught by any brand-name heuristic. Every ID below is either
 * verified present on the target device or is a documented official variant of the same
 * service from the same publisher.
 */
object TargetPackages {

    /** Instagram, Meta. Verified installed on the target Pixel 9. */
    const val INSTAGRAM = "com.instagram.android"

    /** TikTok, ByteDance -- the global "musical.ly" lineage ID. Verified installed. */
    const val TIKTOK = "com.zhiliaoapp.musically"

    /** Reddit, Reddit Inc. Verified installed on the target Pixel 9. */
    const val REDDIT = "com.reddit.frontpage"

    /**
     * Official variants of the same three services, published by the same vendors under
     * different application IDs in some regions or as "Lite" builds. None were installed
     * on the target device at inventory time; they are metered pre-emptively so that
     * installing one is not a bypass. Listing an ID that is never installed has no
     * effect, but a wrong ID here would suspend an unrelated app, so this set stays short.
     */
    /** Built-in defaults. The active selection is stored in LimiterSettings. */
    val ALL: Set<String> = LimiterSettings.DEFAULT_TARGET_PACKAGES

    fun groupFor(packageName: String): Set<String> = when (packageName) {
        INSTAGRAM, "com.instagram.lite" -> setOf(INSTAGRAM, "com.instagram.lite")
        TIKTOK, "com.zhiliaoapp.musically.go", "com.ss.android.ugc.trill" ->
            setOf(TIKTOK, "com.zhiliaoapp.musically.go", "com.ss.android.ugc.trill")
        else -> setOf(packageName)
    }

    /**
     * The three services shown on the status screen, in display order. Variants are
     * deliberately not listed separately: they meter the same service.
     */
    val DISPLAY: List<Pair<String, String>> = listOf(
        INSTAGRAM to "Instagram",
        TIKTOK to "TikTok",
        REDDIT to "Reddit",
    )

    fun isTarget(packageName: String): Boolean = packageName in ALL

    fun labelFor(packageName: String): String = when (packageName) {
        INSTAGRAM, "com.instagram.lite" -> "Instagram"
        TIKTOK, "com.zhiliaoapp.musically.go", "com.ss.android.ugc.trill" -> "TikTok"
        REDDIT -> "Reddit"
        else -> packageName
    }
}
