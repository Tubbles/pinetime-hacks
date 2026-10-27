package com.tubbles.hub.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tubbles.hub.SettingsStore
import java.util.concurrent.TimeUnit

/** The three ways work gets queued (doc/DESIGN-hub.md, Leg 2 and "Scheduling"). */
object Scheduling {
    private const val PERIODIC_REFRESH = "periodic-refresh"
    private const val REFRESH_NOW = "refresh-now"
    private const val RESEND_AFTER_CONNECT = "resend-after-connect"

    /**
     * Gadgetbridge initializes the watch a few seconds after the Bluetooth
     * link comes up and refuses writes until then.
     */
    private const val RESEND_DELAY_SECONDS = 20L

    /**
     * (Re)enqueues the periodic poll at the interval from the settings.
     * UPDATE keeps the original enqueue time, so calling this on every app
     * start does not push the next run back.
     */
    fun schedulePeriodicRefresh(context: Context) {
        val intervalMinutes = SettingsStore(context).read().pollIntervalMinutes
            .coerceAtLeast(SettingsStore.MINIMUM_POLL_INTERVAL_MINUTES)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_REFRESH, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * For "Refresh now" and the Home Assistant nudge. REPLACE makes the poll
     * that runs start after the latest request.
     */
    fun enqueueRefreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(REFRESH_NOW, ExistingWorkPolicy.REPLACE, request)
    }

    /** REPLACE restarts the delay when several links come up in a row. */
    fun enqueueResendAfterConnect(context: Context) {
        val request = OneTimeWorkRequestBuilder<ResendWorker>()
            .setInitialDelay(RESEND_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(RESEND_AFTER_CONNECT, ExistingWorkPolicy.REPLACE, request)
    }
}
