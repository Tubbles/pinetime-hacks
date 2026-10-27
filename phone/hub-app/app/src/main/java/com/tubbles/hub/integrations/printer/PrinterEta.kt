package com.tubbles.hub.integrations.printer

import com.tubbles.hub.integrations.WatchCommand
import kotlin.math.roundToLong

/** The printer rule of doc/DESIGN-hub.md, "The printer integration". */
object PrinterEta {
    const val STATE_PRINTING = "printing"

    /**
     * Below this progress the file position ratio says nothing yet (heating
     * and purge), so the slicer's estimate is used instead.
     */
    const val EXTRAPOLATION_MINIMUM_PROGRESS = 0.05

    /**
     * A null [reading] is an unreachable printer: no command, so a Wi-Fi
     * blip never blanks the corner and a stale time expires on the watch by
     * itself. Any state but printing clears the slot, paused included,
     * because the estimate cannot be trusted while the job stands still.
     */
    fun command(reading: PrinterReading?, nowUtcSeconds: Long): WatchCommand? {
        if (reading == null) {
            return null
        }
        if (reading.state != STATE_PRINTING) {
            return WatchCommand.ClearEvent
        }
        val remaining = remainingSeconds(reading) ?: return null
        return WatchCommand.SetEvent(nowUtcSeconds + remaining, PrinterIcon.ICON)
    }

    /** Seconds until the print finishes, never negative; null without any basis for a guess. */
    fun remainingSeconds(reading: PrinterReading): Long? {
        val estimate = reading.estimatedTimeSeconds
        val remaining = when {
            // Without the slicer's number there is no basis for a guess yet: the
            // file position ratio during heating and purge would invent a time.
            reading.progress < EXTRAPOLATION_MINIMUM_PROGRESS ->
                (estimate ?: return null) - reading.printDurationSeconds
            else ->
                reading.printDurationSeconds * (1.0 - reading.progress) / reading.progress
        }
        return remaining.coerceAtLeast(0.0).roundToLong()
    }
}
