package com.vrticconnect.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * E02-B13: in-memory sliding-window limiter keyed by an opaque string (client IP, e-mail hash, ...).
 * One instance per process: the account dimension is additionally backed by the durable
 * `app.login_attempts` table (see LoginService), so a restart or a second instance cannot reset
 * a brute-force counter. Buckets expire lazily; the map is pruned when it grows past [pruneAt].
 */
class RateLimiter(private val pruneAt: Int = 10_000) {
    private class Bucket(var windowStart: Instant, var count: Int)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /**
     * Records one hit for [key] and returns the number of seconds to wait when the limit is exceeded,
     * or null when the request may proceed. Fixed windows of [window] length, [limit] hits per window.
     */
    fun hit(key: String, limit: Int, window: Duration, now: Instant = Instant.now()): Long? {
        if (buckets.size > pruneAt) prune(now, window)
        var retryAfter: Long? = null
        buckets.compute(key) { _, existing ->
            val bucket = if (existing == null || Duration.between(existing.windowStart, now) >= window) Bucket(now, 0) else existing
            if (bucket.count >= limit) {
                retryAfter = maxOf(1L, window.seconds - Duration.between(bucket.windowStart, now).seconds)
            } else {
                bucket.count++
            }
            bucket
        }
        return retryAfter
    }

    /** Throws 429 problem+json with `Retry-After` when the limit is exceeded. */
    fun require(key: String, limit: Int, window: Duration) {
        hit(key, limit, window)?.let { seconds -> throw ProblemException.rateLimited(seconds) }
    }

    private fun prune(now: Instant, window: Duration) {
        buckets.entries.removeIf { Duration.between(it.value.windowStart, now) >= window }
    }
}

/** Limits applied on the authentication surface (docs/SECURITY.md 2.4). */
object AuthRateLimits {
    /** Requests per client IP per minute across all mutating /auth routes. */
    const val IP_PER_MINUTE = 20
    val IP_WINDOW: Duration = Duration.ofMinutes(1)

    /** forgot-password / resend-verification per e-mail hash per hour. */
    const val EMAIL_PER_HOUR = 3
    val EMAIL_WINDOW: Duration = Duration.ofHours(1)

    /** Failed logins per account (e-mail hash) inside the window before the account is locked. */
    const val FAILED_LOGINS_BEFORE_LOCK = 10
    val FAILED_LOGIN_WINDOW: Duration = Duration.ofMinutes(15)
    val LOCK_BASE: Duration = Duration.ofMinutes(15)
    val LOCK_MAX: Duration = Duration.ofHours(24)

    /** 15 min for the first lock, doubling with every further block of failures, capped at 24 h. */
    fun lockDuration(failedLoginCount: Int): Duration {
        val locks = maxOf(1, failedLoginCount / FAILED_LOGINS_BEFORE_LOCK)
        val multiplied = LOCK_BASE.multipliedBy(1L shl minOf(locks - 1, 10))
        return if (multiplied > LOCK_MAX) LOCK_MAX else multiplied
    }
}

/**
 * Client address for rate limiting. Behind the nginx reverse proxy the first `X-Forwarded-For`
 * entry is the client, but only when the deployment says the proxy is trusted; otherwise the
 * header is attacker-controlled and the socket address is used.
 */
fun ApplicationCall.clientIp(trustProxyHeaders: Boolean): String {
    if (trustProxyHeaders) {
        request.headers["X-Forwarded-For"]?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return request.origin.remoteAddress
}

fun ProblemException.Companion.rateLimited(retryAfterSeconds: Long) = ProblemException(
    status = HttpStatusCode.TooManyRequests,
    type = ProblemTypes.RATE_LIMITED,
    title = "Too many requests",
    detail = "RATE_LIMITED",
    headers = mapOf("Retry-After" to retryAfterSeconds.toString()),
)
