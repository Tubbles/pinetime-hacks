package com.tubbles.hub.integrations.printer

import com.tubbles.hub.watch.Icon

/**
 * The printer's 20x20 icon for the watch corner (doc/DESIGN-hub.md, "The
 * printer integration"): a box frame with a gantry across, the print head
 * and its nozzle, and a part standing on the bed.
 */
object PrinterIcon {
    val ICON: Icon = Icon.fromPicture(
        listOf(
            "####################",
            "####################",
            "##................##",
            "##................##",
            "####################",
            "##.....######.....##",
            "##.....######.....##",
            "##.......##.......##",
            "##................##",
            "##......####......##",
            "##.....######.....##",
            "##.....######.....##",
            "##....########....##",
            "##....########....##",
            "##..############..##",
            "##..############..##",
            "##................##",
            "##................##",
            "####################",
            "####################",
        )
    )
}
