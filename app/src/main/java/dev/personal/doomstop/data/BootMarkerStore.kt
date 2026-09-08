package dev.personal.doomstop.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * A deliberately tiny marker in DEVICE-PROTECTED storage, readable before the first unlock.
 *
 * The Room database lives in credential-protected storage and is unreadable during direct
 * boot, so this holds only the two facts early-boot enforcement needs: that protection was
 * configured at all, and whether targets were suspended when the device went down. That is
 * enough to re-apply suspension at LOCKED_BOOT_COMPLETED and close the window between boot
 * and first unlock.
 *
 * What is NOT here, on purpose: the PIN, the PIN verifier, and the balance. Moving the PIN
 * into device-protected storage would make early boot easier and the PIN weaker; the plan
 * forbids that trade, and the balance is recovered from the database after unlock instead.
 */
class BootMarkerStore(context: Context) {

    private val prefs: SharedPreferences = context
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** True once setup has completed and enforcement is meant to be active. */
    var protectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROTECTION_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_PROTECTION_ENABLED, value) }

    /**
     * Last known enforcement decision. On early boot the app re-applies exactly this,
     * erring toward suspension: an allowance that is briefly unavailable is a nuisance, one
     * that is briefly unenforced is a hole. Hence the default when nothing is stored.
     */
    var targetsSuspended: Boolean
        get() = prefs.getBoolean(KEY_TARGETS_SUSPENDED, true)
        set(value) = prefs.edit { putBoolean(KEY_TARGETS_SUSPENDED, value) }

    /** Wall-clock time the marker was last written, for measuring the real boot gap. */
    val updatedAtWallMs: Long
        get() = prefs.getLong(KEY_UPDATED_AT, 0L)

    /**
     * Called on every tick, so it writes only when something actually changed. That keeps a
     * once-per-second loop off the disk, and makes the rare real write cheap enough to
     * commit synchronously -- which matters, because this value has to survive whatever
     * takes the device down.
     */
    fun record(protectionEnabled: Boolean, targetsSuspended: Boolean, nowWallMs: Long) {
        if (this.protectionEnabled == protectionEnabled && this.targetsSuspended == targetsSuspended) return
        prefs.edit(commit = true) {
            putBoolean(KEY_PROTECTION_ENABLED, protectionEnabled)
            putBoolean(KEY_TARGETS_SUSPENDED, targetsSuspended)
            putLong(KEY_UPDATED_AT, nowWallMs)
        }
    }

    private companion object {
        const val FILE_NAME = "doomstop_boot_marker"
        const val KEY_PROTECTION_ENABLED = "protection_enabled"
        const val KEY_TARGETS_SUSPENDED = "targets_suspended"
        const val KEY_UPDATED_AT = "updated_at_wall_ms"
    }
}
