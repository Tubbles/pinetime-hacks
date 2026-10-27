package com.tubbles.hub.integrations.printer

/**
 * One successful Moonraker poll, in Moonraker's units: seconds, and
 * progress as a 0..1 ratio. [estimatedTimeSeconds] is the slicer's
 * estimate for the whole print, null when the file metadata has none.
 */
data class PrinterReading(
    val state: String,
    val printDurationSeconds: Double,
    val progress: Double,
    val estimatedTimeSeconds: Double?,
)
