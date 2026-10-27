package com.tubbles.hub.ui

import com.tubbles.hub.watch.Icon

/**
 * The "Send test event" button's event: its own slot, far from the real
 * integrations' ids, so a test never overwrites the printer's.
 */
object TestEvent {
    const val SOURCE_ID = 250
    const val LEAD_SECONDS = 30L * 60L

    val ICON: Icon = Icon.fromPicture(
        listOf(
            "......####......",
            "....########....",
            "...##########...",
            "..############..",
            ".##############.",
            ".##############.",
            "################",
            "################",
            "################",
            "################",
            ".##############.",
            ".##############.",
            "..############..",
            "...##########...",
            "....########....",
            "......####......",
        )
    )
}
