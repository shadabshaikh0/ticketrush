package com.ticketrush

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Generates REAL concurrency on the server: it launches [StampedeRequest.concurrency]
 * virtual threads that all block on a start latch, then are released at once to contend
 * for the same seats. This is the honest source of the race — not a browser animation.
 *
 * With nodes == 1 the bookings run in-process. With nodes > 1 (and a cluster load balancer
 * configured) each request is sent through the LB so it lands on one of several app nodes —
 * which is what exposes SYNCHRONIZED (a per-JVM lock) and validates REDIS_LOCK.
 */
@Service
class StampedeService(
    private val bookings: BookingService,
    private val seats: SeatRepository,
    private val events: SeatEventPublisher,
    private val mapper: ObjectMapper,
    @Value("\${demo.show-id}") private val showId: Long,
    @Value("\${demo.node-name}") private val localNode: String,
    @Value("\${demo.cluster-lb-url}") private val lbUrl: String,
) {
    private val http: HttpClient = HttpClient.newHttpClient()

    fun run(req: StampedeRequest): StampedeResult {
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

        val distributed = req.nodes > 1 && lbUrl.isNotBlank()
        val n = req.concurrency
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(n)
        val latencies = java.util.Collections.synchronizedList(ArrayList<Long>(n))
        val rejected = AtomicInteger()
        val errors = AtomicInteger()
        val retriesTotal = AtomicInteger()
        val seenSeat = ConcurrentHashMap.newKeySet<Long>()
        val nodesSeen = ConcurrentHashMap.newKeySet<String>()

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            for (w in 0 until n) {
                val seatId = ids[w % ids.size] // round-robin → multiple workers contend per seat
                executor.submit {
                    try {
                        startLatch.await()
                        val t0 = System.nanoTime()
                        val (outcome, retries, node) =
                            if (distributed) bookViaCluster(seatId, req, w)
                            else {
                                val r = bookings.book(req.strategy, seatId, "u$w", req.gapMs)
                                Triple(r.outcome, r.retries, localNode)
                            }
                        latencies.add((System.nanoTime() - t0) / 1_000_000)
                        retriesTotal.addAndGet(retries)
                        nodesSeen.add(node)
                        when (outcome) {
                            Outcome.BOOKED -> {
                                val oversell = !seenSeat.add(seatId)
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
            startLatch.countDown()
            doneLatch.await()
            val wallMs = System.currentTimeMillis() - wallStart

            return buildResult(req, n, latencies, rejected, errors, retriesTotal, nodesSeen.size, wallMs)
        }
    }

    /** Book one seat through the cluster load balancer; returns (outcome, retries, handling-node). */
    private fun bookViaCluster(seatId: Long, req: StampedeRequest, worker: Int): Triple<Outcome, Int, String> {
        val body = mapper.writeValueAsString(
            InternalBookRequest(seatId = seatId, strategy = req.strategy, gapMs = req.gapMs, userId = "u$worker"),
        )
        val request = HttpRequest.newBuilder(URI.create("$lbUrl/internal/book"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = http.send(request, HttpResponse.BodyHandlers.ofString())
        @Suppress("UNCHECKED_CAST")
        val map = mapper.readValue(resp.body(), Map::class.java) as Map<String, Any>
        return Triple(Outcome.valueOf(map["outcome"] as String), 0, map["node"] as String)
    }

    private fun buildResult(
        req: StampedeRequest,
        n: Int,
        latencies: List<Long>,
        rejected: AtomicInteger,
        errors: AtomicInteger,
        retriesTotal: AtomicInteger,
        nodesSeen: Int,
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
            nodes = req.nodes,
            nodesSeen = nodesSeen,
        )
        events.publish(mapOf("type" to "summary", "result" to result))
        return result
    }
}
