package org.evsyukov.post.ui

import androidx.compose.runtime.staticCompositionLocalOf
import org.evsyukov.post.AppContainer
import org.evsyukov.post.oauth.BrowserLauncher

/** The app's objects, provided by MainActivity; screens that read data take them from here. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("No AppContainer: MainActivity provides it") }

/** Opens sign-in pages; MainActivity provides the Auth Tab launcher. Without one, nothing opens. */
val LocalBrowserLauncher = staticCompositionLocalOf<BrowserLauncher> { BrowserLauncher { false } }
