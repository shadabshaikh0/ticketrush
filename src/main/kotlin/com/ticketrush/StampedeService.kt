package com.ticketrush

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Generates REAL concurrency on the server: it launches [StampedeRequest.concurrency]
 * virtual threads that all block on a start latch, then are released at once to contend
 * for the same seats. This is the honest source of the race — not a browser animation.
 */
@Service
class StampedeService(
    private val seats: SeatRepository,
    private val events: SeatEventPublisher,
    @Value("\${demo.show-id}") private val showId: Long,
) {

    fun run(req: StampedeRequest): StampedeResult {
        // Clean slate so every run is comparable.
        seats.ensureSeats(showId, req.seatCount)
        seats.reset(showId)
        val seatList = seats.seats(showId)
        val labelById = seatList.associate { it.id to it.label }
        val ids = seatList.map { it.id }

        events.publish(
            mapOf(
                "type" to "reset",
                "total" to seatList.size,
                "seats" to seatList.map { mapOf("id" to it.id, "label" to it.label) },
            ),
        )

        val n = req.concurrency
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(n)
        val latencies = java.util.Collections.synchronizedList(ArrayList<Long>(n))
        val rejected = AtomicInteger()
        val errors = AtomicInteger()
        val retriesTotal = AtomicInteger()
        // Tracks which seats we've already emitted a booking for, so we can flag oversell live.
        val seenSeat = ConcurrentHashMap.newKeySet<Long>()

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            for (w in 0 until n) {
                // Round-robin assignment → multiple workers contend for each seat.
                val seatId = ids[w % ids.size]
                executor.submit {
                    try {
                        startLatch.await()
                        val t0 = System.nanoTime()
                        val res = when (req.strategy) {
                            Strategy.NAIVE -> seats.bookNaive(seatId, "u$w", req.gapMs)
                            Strategy.PESSIMISTIC -> seats.bookPessimistic(seatId, "u$w")
                            Strategy.OPTIMISTIC -> seats.bookOptimistic(seatId, "u$w")
                            Strategy.ATOMIC -> seats.bookAtomic(seatId, "u$w")
                        }
                        latencies.add((System.nanoTime() - t0) / 1_000_000)
                        retriesTotal.addAndGet(res.retries)
                        when (res.outcome) {
                            Outcome.BOOKED -> {
                                val oversell = !seenSeat.add(seatId) // already booked once → double-book
                                events.publish(
                                    mapOf(
                                        "type" to "booked",
                                        "seatId" to seatId,
                                        "label" to labelById[seatId],
                                        "oversell" to oversell,
                                    ),
                                )
                            }
                            Outcome.REJECTED -> rejected.incrementAndGet()
                            Outcome.ERROR -> errors.incrementAndGet()
                        }
                    } catch (ex: Exception) {
                        errors.incrementAndGet()
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }

            val wallStart = System.currentTimeMillis()
            startLatch.countDown() // release the stampede
            doneLatch.await()
            val wallMs = System.currentTimeMillis() - wallStart

            return buildResult(req, n, latencies, rejected, errors, retriesTotal, wallMs)
        }
    }

    private fun buildResult(
        req: StampedeRequest,
        n: Int,
        latencies: List<Long>,
        rejected: AtomicInteger,
        errors: AtomicInteger,
        retriesTotal: AtomicInteger,
        wallMs: Long,
    ): StampedeResult {
        val sorted = latencies.sorted()
        fun pct(p: Double): Long =
            if (sorted.isEmpty()) 0 else sorted[minOf(sorted.size - 1, (p * sorted.size).toInt())]

        val totalSeats = seats.totalSeats(showId)
        val bookingRows = seats.bookingRows(showId)
        val distinct = seats.distinctSeatsBooked(showId)
        val oversold = seats.oversoldSeats(showId)
        val invariant = bookingRows == distinct && bookingRows <= totalSeats

        val result = StampedeResult(
            strategy = req.strategy,
            concurrency = n,
            totalSeats = totalSeats,
            bookingRows = bookingRows,
            distinctSeatsBooked = distinct,
            oversoldSeats = oversold,
            rejected = rejected.get(),
            errors = errors.get(),
            retries = retriesTotal.get(),
            invariantHeld = invariant,
            wallMs = wallMs,
            throughputPerSec = if (wallMs == 0L) 0.0 else n * 1000.0 / wallMs,
            p50Ms = pct(0.50),
            p95Ms = pct(0.95),
            p99Ms = pct(0.99),
        )
        events.publish(mapOf("type" to "summary", "result" to result))
        return result
    }
}
