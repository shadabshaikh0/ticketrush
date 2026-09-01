package com.ticketrush

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * A minimal distributed lock on Redis:
 *  - acquire = SET key token NX PX ttl  (only one holder cluster-wide)
 *  - release = delete only if the token still matches (so we never free someone else's lock)
 *  - fence   = a monotonically increasing token (INCR) — the classic guard against a stale
 *              holder that paused past its TTL. We hand it out and log it; enforcing it fully
 *              would require the protected resource to reject lower tokens.
 */
@Component
class RedisLock(private val redis: StringRedisTemplate) {

    private val releaseScript = DefaultRedisScript(
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
        Long::class.java,
    )

    fun tryAcquire(key: String, token: String, ttlMs: Long): Boolean =
        redis.opsForValue().setIfAbsent(key, token, Duration.ofMillis(ttlMs)) == true

    fun release(key: String, token: String) {
        redis.execute(releaseScript, listOf(key), token)
    }

    fun fence(key: String): Long = redis.opsForValue().increment("fence:$key") ?: 0
}
