package com.ticketrush

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate

/**
 * All seat/booking data access. Each `book*` method is a different concurrency-control
 * strategy for the SAME operation ("book this seat"), so the demo can compare them.
 */
@Repository
class SeatRepository(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
) {

    // ---- setup / seeding -------------------------------------------------

    /** Create exactly [count] seats for the show, recreating them if the count differs. */
    fun ensureSeats(showId: Long, count: Int) {
        val existing = jdbc.queryForObject(
            "SELECT COUNT(*) FROM seat WHERE show_id = ?", Int::class.java, showId,
        ) ?: 0
        if (existing != count) {
            jdbc.update("DELETE FROM booking WHERE seat_id IN (SELECT id FROM seat WHERE show_id = ?)", showId)
            jdbc.update("DELETE FROM seat WHERE show_id = ?", showId)
            for (i in 1..count) {
                jdbc.update(
                    "INSERT INTO seat(show_id, label, status, version) VALUES (?, ?, 'AVAILABLE', 0)",
                    showId, seatLabel(i),
                )
            }
        }
    }

    /** Clear all bookings and mark every seat AVAILABLE again — used between runs. */
    fun reset(showId: Long) {
        jdbc.update("DELETE FROM booking WHERE seat_id IN (SELECT id FROM seat WHERE show_id = ?)", showId)
        jdbc.update(
            "UPDATE seat SET status = 'AVAILABLE', version = 0, booked_by = NULL, hold_expires_at = NULL WHERE show_id = ?",
            showId,
        )
    }

    fun seats(showId: Long): List<SeatDto> =
        jdbc.query(
            "SELECT id, label, status FROM seat WHERE show_id = ? ORDER BY id",
            { rs, _ -> SeatDto(rs.getLong("id"), rs.getString("label"), rs.getString("status")) },
            showId,
        )

    // ---- strategies (book a single seat) ---------------------------------

    /**
     * NAIVE — read-then-write with no coordination. Multiple threads read the seat as
     * AVAILABLE before any of them writes, so they ALL proceed and insert a booking →
     * the same seat is sold multiple times (classic lost update / double-book).
     */
    fun bookNaive(seatId: Long, userId: String, gapMs: Long): BookResult {
        val status = jdbc.queryForObject("SELECT status FROM seat WHERE id = ?", String::class.java, seatId)
        if (status != "AVAILABLE") return BookResult(Outcome.REJECTED)
        if (gapMs > 0) Thread.sleep(gapMs) // widen the read->write window so the race always shows
        jdbc.update("UPDATE seat SET status = 'BOOKED', booked_by = ? WHERE id = ?", userId, seatId)
        insertBooking(seatId, userId)
        return BookResult(Outcome.BOOKED)
    }

    /**
     * PESSIMISTIC — SELECT ... FOR UPDATE takes a row lock; contenders queue and only the
     * first sees the seat AVAILABLE. Correct, but serializes access and holds locks.
     */
    fun bookPessimistic(seatId: Long, userId: String): BookResult = tx.execute {
        val status = jdbc.queryForObject(
            "SELECT status FROM seat WHERE id = ? FOR UPDATE", String::class.java, seatId,
        )
        if (status != "AVAILABLE") return@execute BookResult(Outcome.REJECTED)
        jdbc.update("UPDATE seat SET status = 'BOOKED', booked_by = ? WHERE id = ?", userId, seatId)
        insertBooking(seatId, userId)
        BookResult(Outcome.BOOKED)
    }!!

    /**
     * OPTIMISTIC — no locks. Read the version, then commit only if it hasn't changed
     * (UPDATE ... WHERE version = ?). A losing writer sees rowcount 0 and retries.
     */
    fun bookOptimistic(seatId: Long, userId: String, maxRetries: Int = 3): BookResult {
        var retries = 0
        while (true) {
            val row = jdbc.queryForMap("SELECT status, version FROM seat WHERE id = ?", seatId)
            val status = row["status"] as String
            val version = (row["version"] as Number).toLong()
            if (status != "AVAILABLE") return BookResult(Outcome.REJECTED, retries)
            val updated = jdbc.update(
                "UPDATE seat SET status = 'BOOKED', booked_by = ?, version = version + 1 WHERE id = ? AND version = ?",
                userId, seatId, version,
            )
            if (updated == 1) {
                insertBooking(seatId, userId)
                return BookResult(Outcome.BOOKED, retries)
            }
            if (retries++ >= maxRetries) return BookResult(Outcome.REJECTED, retries)
        }
    }

    /**
     * ATOMIC — a single conditional UPDATE. The database guarantees exactly one row-update
     * wins the AND status='AVAILABLE' predicate. Simplest correct fix, no explicit lock.
     */
    fun bookAtomic(seatId: Long, userId: String): BookResult {
        val updated = jdbc.update(
            "UPDATE seat SET status = 'BOOKED', booked_by = ? WHERE id = ? AND status = 'AVAILABLE'",
            userId, seatId,
        )
        return if (updated == 1) {
            insertBooking(seatId, userId)
            BookResult(Outcome.BOOKED)
        } else {
            BookResult(Outcome.REJECTED)
        }
    }

    private fun insertBooking(seatId: Long, userId: String) {
        jdbc.update("INSERT INTO booking(seat_id, user_id) VALUES (?, ?)", seatId, userId)
    }

    // ---- M2: holds (leases with TTL) + idempotency -----------------------

    fun availableSeatIds(showId: Long, limit: Int): List<Long> =
        jdbc.queryForList(
            "SELECT id FROM seat WHERE show_id = ? AND status = 'AVAILABLE' ORDER BY id LIMIT ?",
            Long::class.java, showId, limit,
        )

    /**
     * Atomically place a hold (a lease) on an AVAILABLE seat for [ttlSeconds]. After that
     * instant the seat is eligible for auto-release by the sweeper unless it's confirmed.
     */
    fun holdAtomic(seatId: Long, userId: String, ttlSeconds: Long): Boolean {
        val updated = jdbc.update(
            "UPDATE seat SET status = 'HELD', booked_by = ?, hold_expires_at = now() + (? * interval '1 second') " +
                "WHERE id = ? AND status = 'AVAILABLE'",
            userId, ttlSeconds, seatId,
        )
        return updated == 1
    }

    /**
     * Confirm a held seat (the "payment" step), keyed by an idempotency key so replays and
     * concurrent duplicates create at most ONE booking.
     * Returns: BOOKED (first success) | DEDUP (key already used) | REJECTED (hold expired/not held).
     */
    fun confirm(seatId: Long, userId: String, idempotencyKey: String): String = tx.execute {
        val prior = jdbc.queryForObject(
            "SELECT COUNT(*) FROM booking WHERE idempotency_key = ?", Int::class.java, idempotencyKey,
        ) ?: 0
        if (prior > 0) return@execute "DEDUP"

        val updated = jdbc.update(
            "UPDATE seat SET status = 'BOOKED' WHERE id = ? AND status = 'HELD' AND hold_expires_at > now()",
            seatId,
        )
        if (updated != 1) return@execute "REJECTED" // hold expired or seat not held → late payment rejected

        try {
            jdbc.update(
                "INSERT INTO booking(seat_id, user_id, idempotency_key) VALUES (?, ?, ?)",
                seatId, userId, idempotencyKey,
            )
        } catch (e: DuplicateKeyException) {
            return@execute "DEDUP" // a concurrent request with the same key already booked it
        }
        "BOOKED"
    }!!

    /**
     * Release every hold whose lease has expired, in a single atomic statement, and return
     * the seats that were freed (so the UI can flip them back to AVAILABLE live).
     */
    fun sweepExpiredHolds(showId: Long): List<SeatDto> =
        jdbc.query(
            "UPDATE seat SET status = 'AVAILABLE', booked_by = NULL, hold_expires_at = NULL " +
                "WHERE show_id = ? AND status = 'HELD' AND hold_expires_at < now() " +
                "RETURNING id, label, status",
            { rs, _ -> SeatDto(rs.getLong("id"), rs.getString("label"), rs.getString("status")) },
            showId,
        )

    fun bookingCountForSeat(seatId: Long): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM booking WHERE seat_id = ?", Int::class.java, seatId) ?: 0

    /** Every seat with its booking count — the authoritative state the grid repaints from. */
    fun seatStates(showId: Long): List<SeatState> =
        jdbc.query(
            """
            SELECT s.id, s.label, s.status, COUNT(b.id) AS bookings
            FROM seat s LEFT JOIN booking b ON b.seat_id = s.id
            WHERE s.show_id = ?
            GROUP BY s.id, s.label, s.status
            ORDER BY s.id
            """.trimIndent(),
            { rs, _ -> SeatState(rs.getLong("id"), rs.getString("label"), rs.getString("status"), rs.getInt("bookings")) },
            showId,
        )

    // ---- metrics ---------------------------------------------------------

    fun totalSeats(showId: Long): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM seat WHERE show_id = ?", Int::class.java, showId) ?: 0

    fun bookingRows(showId: Long): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM booking b JOIN seat s ON s.id = b.seat_id WHERE s.show_id = ?",
            Int::class.java, showId,
        ) ?: 0

    fun distinctSeatsBooked(showId: Long): Int =
        jdbc.queryForObject(
            "SELECT COUNT(DISTINCT b.seat_id) FROM booking b JOIN seat s ON s.id = b.seat_id WHERE s.show_id = ?",
            Int::class.java, showId,
        ) ?: 0

    /** Number of seats that ended up with more than one booking (the oversell count). */
    fun oversoldSeats(showId: Long): Int =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM (
                SELECT b.seat_id
                FROM booking b JOIN seat s ON s.id = b.seat_id
                WHERE s.show_id = ?
                GROUP BY b.seat_id
                HAVING COUNT(*) > 1
            ) oversold
            """.trimIndent(),
            Int::class.java, showId,
        ) ?: 0
}
