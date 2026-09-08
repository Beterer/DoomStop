package dev.personal.doomstop.config

import org.json.JSONArray

/**
 * Hosts blocked permanently in Chrome through the managed `URLBlocklist` application
 * restriction. Unlike the native-app allowance these are never released: requirement 7
 * says the websites stay blocked even while allowance or extra time remains.
 *
 * Filter semantics, verified against Google's "URL filter format" documentation
 * (support.google.com/chrome/a/answer/9942583) rather than assumed:
 *  - the grammar is `[scheme://][.]host[:port][/path][@query]`;
 *  - a bare host such as `instagram.com` matches that host AND its subdomains -- the
 *    documentation states "example.com matches example.com, www.example.com and
 *    sub.www.example.com" -- across all schemes and all paths;
 *  - a LEADING DOT would restrict a filter to the exact host only, so the entries below
 *    deliberately have none.
 *
 * That one rule is what covers `www.`, `m.`, `old.reddit.com`, `new.reddit.com`,
 * `vm.tiktok.com` and `vt.tiktok.com` without enumerating them.
 *
 * The content-delivery domains supplement direct-site blocking. They are not, and are not
 * claimed to be, comprehensive embedded-content filtering.
 */
object BlockedSites {

    val HOSTS: List<String> = listOf(
        // Instagram
        "instagram.com",
        "cdninstagram.com",
        // TikTok
        "tiktok.com",
        "tiktokv.com",
        "tiktokcdn.com",
        // Reddit
        "reddit.com",
        "redd.it",
        "redditmedia.com",
        "redditstatic.com",
    )

    /** Chrome package whose managed configuration carries the blocklist. */
    const val CHROME_PACKAGE = "com.android.chrome"

    /**
     * Restriction key. Verified by reading `app_restrictions.xml` out of the Chrome
     * 152.0.7977.76 APK installed on the target device: `URLBlocklist` is declared with
     * `restrictionType=6`, i.e. RestrictionEntry.TYPE_STRING, so the value is a String
     * holding a JSON array -- not a String[] and not a Bundle. The legacy `URLBlacklist`
     * key does not exist in this Chrome build, so only this key is written.
     */
    const val KEY_URL_BLOCKLIST = "URLBlocklist"

    /** The exact String value written into Chrome's application restrictions. */
    fun blocklistJson(): String = JSONArray(HOSTS).toString()

    /** True when a managed value already contains every required host. */
    fun isSatisfiedBy(current: String?): Boolean {
        if (current.isNullOrBlank()) return false
        val present = runCatching {
            val array = JSONArray(current)
            (0 until array.length()).mapTo(mutableSetOf()) { array.optString(it) }
        }.getOrElse { return false }
        return HOSTS.all { it in present }
    }
}
