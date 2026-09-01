package com.ticketrush

/** The concurrency-control strategy used when booking a seat. */
enum class Strategy {
    NAIVE,          // read-then-write, no coordination (the bug)
    PESSIMISTIC,    // SELECT ... FOR UPDATE
    OPTIMISTIC,     // version + retry
    ATOMIC,         // conditional UPDATE
    SYNCHRONIZED,   // JVM lock around a naive body — correct on 1 node, oversells across nodes
    REDIS_LOCK,     // distributed lock (SET NX PX + fencing) — correct across nodes
}

/** Outcome of a single booking attempt. */
enum class Outcome { BOOKED, REJECTED, ERROR }

data class SeatDto(val id: Long, val label: String, val status: String)

/** Result of one booking attempt inside the repository. */
data class BookResult(
    val outcome: Outcome,
    val retries: Int = 0,
)

/** Request body for POST /demo/hold. */
data class HoldRequest(val count: Int = 20, val ttlSeconds: Long = 8)

/** Request body for POST /demo/stampede. */
data class StampedeRequest(
    val concurrency: Int = 500,
    val strategy: Strategy = Strategy.NAIVE,
    // Artificial read->write gap (ms) used by NAIVE / SYNCHRONIZED / REDIS_LOCK bodies,
    // to make the lost-update race reliably reproducible in a live demo.
    val gapMs: Long = 5,
    val seatCount: Int = 100,
    // >1 fans the load out across app nodes via the cluster load balancer (M3).
    val nodes: Int = 1,
)

/** Request body for POST /internal/book — one booking on whichever node handles it. */
data class InternalBookRequest(
    val seatId: Long,
    val strategy: Strategy,
    val gapMs: Long = 5,
    val userId: String = "u",
)

/** Seat + how many bookings it has — used to repaint the grid authoritatively after a run. */
data class SeatState(val id: Long, val label: String, val status: String, val bookings: Int)

/** Aggregated result of a stampede run — this is what the comparison table shows. */
data class StampedeResult(
    val strategy: Strategy,
    val concurrency: Int,
    val totalSeats: Int,
    val bookingRows: Int,
    val distinctSeatsBooked: Int,
    val oversoldSeats: Int,
    val rejected: Int,
    val errors: Int,
    val retries: Int,
    val invariantHeld: Boolean,
    val wallMs: Long,
    val throughputPerSec: Double,
    val p50Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
    val nodes: Int = 1,
    val nodesSeen: Int = 1,
)

/** Build a human seat label like A1..A10, B1..B10 (10 seats per row). */
fun seatLabel(i: Int): String {
    val rows = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    val idx = i - 1
    val perRow = 10
    val row = rows[(idx / perRow) % rows.length]
    val col = idx % perRow + 1
    return "$row$col"
}
