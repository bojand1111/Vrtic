package com.vrticconnect.core.auth

/**
 * Opaque session token pair (REQUIREMENTS_BRIEF §7: opaque, cryptographically random tokens;
 * the client never inspects their content).
 *
 * `toString()` is redacted so tokens never end up in logs by accident.
 */
data class SessionTokens(val access: String, val refresh: String) {
    override fun toString(): String = "SessionTokens(access=<redacted>, refresh=<redacted>)"
}

/**
 * Secure storage for [SessionTokens].
 *
 * Production actuals must be backed by iOS Keychain and Android Keystore-encrypted storage
 * (planned in EPIC 02). Tokens must never be written to SharedPreferences/UserDefaults,
 * plain files or logs.
 */
interface SecureTokenStore {
    suspend fun save(tokens: SessionTokens)
    suspend fun load(): SessionTokens?
    suspend fun clear()
}

/**
 * NOT FOR PRODUCTION – placeholder until EPIC 02 implements Keychain/Keystore actuals.
 *
 * Keeps tokens only in process memory: nothing is persisted to preferences, files or disk,
 * and everything is lost when the process ends. Suitable for tests and for the skeleton UI.
 */
class InMemorySecureTokenStore : SecureTokenStore {
    private var tokens: SessionTokens? = null

    override suspend fun save(tokens: SessionTokens) {
        this.tokens = tokens
    }

    override suspend fun load(): SessionTokens? = tokens

    override suspend fun clear() {
        tokens = null
    }
}
