package com.personal.autoclicker.data

import android.content.Context

class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var antiDetection: Boolean
        get() = prefs.getBoolean(KEY_ANTI_DETECTION, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ANTI_DETECTION, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "auto_clicker_settings"
        private const val KEY_ANTI_DETECTION = "anti_detection"
    }
}
