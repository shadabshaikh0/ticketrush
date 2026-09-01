package com.ticketrush

import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Auto-releases expired seat holds once per second. This is the "lease expiry" half of the
 * hold/TTL concept: a seat reserved but never confirmed returns to AVAILABLE on its own.
 *
 * On a single node this plain @Scheduled sweeper is correct. In M3 (multiple app nodes) it
 * becomes leader-elected so exactly one node sweeps.
 */
@Component
class HoldSweeper(
    private val seats: SeatRepository,
    private val events: SeatEventPublisher,
    @Value("\${demo.show-id}") private val showId: Long,
) {

    @Scheduled(fixedDelay = 1000)
    fun sweep() {
        val released = seats.sweepExpiredHolds(showId)
        for (s in released) {
            events.publish(mapOf("type" to "released", "seatId" to s.id, "label" to s.label))
        }
    }
}
