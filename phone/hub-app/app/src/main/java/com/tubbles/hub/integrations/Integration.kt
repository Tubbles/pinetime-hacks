package com.tubbles.hub.integrations

import com.tubbles.hub.watch.Icon

/** What an integration wants its slot on the watch to hold. */
sealed interface WatchCommand {
    data class SetEvent(val utcSeconds: Long, val icon: Icon) : WatchCommand

    data object ClearEvent : WatchCommand
}

/**
 * One source of events for the watch's next-event corner. Adding an
 * integration means one more object in RefreshWorker's list and one more
 * source id; transport, scheduling and UI stay as they are
 * (doc/DESIGN-hub.md, Leg 3 "Shape").
 */
interface Integration {
    /** The event frame's source id, 1..255, unique per integration. */
    val id: Int

    /** One line on what the last [refresh] found, for the status screen. */
    val lastOutcome: String

    /**
     * Asks the integration's source what the watch should show. null means
     * no opinion: the watch keeps what it has, as for an unreachable
     * printer (doc/DESIGN-hub.md, decision 3).
     */
    suspend fun refresh(): WatchCommand?
}
