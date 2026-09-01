package com.ticketrush

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * M2 demos: seat holds as leases (TTL + auto-release) and idempotent confirmation.
 */
@Service
class HoldService(
    private val seats: SeatRepository,
    private val events: SeatEventPublisher,
    @Value("\${demo.show-id}") private val showId: Long,
    @Value("\${demo.seat-count}") private val seatCount: Int,
) {

    /** Reset, then hold [count] seats for [ttlSeconds]. The sweeper auto-releases them on expiry. */
    fun holdSeats(count: Int, ttlSeconds: Long): Map<String, Any> {
        seats.ensureSeats(showId, seatCount)
        seats.reset(showId)

        val all = seats.seats(showId)
        val labelById = all.associate { it.id to it.label }
        events.publish(
            mapOf(
                "type" to "reset",
                "total" to all.size,
                "seats" to all.map { mapOf("id" to it.id, "label" to it.label) },
            ),
        )

        var held = 0
        for (id in seats.availableSeatIds(showId, count)) {
            if (seats.holdAtomic(id, "holder", ttlSeconds)) {
                held++
                events.publish(
                    mapOf("type" to "held", "seatId" to id, "label" to labelById[id], "ttlSeconds" to ttlSeconds),
                )
            }
        }
        return mapOf("held" to held, "ttlSeconds" to ttlSeconds)
    }

    /**
     * Idempotency demo: hold a fresh seat, then fire [attempts] confirmations with the SAME
     * idempotency key concurrently. Exactly one booking must result.
     */
    fun idempotencyTest(attempts: Int = 5): Map<String, Any?> {
        seats.ensureSeats(showId, seatCount)
        var ids = seats.availableSeatIds(showId, 1)
        if (ids.isEmpty()) {
            seats.reset(showId)
            ids = seats.availableSeatIds(showId, 1)
        }
        val seatId = ids.first()
        seats.holdAtomic(seatId, "holder", 60)

        val key = "idem-" + UUID.randomUUID()
        val outcomes = java.util.Collections.synchronizedList(ArrayList<String>())
        val start = CountDownLatch(1)
        val done = CountDownLatch(attempts)

        Executors.newVirtualThreadPerTaskExecutor().use { ex ->
            repeat(attempts) {
                ex.submit {
                    try {
                        start.await()
                        outcomes.add(seats.confirm(seatId, "payer", key))
                    } catch (e: Exception) {
                        outcomes.add("ERROR")
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            done.await()
        }

        val bookings = seats.bookingCountForSeat(seatId)
        val label = seats.seats(showId).firstOrNull { it.id == seatId }?.label
        events.publish(mapOf("type" to "booked", "seatId" to seatId, "label" to label, "oversell" to false))

        return mapOf(
            "seatId" to seatId,
            "label" to label,
            "attempts" to attempts,
            "sameKey" to key,
            "outcomes" to outcomes,
            "bookings" to bookings,
            "correct" to (bookings == 1),
        )
    }
}
