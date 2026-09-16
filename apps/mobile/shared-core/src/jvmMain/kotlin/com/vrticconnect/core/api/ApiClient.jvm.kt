package com.vrticconnect.core.api

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO

/** CIO is pure Kotlin and works on both desktop JVM and Android (which consumes this jvm variant). */
actual fun createPlatformEngine(): HttpClientEngine = CIO.create()
