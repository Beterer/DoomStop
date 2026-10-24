package dev.personal.doomstop.config

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppCatalogTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun pickerIncludesLaunchableAppsAndExcludesCorePhoneFunctions() {
        val apps = AppCatalog(context).apps().flatMap { it.packages }.toSet()
        assertTrue(apps.containsAll(setOf("com.instagram.android", "com.instagram.lite", "com.reddit.frontpage")))
        assertFalse(context.packageName in apps)
        assertFalse("com.android.settings" in apps)
        assertFalse("com.android.vending" in apps)
        assertFalse("com.android.chrome" in apps)
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { assertFalse(it in apps) }
        Telephony.Sms.getDefaultSmsPackage(context)?.let { assertFalse(it in apps) }
        context.getSystemService(InputMethodManager::class.java)?.inputMethodList.orEmpty()
            .forEach { assertFalse(it.packageName in apps) }
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .forEach { assertFalse(it.activityInfo.packageName in apps) }
        for (intent in listOf(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")), Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))) {
            context.packageManager.queryIntentActivities(intent, 0)
                .forEach { assertFalse(it.activityInfo.packageName in apps) }
        }
    }

    @Test
    fun protectedAppsCannotBeSelectedEvenIfTheyAppearInOldSettings() {
        val result = runCatching { AppCatalog(context).validate(setOf("com.android.settings"), setOf("com.android.settings")) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
