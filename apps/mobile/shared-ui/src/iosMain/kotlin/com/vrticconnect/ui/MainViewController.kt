package com.vrticconnect.ui

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Entry point used by the SwiftUI host (`MainViewControllerKt.MainViewController()`). */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
