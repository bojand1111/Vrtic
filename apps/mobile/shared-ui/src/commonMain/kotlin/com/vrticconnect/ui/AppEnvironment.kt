package com.vrticconnect.ui

import com.vrticconnect.core.api.ApiConfig
import com.vrticconnect.core.api.AuthApi
import com.vrticconnect.core.api.DefaultAuthApi
import com.vrticconnect.core.api.HealthApi
import com.vrticconnect.core.api.createHttpClient
import com.vrticconnect.core.auth.AuthCoordinator
import com.vrticconnect.core.auth.InMemorySecureTokenStore
import com.vrticconnect.core.auth.SecureTokenStore

/**
 * Hand-wired dependency graph for the skeleton (constructor injection, no DI framework yet).
 *
 * [tokenStore] defaults to the in-memory placeholder — NOT FOR PRODUCTION; EPIC 02 replaces it
 * with Keychain/Keystore-backed actuals.
 */
class AppEnvironment(
    val config: ApiConfig = ApiConfig(defaultApiBaseUrl()),
    val tokenStore: SecureTokenStore = InMemorySecureTokenStore(),
) {
    val httpClient = createHttpClient(config)
    val healthApi: HealthApi = HealthApi(httpClient)
    val authApi: AuthApi = DefaultAuthApi(httpClient)
    val authCoordinator: AuthCoordinator = AuthCoordinator(tokenStore, authApi)
}
