package com.jdpneto.stackstackstack.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme()

/**
 * Stack Stack Stack dark-mode Material3 theme. The app is always dark (capture screen is
 * black-background; gallery/editor follow the same dark scheme). Mirrors the iOS
 * `.preferredColorScheme(.dark)` / `Color.black` background pattern. (spec §15.1)
 */
@Composable
fun StackTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
