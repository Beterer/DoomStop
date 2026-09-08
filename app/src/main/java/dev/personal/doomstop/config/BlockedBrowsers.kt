package dev.personal.doomstop.config

/**
 * Alternative browsers that are suspended unconditionally, so that the permanent Chrome
 * URL policy cannot be sidestepped by installing a browser Chrome policy does not reach.
 *
 * Scope, stated honestly (plan section 3):
 *  - This is a hardcoded list, not a category rule. A browser that is not on it is not
 *    blocked. Differently packaged clients, WebView-based apps and in-app browsers inside
 *    unrelated applications are NOT covered. This is not Internet filtering.
 *  - Suspension blocks USE, not the installation transaction. Standard device-owner APIs
 *    expose no per-package pre-install denylist, so a newly installed browser is suspended
 *    when the package-added signal arrives; the width of that window is measured and
 *    reported rather than hidden.
 *
 * Chrome stable is intentionally absent: it stays usable and is governed by the
 * URLBlocklist application restriction instead.
 */
object BlockedBrowsers {

    /** Product name beside every ID, per the plan's rule against guessing IDs from brands. */
    val ALL: Set<String> = setOf(
        // Mozilla
        "org.mozilla.firefox",               // Firefox (stable) -- verified installed on device
        "org.mozilla.firefox_beta",          // Firefox Beta
        "org.mozilla.fenix",                 // Firefox Nightly (Fenix)
        "org.mozilla.focus",                 // Firefox Focus
        "org.mozilla.klar",                  // Firefox Klar (Focus, German-market name)

        // Brave Software
        "com.brave.browser",                 // Brave (stable)
        "com.brave.browser_beta",            // Brave Beta
        "com.brave.browser_nightly",         // Brave Nightly

        // Microsoft
        "com.microsoft.emmx",                // Microsoft Edge (stable)
        "com.microsoft.emmx.beta",           // Microsoft Edge Beta
        "com.microsoft.emmx.dev",            // Microsoft Edge Dev
        "com.microsoft.emmx.canary",         // Microsoft Edge Canary

        // Opera Norway
        "com.opera.browser",                 // Opera
        "com.opera.browser.beta",            // Opera Beta
        "com.opera.mini.native",             // Opera Mini
        "com.opera.mini.native.beta",        // Opera Mini Beta
        "com.opera.gx",                      // Opera GX

        // Samsung
        "com.sec.android.app.sbrowser",      // Samsung Internet
        "com.sec.android.app.sbrowser.beta", // Samsung Internet Beta

        // Vivaldi Technologies
        "com.vivaldi.browser",               // Vivaldi
        "com.vivaldi.browser.snapshot",      // Vivaldi Snapshot

        // DuckDuckGo
        "com.duckduckgo.mobile.android",     // DuckDuckGo Private Browser

        // The Tor Project
        "org.torproject.torbrowser",         // Tor Browser -- verified installed on device
        "org.torproject.torbrowser_alpha",   // Tor Browser Alpha

        // Google -- non-stable Chrome channels only; Chrome stable stays available.
        "com.chrome.beta",                   // Chrome Beta
        "com.chrome.dev",                    // Chrome Dev
        "com.chrome.canary",                 // Chrome Canary
    )

    /**
     * Packages that must never be suspended, whatever else happens. Suspending WebView
     * would break unrelated applications system-wide; suspending Chrome stable would
     * remove ordinary browsing, which requirement 6 forbids.
     */
    val NEVER_SUSPEND: Set<String> = setOf(
        "com.android.chrome",                       // Chrome stable -- policy-controlled, allowed
        "com.google.android.webview",               // Android System WebView
        "com.google.android.overlay.googlewebview", // WebView overlay (Pixel)
        "com.android.webview",                      // AOSP WebView
    )

    init {
        require(ALL.intersect(NEVER_SUSPEND).isEmpty()) {
            "A package is listed as both blocked and protected; refusing an inconsistent list."
        }
    }
}
