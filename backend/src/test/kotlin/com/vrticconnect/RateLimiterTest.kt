package com.vrticconnect

import com.vrticconnect.http.AuthRateLimits
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.RateLimiter
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RateLimiterTest {

    @Test
    fun `allows limit hits per window then answers with retry-after and resets after the window`() {
        val limiter = RateLimiter()
        val t0 = Instant.parse("2026-09-28T10:00:00Z")
        val window = Duration.ofMinutes(1)
        repeat(3) { assertNull(limiter.hit("k", 3, window, t0.plusSeconds(it.toLong()))) }
        val retry = limiter.hit("k", 3, window, t0.plusSeconds(10))
        assertNotNull(retry)
        assertTrue(retry in 1..60, "retry-after $retry")
        // still blocked inside the window, other keys unaffected
        assertNotNull(limiter.hit("k", 3, window, t0.plusSeconds(59)))
        assertNull(limiter.hit("other", 3, window, t0.plusSeconds(59)))
        // a new window starts after 60 s
        assertNull(limiter.hit("k", 3, window, t0.plusSeconds(60)))
    }

    @Test
    fun `require throws a 429 problem with Retry-After header`() {
        val limiter = RateLimiter()
        limiter.require("x", 1, Duration.ofMinutes(1))
        val problem = assertFailsWith<ProblemException> { limiter.require("x", 1, Duration.ofMinutes(1)) }
        assertEquals(429, problem.status.value)
        assertEquals("RATE_LIMITED", problem.detail)
        assertTrue(problem.headers["Retry-After"]!!.toLong() >= 1)
    }

    @Test
    fun `lock duration doubles per block of failures and is capped at 24 hours`() {
        assertEquals(Duration.ofMinutes(15), AuthRateLimits.lockDuration(10))
        assertEquals(Duration.ofMinutes(15), AuthRateLimits.lockDuration(19))
        assertEquals(Duration.ofMinutes(30), AuthRateLimits.lockDuration(20))
        assertEquals(Duration.ofHours(1), AuthRateLimits.lockDuration(30))
        assertEquals(Duration.ofHours(24), AuthRateLimits.lockDuration(200))
        assertEquals(Duration.ofHours(24), AuthRateLimits.lockDuration(10_000))
    }
}
