package com.tubbles.hub

import android.content.Context
import androidx.core.content.edit
import java.net.MalformedURLException
import java.net.URL

/**
 * The app's only settings (doc/DESIGN-hub.md, Leg 3: the printer URL and
 * the poll interval; the watch address is a build constant).
 */
data class Settings(val printerUrl: String, val pollIntervalMinutes: Long)

class SettingsStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): Settings = Settings(
        printerUrl = preferences.getString(KEY_PRINTER_URL, null) ?: DEFAULT_PRINTER_URL,
        pollIntervalMinutes = preferences.getLong(KEY_POLL_INTERVAL_MINUTES, DEFAULT_POLL_INTERVAL_MINUTES),
    )

    fun write(settings: Settings) {
        preferences.edit {
            putString(KEY_PRINTER_URL, settings.printerUrl)
            putLong(KEY_POLL_INTERVAL_MINUTES, settings.pollIntervalMinutes)
        }
    }

    companion object {
        const val DEFAULT_PRINTER_URL = "http://fluiddpi.local:7125"
        const val DEFAULT_POLL_INTERVAL_MINUTES = 15L

        /** WorkManager's floor for periodic work, which would raise a shorter interval anyway. */
        const val MINIMUM_POLL_INTERVAL_MINUTES = 15L

        private const val PREFERENCES_NAME = "settings"
        private const val KEY_PRINTER_URL = "printer_url"
        private const val KEY_POLL_INTERVAL_MINUTES = "poll_interval_minutes"
    }
}

/** An http or https URL with a host and a valid port, the forms a Moonraker address takes. */
fun isValidPrinterUrl(text: String): Boolean {
    val url = try {
        URL(text)
    } catch (error: MalformedURLException) {
        return false
    }
    return url.protocol in setOf("http", "https") && url.host.isNotEmpty() && url.port in -1..65535
}

/** Whole minutes, raised to the floor; null when [text] is not a number. */
fun parsePollIntervalMinutes(text: String): Long? =
    text.trim().toLongOrNull()?.coerceAtLeast(SettingsStore.MINIMUM_POLL_INTERVAL_MINUTES)
