package com.tubbles.hub.work

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.tubbles.hub.watch.WatchLink

/** Re-sends the stored desired state without polling anything, for the Bluetooth connect trigger. */
class ResendWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {

    override fun doWork(): Result {
        WatchLink(applicationContext).resendAll()
        return Result.success()
    }
}
