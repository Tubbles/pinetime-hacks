package com.tubbles.hub.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.tubbles.hub.R
import com.tubbles.hub.Settings
import com.tubbles.hub.SettingsStore
import com.tubbles.hub.Status
import com.tubbles.hub.StatusStore
import com.tubbles.hub.isValidPrinterUrl
import com.tubbles.hub.parsePollIntervalMinutes
import com.tubbles.hub.watch.EventFrame
import com.tubbles.hub.watch.WatchLink
import com.tubbles.hub.work.Scheduling
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The one screen: settings, status, and the manual buttons (doc/DESIGN-hub.md, Leg 3 "Shape"). */
class MainActivity : AppCompatActivity() {

    // Nothing to do with the answer: without the permission the connect
    // receiver never fires and the periodic poll's resend still covers.
    private val bluetoothPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private lateinit var printerUrlField: EditText
    private lateinit var pollIntervalField: EditText
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        printerUrlField = findViewById(R.id.printer_url)
        pollIntervalField = findViewById(R.id.poll_interval)
        statusView = findViewById(R.id.status)
        showSettings(SettingsStore(this).read())
        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.refresh_now).setOnClickListener { refreshNow() }
        findViewById<Button>(R.id.send_test_event).setOnClickListener { sendTestEvent() }
        findViewById<Button>(R.id.clear_test_event).setOnClickListener { clearTestEvent() }
        Scheduling.schedulePeriodicRefresh(this)
        Scheduling.armWifiJoinPoll(this)
        // Only on a fresh start, so a rotation does not ask a second time.
        if (savedInstanceState == null) {
            requestBluetoothPermissionIfMissing()
        }
    }

    override fun onResume() {
        super.onResume()
        showStatus()
    }

    private fun showSettings(settings: Settings) {
        printerUrlField.setText(settings.printerUrl)
        pollIntervalField.setText(settings.pollIntervalMinutes.toString())
    }

    private fun save() {
        val printerUrl = printerUrlField.text.toString().trim()
        val pollIntervalMinutes = parsePollIntervalMinutes(pollIntervalField.text.toString())
        if (!isValidPrinterUrl(printerUrl)) {
            printerUrlField.error = getString(R.string.error_printer_url)
            return
        }
        if (pollIntervalMinutes == null) {
            pollIntervalField.error = getString(R.string.error_poll_interval)
            return
        }
        val settings = Settings(printerUrl, pollIntervalMinutes)
        SettingsStore(this).write(settings)
        showSettings(settings)
        Scheduling.schedulePeriodicRefresh(this)
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        showStatus()
    }

    private fun refreshNow() {
        Scheduling.enqueueRefreshNow(this)
        showStatus()
    }

    private fun sendTestEvent() {
        val utcSeconds = System.currentTimeMillis() / 1000 + TestEvent.LEAD_SECONDS
        sendTestFrame(EventFrame.encodeSet(TestEvent.SOURCE_ID, utcSeconds, TestEvent.ICON))
    }

    private fun clearTestEvent() {
        sendTestFrame(EventFrame.encodeClearSource(TestEvent.SOURCE_ID))
    }

    /** Stored like any integration's frame, so a reconnect repeats the test too. */
    private fun sendTestFrame(frame: ByteArray) {
        val watchLink = WatchLink(this)
        watchLink.store(TestEvent.SOURCE_ID, frame)
        watchLink.send(frame)
        showStatus()
    }

    private fun showStatus() {
        statusView.text = describeStatus(StatusStore(this).read(), WatchLink(this).storedFrames())
    }

    private fun describeStatus(status: Status, frames: Map<Int, String>): String = buildString {
        appendLine("Last poll: ${formatMillis(status.lastPollMillis)}")
        status.lastPollOutcome?.let { outcome -> appendLine(outcome) }
        appendLine("Frames last sent: ${formatMillis(status.lastSendMillis)}")
        frames.forEach { (sourceId, frameHex) -> appendLine("source $sourceId: $frameHex") }
        append("Last nudge: ${formatMillis(status.lastNudgeMillis)}")
    }

    private fun formatMillis(millis: Long?): String =
        millis?.let { TIME_FORMAT.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "never"

    private fun requestBluetoothPermissionIfMissing() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermissionRequest.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
