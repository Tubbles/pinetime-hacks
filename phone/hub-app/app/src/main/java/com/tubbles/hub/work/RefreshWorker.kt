package com.tubbles.hub.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tubbles.hub.StatusStore
import com.tubbles.hub.integrations.Integration
import com.tubbles.hub.integrations.printer.PrinterIntegration
import com.tubbles.hub.watch.EventFrame
import com.tubbles.hub.watch.WatchLink

/**
 * Runs every integration, stores what each wants the watch to show, then
 * sends the whole desired state. Sending everything on every run makes the
 * periodic poll double as the reconnect fallback (doc/DESIGN-hub.md,
 * "Scheduling").
 */
class RefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val watchLink = WatchLink(applicationContext)
        val outcomes = integrations().map { integration -> refresh(integration, watchLink) }
        watchLink.resendAll()
        StatusStore(applicationContext).recordPoll(outcomes.joinToString("\n"))
        // Every kind of poll re-arms the Wi-Fi join trigger while no
        // validated Wi-Fi exists, which also covers reboots and app updates.
        Scheduling.armWifiJoinPoll(applicationContext)
        return Result.success()
    }

    private fun integrations(): List<Integration> = listOf(PrinterIntegration(applicationContext))

    /** A null command leaves the stored frame, and so the watch, as it was. */
    private suspend fun refresh(integration: Integration, watchLink: WatchLink): String {
        val command = integration.refresh()
        if (command != null) {
            watchLink.store(integration.id, EventFrame.encode(integration.id, command))
        }
        return integration.lastOutcome
    }
}
