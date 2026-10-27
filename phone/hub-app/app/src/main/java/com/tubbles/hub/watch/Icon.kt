package com.tubbles.hub.watch

/**
 * A 1-bit icon for the watch's next-event corner, sent inside the event
 * frame so a new integration never needs a firmware build
 * (doc/DESIGN-hub.md, decision 1).
 *
 * Each entry of [rows] is one pixel row as a bit pattern of [width] bits,
 * the leftmost pixel in the highest bit: with width 8, 0b1000_0001 lights
 * both ends. [EventFrame] checks the limits.
 */
data class Icon(val width: Int, val height: Int, val rows: List<Int>) {

    companion object {
        private const val LIT = '#'
        private const val DARK = '.'

        /**
         * Builds an icon from a drawing, one string per row, '#' lit and
         * '.' dark, so an icon reads as a picture in the source.
         */
        fun fromPicture(picture: List<String>): Icon {
            val width = picture.firstOrNull()?.length ?: 0
            require(picture.all { line -> line.length == width }) {
                "every line of an icon picture must be $width characters long"
            }
            return Icon(width, picture.size, picture.map { line -> rowFromLine(line) })
        }

        private fun rowFromLine(line: String): Int =
            line.fold(0) { bits, pixel -> (bits shl 1) or pixelBit(pixel) }

        private fun pixelBit(pixel: Char): Int = when (pixel) {
            LIT -> 1
            DARK -> 0
            else -> throw IllegalArgumentException("icon pixel '$pixel' is neither '$LIT' nor '$DARK'")
        }
    }
}
