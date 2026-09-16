package com.vrticconnect.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParseProblemTest {

    @Test
    fun parsesFullProblemJson() {
        val body = """
            {"type":"https://vrticconnect.app/problems/validation","title":"Validation failed",
             "status":422,"detail":"email is required","instance":"api/v1/auth/login","errors":[{"field":"email"}]}
        """.trimIndent()
        val p = parseProblem(422, body, "application/problem+json")
        assertEquals("https://vrticconnect.app/problems/validation", p.type)
        assertEquals("Validation failed", p.title)
        assertEquals(422, p.status)
        assertEquals("email is required", p.detail)
        assertEquals("api/v1/auth/login", p.instance)
    }

    @Test
    fun fillsMissingStatusFromHttpStatus() {
        val p = parseProblem(501, """{"title":"Not implemented"}""", "application/problem+json")
        assertEquals(501, p.status)
        assertEquals("about:blank", p.type)
        assertTrue(p.isNotImplemented)
    }

    @Test
    fun emptyBodyFallsBackToHttpStatus() {
        val p = parseProblem(503, "", null)
        assertEquals(503, p.status)
        assertEquals("HTTP 503", p.title)
        assertNull(p.detail)
    }

    @Test
    fun nonJsonBodyFallsBackAndKeepsBodyAsDetail() {
        val p = parseProblem(502, "<html>Bad Gateway</html>", "text/html")
        assertEquals(502, p.status)
        assertEquals("<html>Bad Gateway</html>", p.detail)
    }

    @Test
    fun malformedJsonNeverThrows() {
        val p = parseProblem(500, "{not json", "application/json")
        assertEquals(500, p.status)
        assertEquals("HTTP 500", p.title)
    }

    @Test
    fun jsonArrayBodyFallsBack() {
        val p = parseProblem(400, "[1,2,3]", "application/json")
        assertEquals(400, p.status)
        assertEquals("[1,2,3]", p.detail)
    }
}
