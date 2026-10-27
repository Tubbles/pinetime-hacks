package com.tubbles.hub.integrations.printer

import android.content.Context
import com.tubbles.hub.SettingsStore
import com.tubbles.hub.integrations.Integration
import com.tubbles.hub.integrations.WatchCommand
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** The 3D printer's finish time, polled from Moonraker. */
class PrinterIntegration(context: Context) : Integration {
    private val applicationContext = context.applicationContext
    private val client = MoonrakerClient(applicationContext)
    private var outcome = "printer: not polled yet"

    override val id: Int = SOURCE_ID

    override val lastOutcome: String
        get() = outcome

    override suspend fun refresh(): WatchCommand? {
        val reading = client.poll(SettingsStore(applicationContext).read().printerUrl)
        val command = PrinterEta.command(reading, System.currentTimeMillis() / 1000)
        outcome = describe(reading, command)
        return command
    }

    /** E.g. "printer: printing, 42 %, done 17:42", "printer: unreachable", "printer: standby, cleared". */
    private fun describe(reading: PrinterReading?, command: WatchCommand?): String {
        if (reading == null) {
            return "printer: unreachable"
        }
        val percent = (reading.progress * 100).roundToInt()
        return when (command) {
            is WatchCommand.SetEvent -> "printer: ${reading.state}, $percent %, done ${localTime(command.utcSeconds)}"
            WatchCommand.ClearEvent -> "printer: ${reading.state}, cleared"
            null -> "printer: ${reading.state}, $percent %, no estimate yet"
        }
    }

    private fun localTime(utcSeconds: Long): String =
        TIME_FORMAT.withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(utcSeconds))

    companion object {
        /** The printer's slot on the watch (doc/DESIGN-hub.md, event frame table: 1 = printer). */
        const val SOURCE_ID = 1

        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
