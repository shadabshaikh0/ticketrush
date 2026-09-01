package com.ticketrush

import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Auto-releases expired seat holds once per second — the "lease expiry" half of the hold/TTL
 * concept: a seat reserved but never confirmed returns to AVAILABLE on its own.
 *
 * Leader election: with multiple nodes we don't want all of them sweeping. Each tick a node
 * tries to grab a short-lived Redis lock; only the holder sweeps. If Redis is unreachable
 * (e.g. single-node dev without Redis), it falls back to sweeping locally.
 */
@Component
class HoldSweeper(
    private val seats: SeatRepository,
    private val events: SeatEventPublisher,
    private val redisLock: RedisLock,
    @Value("\${demo.show-id}") private val showId: Long,
) {

    @Scheduled(fixedDelay = 1000)
    fun sweep() {
        val token = UUID.randomUUID().toString()
        var isLeader = false
        val redisUp = try {
            isLeader = redisLock.tryAcquire("sweeper-leader", token, ttlMs = 900)
            true
        } catch (e: Exception) {
            false // Redis not available → just sweep locally (single node)
        }
        if (redisUp && !isLeader) return // another node is the leader this tick

        try {
            val released = seats.sweepExpiredHolds(showId)
            for (s in released) {
                events.publish(mapOf("type" to "released", "seatId" to s.id, "label" to s.label))
            }
        } finally {
            if (redisUp && isLeader) {
                try {
                    redisLock.release("sweeper-leader", token)
                } catch (e: Exception) {
                    /* lock will expire on its own */
                }
            }
        }
    }
}
