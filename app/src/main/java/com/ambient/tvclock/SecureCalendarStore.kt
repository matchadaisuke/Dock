package com.ambient.tvclock

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the bearer-like calendar feed URL encrypted at rest using Android Keystore.
 *
 * Older builds exposed separate Personal and Work feeds. The app now has one
 * calendar slot. Migration prefers the former Personal URL; when it is empty,
 * the former Work URL is promoted into the single slot. The obsolete Work copy
 * is then deleted from both encrypted and legacy plaintext preferences.
 */
object SecureCalendarStore {
    private const val PREFS = "secure_calendar_preferences"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun migrateLegacy(context: Context) {
        val app = context.applicationContext
        val legacy = PreferenceManager.getDefaultSharedPreferences(app)
        val secure = prefs(app)

        val secureCalendar = secure.getString(CalendarPreferences.KEY_CALENDAR_URL, null)
            ?.trim().orEmpty()
        val secureWork = secure.getString(CalendarPreferences.KEY_WORK_URL, null)
            ?.trim().orEmpty()
        val legacyCalendar = legacy.getString(CalendarPreferences.KEY_CALENDAR_URL, null)
            ?.trim().orEmpty()
        val legacyWork = legacy.getString(CalendarPreferences.KEY_WORK_URL, null)
            ?.trim().orEmpty()

        // Preserve the old Personal value first. A Work-only installation still
        // keeps its calendar by promoting that URL into the new single slot.
        val chosen = secureCalendar.ifBlank {
            legacyCalendar.ifBlank {
                secureWork.ifBlank { legacyWork }
            }
        }

        val secureEditor = secure.edit()
        var secureChanged = false
        if (chosen.isNotBlank() && secureCalendar != chosen) {
            secureEditor.putString(CalendarPreferences.KEY_CALENDAR_URL, chosen)
            secureChanged = true
        }
        if (secure.contains(CalendarPreferences.KEY_WORK_URL)) {
            secureEditor.remove(CalendarPreferences.KEY_WORK_URL)
            secureChanged = true
        }

        val secureReady = !secureChanged || secureEditor.commit()
        if (!secureReady) return

        if (legacy.contains(CalendarPreferences.KEY_CALENDAR_URL) ||
            legacy.contains(CalendarPreferences.KEY_WORK_URL)
        ) {
            legacy.edit()
                .remove(CalendarPreferences.KEY_CALENDAR_URL)
                .remove(CalendarPreferences.KEY_WORK_URL)
                .apply()
        }
    }

    fun get(context: Context, key: String): String {
        require(key == CalendarPreferences.KEY_CALENDAR_URL)
        migrateLegacy(context)
        return prefs(context).getString(key, "")?.trim().orEmpty()
    }

    fun put(context: Context, key: String, value: String?) {
        require(key == CalendarPreferences.KEY_CALENDAR_URL)
        migrateLegacy(context)
        val normalized = value?.trim().orEmpty()
        prefs(context).edit().putString(key, normalized).apply()
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .edit()
            .remove(CalendarPreferences.KEY_CALENDAR_URL)
            .remove(CalendarPreferences.KEY_WORK_URL)
            .apply()
    }
}

/** Preference bridge so EditTextPreference can read/write the encrypted store. */
class CalendarPreferenceDataStore(context: Context) : PreferenceDataStore() {
    private val appContext = context.applicationContext

    init {
        SecureCalendarStore.migrateLegacy(appContext)
    }

    override fun putString(key: String?, value: String?) {
        if (key == null) return
        val canonical = when (key) {
            CalendarPreferences.KEY_CALENDAR_URL,
            CalendarPreferences.KEY_WORK_URL -> CalendarPreferences.KEY_CALENDAR_URL
            else -> return
        }
        SecureCalendarStore.put(appContext, canonical, value)
    }

    override fun getString(key: String?, defValue: String?): String? {
        if (key == null) return defValue
        if (key != CalendarPreferences.KEY_CALENDAR_URL && key != CalendarPreferences.KEY_WORK_URL) {
            return defValue
        }
        return SecureCalendarStore.get(appContext, CalendarPreferences.KEY_CALENDAR_URL)
            .ifEmpty { defValue }
    }
}
