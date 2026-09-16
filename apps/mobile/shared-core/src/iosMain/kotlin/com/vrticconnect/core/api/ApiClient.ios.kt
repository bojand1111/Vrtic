package com.vrticconnect.core.api

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

actual fun createPlatformEngine(): HttpClientEngine = Darwin.create()
