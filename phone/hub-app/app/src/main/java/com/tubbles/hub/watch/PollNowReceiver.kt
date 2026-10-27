package com.tubbles.hub.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tubbles.hub.StatusStore
import com.tubbles.hub.work.Scheduling

/**
 * The nudge from Home Assistant when a print starts or ends
 * (doc/DESIGN-hub.md, "The nudge from Home Assistant"). It carries no data:
 * the watch only ever gets what a poll of Moonraker says.
 */
class PollNowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_POLL_NOW) {
            StatusStore(context).recordNudge()
            Scheduling.enqueueRefreshNow(context)
        }
    }

    companion object {
        const val ACTION_POLL_NOW = "com.tubbles.hub.POLL_NOW"
    }
}
