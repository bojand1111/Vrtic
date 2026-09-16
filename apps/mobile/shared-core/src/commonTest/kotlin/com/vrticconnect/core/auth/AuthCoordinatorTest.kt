package com.vrticconnect.core.auth

import com.vrticconnect.core.api.ApiProblem
import com.vrticconnect.core.api.ApiResult
import com.vrticconnect.core.api.AuthApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class AuthCoordinatorTest {

    private val notImplementedApi = object : AuthApi {
        override suspend fun login(email: String, password: String) =
            ApiResult.Failure(ApiProblem(title = "Not Implemented", status = 501))

        override suspend fun refresh(refreshToken: String) =
            ApiResult.Failure(ApiProblem(title = "Not Implemented", status = 501))
    }

    @Test
    fun startsUnauthenticatedAndStaysSoWhenBackendReturns501() = runTest {
        val store = InMemorySecureTokenStore()
        val coordinator = AuthCoordinator(store, notImplementedApi)
        assertEquals(AuthState.Unauthenticated, coordinator.state.value)

        val result = coordinator.login("a@b.c", "secret")
        assertIs<ApiResult.Failure>(result)
        assertEquals(501, result.problem.status)
        assertEquals(AuthState.Unauthenticated, coordinator.state.value)
        assertNull(store.load(), "no tokens must be issued while login is not implemented")
    }

    @Test
    fun refreshWithoutSessionFails() = runTest {
        val coordinator = AuthCoordinator(InMemorySecureTokenStore(), notImplementedApi)
        val result = coordinator.refreshSingleFlight(staleAccess = null)
        assertIs<ApiResult.Failure>(result)
        assertEquals(401, result.problem.status)
    }

    @Test
    fun inMemoryStoreRoundTripsAndRedactsToString() = runTest {
        val store = InMemorySecureTokenStore()
        val tokens = SessionTokens(access = "acc-123", refresh = "ref-456")
        store.save(tokens)
        assertEquals(tokens, store.load())
        assertFalse(tokens.toString().contains("acc-123"))
        assertFalse(tokens.toString().contains("ref-456"))
        store.clear()
        assertNull(store.load())
    }
}
