package com.ngi.agentjin.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Gold = Color(0xFFE7A83F)
val GoldPale = Color(0xFFF7D98A)
val GoldDeep = Color(0xFFC97A1F)
val PurpleDeep = Color(0xFF100A29)
val Purple = Color(0xFF241A4A)
val PurpleMid = Color(0xFF2E245C)
val Ink = Color(0xFFEDE8FF)
val Muted = Color(0xFFB7A8D9)
val Danger = Color(0xFFE07070)

private val scheme = darkColorScheme(
    primary = Gold,
    onPrimary = PurpleDeep,
    secondary = GoldPale,
    background = PurpleDeep,
    surface = Purple,
    onBackground = Ink,
    onSurface = Ink,
    surfaceVariant = PurpleMid,
    onSurfaceVariant = Muted,
    error = Danger,
    outline = Color(0xFF5A4A86),
)

@Composable
fun AgentJinTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(),
        content = content,
    )
}
