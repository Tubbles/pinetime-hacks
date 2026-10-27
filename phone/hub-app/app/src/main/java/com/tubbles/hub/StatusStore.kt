package com.tubbles.hub

import android.content.Context
import androidx.core.content.edit

/**
 * What the status screen shows, the first place to look when the corner
 * disagrees with Fluidd (doc/DESIGN-hub.md, "What the user sees"). Times
 * are epoch milliseconds, null for "never".
 */
data class Status(
    val lastPollMillis: Long?,
    val lastPollOutcome: String?,
    val lastSendMillis: Long?,
    val lastNudgeMillis: Long?,
)

/** Kept in SharedPreferences because the worker and receivers write it while no screen is open. */
class StatusStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun recordPoll(outcome: String) {
        preferences.edit {
            putLong(KEY_LAST_POLL_MILLIS, System.currentTimeMillis())
            putString(KEY_LAST_POLL_OUTCOME, outcome)
        }
    }

    fun recordSend() {
        preferences.edit { putLong(KEY_LAST_SEND_MILLIS, System.currentTimeMillis()) }
    }

    fun recordNudge() {
        preferences.edit { putLong(KEY_LAST_NUDGE_MILLIS, System.currentTimeMillis()) }
    }

    fun read(): Status = Status(
        lastPollMillis = optionalLong(KEY_LAST_POLL_MILLIS),
        lastPollOutcome = preferences.getString(KEY_LAST_POLL_OUTCOME, null),
        lastSendMillis = optionalLong(KEY_LAST_SEND_MILLIS),
        lastNudgeMillis = optionalLong(KEY_LAST_NUDGE_MILLIS),
    )

    private fun optionalLong(key: String): Long? =
        if (preferences.contains(key)) preferences.getLong(key, 0L) else null

    private companion object {
        const val PREFERENCES_NAME = "status"
        const val KEY_LAST_POLL_MILLIS = "last_poll_millis"
        const val KEY_LAST_POLL_OUTCOME = "last_poll_outcome"
        const val KEY_LAST_SEND_MILLIS = "last_send_millis"
        const val KEY_LAST_NUDGE_MILLIS = "last_nudge_millis"
    }
}
