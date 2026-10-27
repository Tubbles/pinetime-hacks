package com.tubbles.hub.watch

import com.tubbles.hub.integrations.WatchCommand

/**
 * Encoder for the frames written to the watch's Hub service, characteristic
 * 00060001. The layout and its limits are the table in doc/DESIGN-hub.md,
 * "Hub service, vendor id 0006"; the watch drops any frame that breaks
 * them, so this encoder refuses to build one.
 */
object EventFrame {
    const val VERSION: Byte = 1
    const val OPCODE_SET: Byte = 1
    const val OPCODE_CLEAR_SOURCE: Byte = 2
    const val OPCODE_CLEAR_ALL: Byte = 3
    const val MAXIMUM_ICON_SIDE = 24

    private const val RESERVED: Byte = 0
    private const val MAXIMUM_UTC_SECONDS = 0xFFFFFFFFL
    private const val HEX_DIGITS = "0123456789abcdef"

    /** The frame that puts [command] into the watch slot of [sourceId]. */
    fun encode(sourceId: Int, command: WatchCommand): ByteArray = when (command) {
        is WatchCommand.SetEvent -> encodeSet(sourceId, command.utcSeconds, command.icon)
        WatchCommand.ClearEvent -> encodeClearSource(sourceId)
    }

    /** Header of 10 bytes, then [icon] at 1 bit per pixel, rows padded to whole bytes. */
    fun encodeSet(sourceId: Int, utcSeconds: Long, icon: Icon): ByteArray {
        requireSourceId(sourceId)
        require(utcSeconds in 0..MAXIMUM_UTC_SECONDS) {
            "UTC seconds $utcSeconds do not fit the frame's unsigned 32-bit field"
        }
        requireIcon(icon)
        val header = byteArrayOf(VERSION, OPCODE_SET, sourceId.toByte(), RESERVED) +
            littleEndianUnsigned32(utcSeconds) +
            byteArrayOf(icon.width.toByte(), icon.height.toByte())
        return header + bitmap(icon)
    }

    fun encodeClearSource(sourceId: Int): ByteArray {
        requireSourceId(sourceId)
        return byteArrayOf(VERSION, OPCODE_CLEAR_SOURCE, sourceId.toByte(), RESERVED)
    }

    /**
     * The watch ignores the source byte of a clear-all, but the byte is
     * still sent: every clear frame is exactly 4 bytes long.
     */
    fun encodeClearAll(): ByteArray = byteArrayOf(VERSION, OPCODE_CLEAR_ALL, 1, RESERVED)

    /** Lower case, the casing the ClockSync bridge sends Gadgetbridge (ClockSyncFrame.bytesToHex). */
    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (value in bytes) {
            val unsigned = value.toInt() and 0xFF
            append(HEX_DIGITS[unsigned ushr 4])
            append(HEX_DIGITS[unsigned and 0x0F])
        }
    }

    /** One pixel row, most significant bit first, zero padded to whole bytes. */
    private fun rowBytes(row: Int, width: Int): ByteArray {
        val bytesPerRow = (width + 7) / 8
        val leftAligned = row shl (bytesPerRow * 8 - width)
        return ByteArray(bytesPerRow) { index -> (leftAligned ushr (8 * (bytesPerRow - 1 - index))).toByte() }
    }

    private fun bitmap(icon: Icon): ByteArray =
        icon.rows.flatMap { row -> rowBytes(row, icon.width).asList() }.toByteArray()

    private fun littleEndianUnsigned32(value: Long): ByteArray =
        ByteArray(4) { index -> (value ushr (8 * index)).toByte() }

    private fun requireSourceId(sourceId: Int) {
        require(sourceId in 1..255) { "source id $sourceId outside 1..255" }
    }

    private fun requireIcon(icon: Icon) {
        require(icon.width in 1..MAXIMUM_ICON_SIDE) { "icon width ${icon.width} outside 1..$MAXIMUM_ICON_SIDE" }
        require(icon.height in 1..MAXIMUM_ICON_SIDE) { "icon height ${icon.height} outside 1..$MAXIMUM_ICON_SIDE" }
        require(icon.rows.size == icon.height) { "icon has ${icon.rows.size} rows but height ${icon.height}" }
        require(icon.rows.all { row -> row >= 0 && (row ushr icon.width) == 0 }) {
            "an icon row has lit bits beyond width ${icon.width}"
        }
    }
}
