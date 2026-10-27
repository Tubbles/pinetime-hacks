package com.tubbles.hub.integrations.printer

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The two Moonraker requests of doc/DESIGN-hub.md, "The printer
 * integration". Plain requests without credentials: Moonraker trusts the
 * LAN and has no user account.
 */
class MoonrakerClient(context: Context) {
    private val cache =
        context.applicationContext.getSharedPreferences(CACHE_PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * null when the printer cannot be reached or answers with an error.
     * The Nexa plug powers the host together with the printer, so this is
     * also what "printer off" looks like.
     */
    suspend fun poll(baseUrl: String): PrinterReading? = withContext(Dispatchers.IO) {
        try {
            read(baseUrl.trimEnd('/'))
        } catch (error: IOException) {
            null
        } catch (error: JSONException) {
            null
        }
    }

    private fun read(baseUrl: String): PrinterReading {
        val status = getJson("$baseUrl/printer/objects/query?print_stats&virtual_sdcard&display_status")
            .getJSONObject("result")
            .getJSONObject("status")
        val printStats = status.getJSONObject("print_stats")
        val filename = printStats.optString("filename", "")
        return PrinterReading(
            state = printStats.getString("state"),
            printDurationSeconds = printStats.optDouble("print_duration", 0.0),
            progress = progress(status),
            estimatedTimeSeconds = if (filename.isEmpty()) null else estimatedTime(baseUrl, filename),
        )
    }

    /**
     * M73 progress from the slicer when there is any, else the file
     * position ratio.
     */
    private fun progress(status: JSONObject): Double {
        val displayProgress = status.optJSONObject("display_status")?.optDouble("progress", 0.0) ?: 0.0
        if (displayProgress > 0.0) {
            return displayProgress
        }
        return status.optJSONObject("virtual_sdcard")?.optDouble("progress", 0.0) ?: 0.0
    }

    /**
     * The slicer's estimate for [filename]. Cached for the last file asked
     * about, a missing estimate included, so the metadata request happens
     * once per print file.
     */
    private fun estimatedTime(baseUrl: String, filename: String): Double? {
        if (cache.getString(KEY_CACHED_FILENAME, null) == filename) {
            return cache.getString(KEY_CACHED_ESTIMATE, null)?.toDoubleOrNull()
        }
        val metadata = fetchMetadata(baseUrl, filename) ?: return null
        val estimate = metadata.optDouble("estimated_time", Double.NaN).takeUnless { it.isNaN() }
        cache.edit {
            putString(KEY_CACHED_FILENAME, filename)
            putString(KEY_CACHED_ESTIMATE, estimate?.toString() ?: "")
        }
        return estimate
    }

    /**
     * null on failure, and nothing is cached then, so the next poll asks
     * again. A failed metadata request leaves the main reading valid.
     */
    private fun fetchMetadata(baseUrl: String, filename: String): JSONObject? = try {
        val encodedFilename = URLEncoder.encode(filename, "UTF-8")
        getJson("$baseUrl/server/files/metadata?filename=$encodedFilename").getJSONObject("result")
    } catch (error: IOException) {
        null
    } catch (error: JSONException) {
        null
    }

    /** Throws IOException for connection failures and for any non-2xx answer. */
    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as? HttpURLConnection
            ?: throw IOException("not an HTTP URL: $url")
        try {
            connection.connectTimeout = TIMEOUT_MILLISECONDS
            connection.readTimeout = TIMEOUT_MILLISECONDS
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode from $url")
            }
            return JSONObject(connection.inputStream.bufferedReader().use { reader -> reader.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MILLISECONDS = 10_000
        const val CACHE_PREFERENCES_NAME = "moonraker_cache"
        const val KEY_CACHED_FILENAME = "estimate_filename"
        const val KEY_CACHED_ESTIMATE = "estimate_seconds"
    }
}
