package com.ticketrush

import org.springframework.stereotype.Service

/** Single place that maps a [Strategy] to its booking implementation. */
@Service
class BookingService(
    private val seats: SeatRepository,
    private val locking: LockingBookings,
) {
    fun book(strategy: Strategy, seatId: Long, userId: String, gapMs: Long): BookResult =
        when (strategy) {
            Strategy.NAIVE -> seats.bookNaive(seatId, userId, gapMs)
            Strategy.PESSIMISTIC -> seats.bookPessimistic(seatId, userId)
            Strategy.OPTIMISTIC -> seats.bookOptimistic(seatId, userId)
            Strategy.ATOMIC -> seats.bookAtomic(seatId, userId)
            Strategy.SYNCHRONIZED -> locking.bookSynchronized(seatId, userId, gapMs)
            Strategy.REDIS_LOCK -> locking.bookRedisLock(seatId, userId, gapMs)
        }
}
