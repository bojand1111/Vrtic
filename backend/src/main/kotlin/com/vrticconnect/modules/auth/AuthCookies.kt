package com.vrticconnect.modules.auth

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall

/**
 * Web cookie layout (docs/SECURITY.md 2.2): access and refresh tokens are HttpOnly with narrow
 * paths; the CSRF double-submit token is readable by the SPA. `Secure` is dropped only in DEV
 * (plain http://localhost). All three are cleared together on any logout.
 */
object AuthCookies {
    const val ACCESS = "vc_access"
    const val REFRESH = "vc_refresh"
    const val CSRF = "vc_csrf"
    const val ACCESS_PATH = "/api"
    const val REFRESH_PATH = "/api/v1/auth/refresh"
    const val CSRF_PATH = "/"

    fun append(call: ApplicationCall, name: String, value: String, maxAgeSeconds: Int, path: String, httpOnly: Boolean, devMode: Boolean) {
        val secure = if (devMode) "" else "; Secure"
        val httpOnlyAttribute = if (httpOnly) "; HttpOnly" else ""
        call.response.headers.append(
            HttpHeaders.SetCookie,
            "$name=$value; Max-Age=$maxAgeSeconds; Path=$path$httpOnlyAttribute; SameSite=Lax$secure",
        )
    }

    fun clearAll(call: ApplicationCall, devMode: Boolean) {
        append(call, ACCESS, "", 0, ACCESS_PATH, httpOnly = true, devMode = devMode)
        append(call, REFRESH, "", 0, REFRESH_PATH, httpOnly = true, devMode = devMode)
        append(call, CSRF, "", 0, CSRF_PATH, httpOnly = false, devMode = devMode)
    }

    /** True when the request authenticated through the access cookie (not a Bearer header). */
    fun isCookieRequest(call: ApplicationCall): Boolean =
        call.bearerToken() == null && call.request.cookies[ACCESS] != null
}
