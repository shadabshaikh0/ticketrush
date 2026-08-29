package com.ticketrush

/** The concurrency-control strategy used when booking a seat. */
enum class Strategy { NAIVE, PESSIMISTIC, OPTIMISTIC, ATOMIC }

/** Outcome of a single booking attempt. */
enum class Outcome { BOOKED, REJECTED, ERROR }

data class SeatDto(val id: Long, val label: String, val status: String)

/** Result of one booking attempt inside the repository. */
data class BookResult(
    val outcome: Outcome,
    val retries: Int = 0,
)

/** Request body for POST /demo/stampede. */
data class StampedeRequest(
    val concurrency: Int = 500,
    val strategy: Strategy = Strategy.NAIVE,
    // Artificial read->write gap (ms) used ONLY by NAIVE, to make the lost-update
    // race reliably reproducible in a live demo. The race exists regardless; the
    // gap just widens the window so it shows every time.
    val gapMs: Long = 5,
    val seatCount: Int = 100,
)

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
