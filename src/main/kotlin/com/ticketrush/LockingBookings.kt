package com.ticketrush

import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The two M3 strategies that wrap a *naive* booking body in a lock. On a single node the JVM
 * lock is enough; the whole point of M3 is that it stops being enough once there are several
 * nodes — only the Redis (distributed) lock is correct across the cluster.
 */
@Component
class LockingBookings(
    private val seats: SeatRepository,
    private val redisLock: RedisLock,
) {

    // One monitor per seat, PER JVM. That "per JVM" is exactly why it fails across nodes.
    private val monitors = ConcurrentHashMap<Long, Any>()

    fun bookSynchronized(seatId: Long, userId: String, gapMs: Long): BookResult {
        val monitor = monitors.computeIfAbsent(seatId) { Any() }
        return synchronized(monitor) { seats.bookNaive(seatId, userId, gapMs) }
    }

    fun bookRedisLock(seatId: Long, userId: String, gapMs: Long): BookResult {
        val key = "lock:seat:$seatId"
        val token = UUID.randomUUID().toString()

        var acquired = false
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (redisLock.tryAcquire(key, token, ttlMs = 5_000)) {
                acquired = true
                break
            }
            Thread.sleep(3)
        }
        if (!acquired) return BookResult(Outcome.REJECTED)

        return try {
            redisLock.fence(key) // obtain a fencing token for the critical section
            seats.bookNaive(seatId, userId, gapMs)
        } finally {
            redisLock.release(key, token)
        }
    }
}
