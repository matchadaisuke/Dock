package com.ambient.tvclock

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores bearer-like calendar feed URLs encrypted at rest using Android Keystore.
 *
 * The previous implementation kept secret iCal URLs in the default preferences
 * file as plaintext. Those URLs grant read access to a calendar feed, so treat
 * them like credentials. Existing values are migrated once and removed from the
 * legacy preference file.
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
        val legacy = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        val secure = prefs(context)
        val keys = listOf(
            CalendarPreferences.KEY_PERSONAL_URL,
            CalendarPreferences.KEY_WORK_URL,
        )

        val secureEditor = secure.edit()
        val legacyEditor = legacy.edit()
        var changed = false
        for (key in keys) {
            val oldValue = legacy.getString(key, null)?.trim().orEmpty()
            if (oldValue.isNotEmpty() && secure.getString(key, null).isNullOrBlank()) {
                secureEditor.putString(key, oldValue)
                changed = true
            }
            if (legacy.contains(key)) {
                legacyEditor.remove(key)
                changed = true
            }
        }
        if (changed) {
            // Commit the encrypted copy before deleting the plaintext source.
            if (secureEditor.commit()) {
                legacyEditor.apply()
            }
        }
    }

    fun get(context: Context, key: String): String {
        migrateLegacy(context)
        return prefs(context).getString(key, "")?.trim().orEmpty()
    }

    fun put(context: Context, key: String, value: String?) {
        require(key == CalendarPreferences.KEY_PERSONAL_URL || key == CalendarPreferences.KEY_WORK_URL)
        val normalized = value?.trim().orEmpty()
        prefs(context).edit().putString(key, normalized).apply()
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .edit()
            .remove(key)
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
        SecureCalendarStore.put(appContext, key, value)
    }

    override fun getString(key: String?, defValue: String?): String? {
        if (key == null) return defValue
        return SecureCalendarStore.get(appContext, key).ifEmpty { defValue }
    }
}
