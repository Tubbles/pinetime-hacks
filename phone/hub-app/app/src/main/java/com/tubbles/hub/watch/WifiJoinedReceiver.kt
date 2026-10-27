package com.tubbles.hub.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tubbles.hub.work.Scheduling

/**
 * Polls as soon as the phone is on a validated Wi-Fi network, so the corner
 * catches up on coming home instead of at the next periodic poll
 * (doc/DESIGN-hub.md, "Scheduling"). The system sends this through the
 * PendingIntent [Scheduling.armWifiJoinPoll] registers, once per
 * registration. It is not re-armed here: the phone is on Wi-Fi now, and the
 * next poll that finds no validated Wi-Fi arms it again.
 */
class WifiJoinedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduling.enqueueRefreshNow(context)
    }
}
