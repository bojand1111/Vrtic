package com.vrticconnect.ui

/**
 * Default API base URL for local development:
 * Android emulator reaches the host machine via 10.0.2.2, the iOS simulator via localhost.
 * Real builds will receive the URL from build configuration (later epic).
 */
expect fun defaultApiBaseUrl(): String
