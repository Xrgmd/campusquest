package com.campusquest.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

/** 与设计基线 DESIGN-BASELINE.md 一致的双主题令牌 */
class CqColors(
    val stage: Color, val bg: Color,
    val panel: Color, val panelBorder: Color,
    val card: Color, val cardBorder: Color,
    val ink: Color, val ink2: Color, val ink3: Color,
    val chipRoom: Color, val accent: Color, val accentSoft: Color,
    val todayCol: Color, val danger: Color, val warn: Color, val ok: Color,
    val cellLine: Color, val pinGlow: Color, val shadow: Color,
    val rgb: List<Color>, val course: List<Color>,
    val sheet: Color, val mask: Color,
    val isDark: Boolean,
)

val LightColors = CqColors(
    stage = Color(0xFFE7E1D5), bg = Color(0xFFF3EEE3),
    panel = Color(0x70FFFFFF), panelBorder = Color(0xC2FFFFFF),
    card = Color(0x8AFFFFFF), cardBorder = Color(0xE0FFFFFF),
    ink = Color(0xFF26221C), ink2 = Color(0xFF6F675C), ink3 = Color(0xFFA29A8D),
    chipRoom = Color(0xFF6B6355), accent = Color(0xFF4A5AA8),
    accentSoft = Color(0x1F4A5AA8), todayCol = Color(0x144A5AA8),
    danger = Color(0xFFB3402F), warn = Color(0xFFA06A1C), ok = Color(0xFF3C7A58),
    cellLine = Color(0x1F786E5A), pinGlow = Color(0x38FF9650),
    shadow = Color(0x2E5A503C), // 暖棕投影(与原型 rgba(90,80,60,.14~.18) 对齐)
    rgb = listOf(Color(0xFFFF5C5C), Color(0xFFFF9F40), Color(0xFFFFD666), Color(0xFFFF85C0)),
    course = listOf(
        Color(0xFF5B6ABF), Color(0xFF2F8F83), Color(0xFFB97A2A), Color(0xFFB4526A),
        Color(0xFF7C5CBF), Color(0xFF3E7CB1), Color(0xFF6E7F3C)
    ),
    sheet = Color(0xE6FCFAF5), mask = Color(0x6B0A0A0E),
    isDark = false,
)

val DarkColors = CqColors(
    stage = Color(0xFF08080B), bg = Color(0xFF060609),
    panel = Color(0x0DFFFFFF), panelBorder = Color(0x1AFFFFFF),
    card = Color(0x0EFFFFFF), cardBorder = Color(0x1AFFFFFF),
    ink = Color(0xFFEDEDF2), ink2 = Color(0xFFA7A7B4), ink3 = Color(0xFF6D6D7A),
    chipRoom = Color(0xFFD6D6E4), accent = Color(0xFF93A5FF),
    accentSoft = Color(0x2493A5FF), todayCol = Color(0x1793A5FF),
    danger = Color(0xFFFC8181), warn = Color(0xFFF6AD55), ok = Color(0xFF68D391),
    cellLine = Color(0x12FFFFFF), pinGlow = Color(0x425A8CFF),
    shadow = Color(0x8A000000), // 深色:柔和黑投影
    rgb = listOf(Color(0xFF4DD0FF), Color(0xFF597EF7), Color(0xFF9254DE), Color(0xFF36CFC9)),
    course = listOf(
        Color(0xFF8B9DFF), Color(0xFF4FD1C5), Color(0xFFF6AD55), Color(0xFFF687B3),
        Color(0xFFB794F4), Color(0xFF63B3ED), Color(0xFF9AE6B4)
    ),
    sheet = Color(0xE6121218), mask = Color(0x6B0A0A0E),
    isDark = true,
)

val LocalCqColors = staticCompositionLocalOf { LightColors }

@Composable
fun CqTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCqColors provides if (dark) DarkColors else LightColors) {
        content()
    }
}
