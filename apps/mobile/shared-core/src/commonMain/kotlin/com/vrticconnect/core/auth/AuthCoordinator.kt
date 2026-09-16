package com.vrticconnect.core.auth

import com.vrticconnect.core.api.ApiProblem
import com.vrticconnect.core.api.ApiResult
import com.vrticconnect.core.api.AuthApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Authentication state observed by the UI. */
sealed interface AuthState {
    data object Unauthenticated : AuthState

    /**
     * Placeholder: carries no user identity yet. EPIC 02 will add the session/user descriptor
     * returned by the backend.
     */
    data object Authenticated : AuthState
}

/**
 * Coordinates login / refresh / logout for the mobile app.
 *
 * Skeleton for the single-flight refresh design required by REQUIREMENTS_BRIEF §7:
 * concurrent callers that discover an expired access token must NOT each call
 * `auth/refresh` (the backend rotates refresh tokens atomically and treats reuse as theft).
 * Instead all callers serialize on [refreshMutex]; the first one performs the refresh and the
 * others observe the freshly stored tokens.
 *
 * Current behaviour: no tokens are issued (the backend answers 501). [state] is exposed so the
 * UI can already bind to it; [refreshSingleFlight] contains the locking shape but performs no
 * network refresh until EPIC 02.
 */
class AuthCoordinator(
    private val tokenStore: SecureTokenStore,
    private val authApi: AuthApi,
) {
    private val refreshMutex = Mutex()
    private val _state = MutableStateFlow<AuthState>(AuthState.Unauthenticated)

    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Restores state from the token store (e.g. at app start). */
    suspend fun restore() {
        _state.value = if (tokenStore.load() != null) AuthState.Authenticated else AuthState.Unauthenticated
    }

    /** Placeholder login: forwards to [AuthApi]; the backend currently returns 501 → [ApiResult.Failure]. */
    suspend fun login(email: String, password: String): ApiResult<Unit> =
        when (val result = authApi.login(email, password)) {
            is ApiResult.Success -> {
                tokenStore.save(result.value)
                _state.value = AuthState.Authenticated
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> ApiResult.Failure(result.problem)
        }

    suspend fun logout() {
        tokenStore.clear()
        _state.value = AuthState.Unauthenticated
    }

    /**
     * Single-flight refresh skeleton.
     *
     * @param staleAccess the access token the caller was rejected with. If the stored access token
     *   already differs, another caller has refreshed in the meantime and the stored pair is returned
     *   without hitting the network.
     *
     * TODO(EPIC 02): call [AuthApi.refresh], persist the rotated pair, and on a definitive
     * rejection (reuse detected / family revoked) clear the store and move to Unauthenticated.
     */
    suspend fun refreshSingleFlight(staleAccess: String?): ApiResult<SessionTokens> =
        refreshMutex.withLock {
            val current = tokenStore.load()
                ?: return@withLock ApiResult.Failure(NOT_AUTHENTICATED)
            if (staleAccess != null && current.access != staleAccess) {
                // Someone else already refreshed while we were waiting for the lock.
                return@withLock ApiResult.Success(current)
            }
            ApiResult.Failure(REFRESH_NOT_IMPLEMENTED)
        }

    private companion object {
        val NOT_AUTHENTICATED = ApiProblem(
            type = "urn:vrticconnect:problem:not-authenticated",
            title = "Not authenticated",
            status = 401,
        )
        val REFRESH_NOT_IMPLEMENTED = ApiProblem(
            type = "urn:vrticconnect:problem:not-implemented",
            title = "Refresh not implemented yet (EPIC 02)",
            status = 501,
        )
    }
}
