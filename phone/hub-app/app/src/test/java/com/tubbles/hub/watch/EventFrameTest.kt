package com.tubbles.hub.watch

import com.tubbles.hub.integrations.printer.PrinterIcon
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EventFrameTest {

    private fun filledIcon(width: Int, height: Int): Icon =
        Icon(width, height, List(height) { (1 shl width) - 1 })

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { index -> values[index].toByte() }

    @Test
    fun setFrameOf20x20IconIs70BytesWithRowsPaddedToThreeBytes() {
        val frame = EventFrame.encodeSet(1, 0L, filledIcon(20, 20))

        assertEquals(10 + 3 * 20, frame.size)
        for (row in 0 until 20) {
            val offset = 10 + row * 3
            assertArrayEquals(bytes(0xFF, 0xFF, 0xF0), frame.copyOfRange(offset, offset + 3))
        }
    }

    @Test
    fun setFrameOf8x3IconIsOneBytePerRowMostSignificantBitFirst() {
        val icon = Icon.fromPicture(listOf("#......#", "##......", ".......#"))

        val frame = EventFrame.encodeSet(7, 0L, icon)

        assertEquals(13, frame.size)
        assertArrayEquals(bytes(0x81, 0xC0, 0x01), frame.copyOfRange(10, 13))
    }

    @Test
    fun setFrameHeaderCarriesVersionOpcodeSourceReservedAndDimensions() {
        val frame = EventFrame.encodeSet(255, 0L, filledIcon(20, 20))

        assertArrayEquals(bytes(1, 1, 0xFF, 0), frame.copyOfRange(0, 4))
        assertArrayEquals(bytes(20, 20), frame.copyOfRange(8, 10))
    }

    @Test
    fun setFrameTimeIsLittleEndianUnsigned32() {
        val frame = EventFrame.encodeSet(1, 0xF2345678L, filledIcon(8, 1))

        assertArrayEquals(bytes(0x78, 0x56, 0x34, 0xF2), frame.copyOfRange(4, 8))
    }

    @Test
    fun largestIconGivesTheLargestFrame() {
        assertEquals(82, EventFrame.encodeSet(1, 0L, filledIcon(24, 24)).size)
    }

    @Test
    fun printerIconEncodesAs20x20() {
        val frame = EventFrame.encodeSet(1, 0L, PrinterIcon.ICON)

        assertEquals(70, frame.size)
        assertArrayEquals(bytes(20, 20), frame.copyOfRange(8, 10))
    }

    @Test
    fun clearSourceFrameIsExactlyFourBytes() {
        assertArrayEquals(bytes(1, 2, 250, 0), EventFrame.encodeClearSource(250))
    }

    @Test
    fun clearAllFrameIsExactlyFourBytes() {
        assertArrayEquals(bytes(1, 3, 1, 0), EventFrame.encodeClearAll())
    }

    @Test
    fun iconSidesOutsideOneTo24AreRejected() {
        for (side in listOf(0, 25)) {
            assertThrows(IllegalArgumentException::class.java) {
                EventFrame.encodeSet(1, 0L, Icon(side, 1, listOf(0)))
            }
            assertThrows(IllegalArgumentException::class.java) {
                EventFrame.encodeSet(1, 0L, Icon(1, side, List(side) { 0 }))
            }
        }
    }

    @Test
    fun sourceIdsOutsideOneTo255AreRejected() {
        assertThrows(IllegalArgumentException::class.java) { EventFrame.encodeClearSource(0) }
        assertThrows(IllegalArgumentException::class.java) { EventFrame.encodeClearSource(256) }
    }

    @Test
    fun hexIsTwoLowerCaseDigitsPerByte() {
        assertEquals("01ab00ff", EventFrame.toHex(bytes(0x01, 0xAB, 0x00, 0xFF)))
        assertEquals("01020100", EventFrame.toHex(EventFrame.encodeClearSource(1)))
    }
}
