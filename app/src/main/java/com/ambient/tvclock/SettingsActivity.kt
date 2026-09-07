package com.ambient.tvclock

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<SwitchPreferenceCompat>(CalendarPreferences.KEY_SHOW_CALENDAR)
                ?.setOnPreferenceChangeListener { _, _ ->
                    CalendarPoller(requireContext()).publishNow()
                    true
                }

            val store = CalendarPreferenceDataStore(requireContext().applicationContext)
            val url = findPreference<EditTextPreference>(CalendarPreferences.KEY_CALENDAR_URL)
            url?.preferenceDataStore = store
            url?.text = SecureCalendarStore.get(requireContext(), CalendarPreferences.KEY_CALENDAR_URL)
            url?.setSummary(R.string.pref_calendar_url_summary)
            url?.setOnPreferenceChangeListener { _, newValue ->
                val value = newValue?.toString().orEmpty().trim()
                CalendarPreferences.setUrl(requireContext(), CalendarPreferences.KEY_CALENDAR_URL, value)
                CalendarPoller(requireContext()).publishNow()
                true
            }
        }

        override fun onResume() {
            super.onResume()
            CalendarPoller(requireContext()).publishNow()
        }
    }
}
