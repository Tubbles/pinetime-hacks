package com.tubbles.hub.integrations.printer

import com.tubbles.hub.integrations.WatchCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrinterEtaTest {

    private val now = 1_800_000_000L

    private fun printing(progress: Double, printDurationSeconds: Double, estimatedTimeSeconds: Double?) =
        PrinterReading("printing", printDurationSeconds, progress, estimatedTimeSeconds)

    private fun setEventAt(utcSeconds: Long): WatchCommand = WatchCommand.SetEvent(utcSeconds, PrinterIcon.ICON)

    @Test
    fun printingBelowFivePercentUsesTheSlicerEstimate() {
        val command = PrinterEta.command(printing(0.02, 600.0, 3600.0), now)

        assertEquals(setEventAt(now + 3000), command)
    }

    @Test
    fun printingFromFivePercentExtrapolatesFromProgress() {
        val command = PrinterEta.command(printing(0.25, 900.0, 3600.0), now)

        assertEquals(setEventAt(now + 2700), command)
    }

    @Test
    fun printingBelowFivePercentWithoutEstimateHasNoOpinion() {
        assertNull(PrinterEta.command(printing(0.02, 60.0, null), now))
    }

    @Test
    fun printingWithoutProgressOrEstimateHasNoOpinion() {
        assertNull(PrinterEta.command(printing(0.0, 120.0, null), now))
    }

    @Test
    fun remainingTimeIsNeverNegative() {
        val command = PrinterEta.command(printing(0.01, 4000.0, 3600.0), now)

        assertEquals(setEventAt(now), command)
    }

    @Test
    fun pausedClears() {
        assertEquals(WatchCommand.ClearEvent, PrinterEta.command(PrinterReading("paused", 900.0, 0.5, 3600.0), now))
    }

    @Test
    fun completeClears() {
        assertEquals(WatchCommand.ClearEvent, PrinterEta.command(PrinterReading("complete", 3600.0, 1.0, 3600.0), now))
    }

    @Test
    fun standbyCancelledErrorAndUnknownStatesClear() {
        for (state in listOf("standby", "cancelled", "error", "something new")) {
            assertEquals(WatchCommand.ClearEvent, PrinterEta.command(PrinterReading(state, 0.0, 0.0, null), now))
        }
    }

    @Test
    fun unreachablePrinterHasNoOpinion() {
        assertNull(PrinterEta.command(null, now))
    }
}
