package dev.personal.doomstop.config

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

data class AppChoice(val packageName: String, val label: String, val packages: Set<String>)

/** Launchable apps only. Core phone functions cannot become allowance targets. */
class AppCatalog(private val context: Context) {
    private val pm = context.packageManager

    fun essentialPackages(): Set<String> = buildSet {
        addAll(BlockedBrowsers.NEVER_SUSPEND)
        addAll(setOf(
            context.packageName, "android", "com.android.systemui", "com.android.settings",
            "com.android.vending", "com.google.android.packageinstaller", "com.android.packageinstaller",
            "com.google.android.permissioncontroller", "com.android.permissioncontroller",
            "com.android.phone", "com.android.server.telecom", "com.android.emergency",
            "com.google.android.apps.safetyhub",
        ))
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let(::add)
        Telephony.Sms.getDefaultSmsPackage(context)?.let(::add)
        context.getSystemService(InputMethodManager::class.java)?.inputMethodList.orEmpty()
            .forEach { add(it.packageName) }
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .forEach { add(it.activityInfo.packageName) }
        // Exclude nondefault calling/SMS clients too: changing defaults must remain safe.
        for (intent in listOf(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
        )) pm.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
            .forEach { add(it.activityInfo.packageName) }
    }

    fun unselectablePackages(): Set<String> = essentialPackages() + BlockedBrowsers.ALL

    fun apps(savedPackages: Set<String> = emptySet()): List<AppChoice> {
        val protected = unselectablePackages()
        val defaults = TargetPackages.DISPLAY.map { (pkg, label) ->
            AppChoice(pkg, label, TargetPackages.groupFor(pkg))
        }
        val installed = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.MATCH_DISABLED_COMPONENTS,
        ).map { AppChoice(it.activityInfo.packageName, it.loadLabel(pm).toString(), setOf(it.activityInfo.packageName)) }
        val missing = savedPackages.filter { it !in TargetPackages.ALL }.map { pkg ->
            val label = runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrDefault(pkg)
            AppChoice(pkg, label, setOf(pkg))
        }
        return (defaults + installed.filter { it.packageName !in TargetPackages.ALL } + missing)
            .filter { it.packageName !in protected }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun validate(packages: Set<String>, previous: Set<String>) {
        require(packages.isNotEmpty()) { "Choose at least one app" }
        val available = apps(previous).flatMap { it.packages }.toSet()
        require(packages.all { it in available }) { "Choose apps from the app list" }
    }
}
