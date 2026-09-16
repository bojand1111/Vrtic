package com.vrticconnect.ui

/** The iOS simulator shares the host network, so localhost reaches the local backend. */
actual fun defaultApiBaseUrl(): String = "http://localhost:8080/"
