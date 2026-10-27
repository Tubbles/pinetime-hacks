package com.tubbles.hub.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tubbles.hub.SettingsStore
import com.tubbles.hub.watch.WifiJoinedReceiver
import java.util.concurrent.TimeUnit

/** The ways work gets queued (doc/DESIGN-hub.md, Leg 2 and "Scheduling"). */
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
     * For "Refresh now", the Home Assistant nudge and joining Wi-Fi. REPLACE
     * makes the poll that runs start after the latest request.
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

    /**
     * Has the system start [WifiJoinedReceiver] when the phone next joins a
     * Wi-Fi network, so the corner catches up on coming home. The system
     * sends the PendingIntent once per registration, and at once when the
     * request is already met, so this arms only while no validated Wi-Fi
     * network exists. Registering the same PendingIntent again replaces the
     * earlier registration. VALIDATED holds the broadcast back until the
     * Wi-Fi network is validated and already the default one, so the poll
     * goes out over Wi-Fi (doc/DESIGN-hub.md, "Scheduling").
     */
    fun armWifiJoinPoll(context: Context) {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        if (hasValidatedWifi(connectivityManager)) {
            return
        }
        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        val wifiJoinedIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, WifiJoinedReceiver::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        connectivityManager.registerNetworkCallback(wifiRequest, wifiJoinedIntent)
    }

    /**
     * Looks at every network, not the active one, so it is broader than the
     * request in [armWifiJoinPoll]: whenever the system would fire at once,
     * this is true and nothing registers. The active network misses Wi-Fi
     * while this app's network access is blocked (it is then null) or while
     * a VPN or cellular is the default, and arming would then fire, poll and
     * arm again in a loop.
     */
    private fun hasValidatedWifi(connectivityManager: ConnectivityManager): Boolean {
        // Deprecated since API 31 but still served, and unlike activeNetwork
        // it does not hide networks this app is blocked from.
        @Suppress("DEPRECATION")
        val networks = connectivityManager.allNetworks
        return networks.any { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities != null &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}
