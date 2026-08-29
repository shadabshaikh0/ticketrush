package com.ticketrush

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

/** Seeds the show's seats on startup so the grid renders on first page load. */
@Component
class DataInitializer(
    private val seats: SeatRepository,
    @Value("\${demo.show-id}") private val showId: Long,
    @Value("\${demo.seat-count}") private val seatCount: Int,
) : CommandLineRunner {

    override fun run(vararg args: String?) {
        seats.ensureSeats(showId, seatCount)
    }
}
