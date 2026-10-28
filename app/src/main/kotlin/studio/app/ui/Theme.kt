package studio.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(primary = Color(0xFF7BD88F), background = Color(0xFF111418), surface = Color(0xFF1A1F26))
private val Light = lightColorScheme(primary = Color(0xFF1E7B34))

/** Segue o tema do sistema (claro/escuro). */
@Composable
fun StudioTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
