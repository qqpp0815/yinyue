package com.yinyue.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 网易云风格色板（对标网易云音乐 App）：
 * 主色 #EC4141，浅色底 #F5F5F5 / 卡片白，深色底 #1A1A1A / 卡片 #262626。
 */
object YinYueColors {
    val Red = Color(0xFFEC4141)
    val RedDark = Color(0xFFD73535)
    val RedLight = Color(0xFFFF6B6B)

    // 浅色
    val LightBg = Color(0xFFF5F5F5)
    val LightCard = Color(0xFFFFFFFF)
    val LightDivider = Color(0xFFEEEEEE)
    val LightTextPrimary = Color(0xFF2B2B2B)
    val LightTextSecondary = Color(0xFF8A8A8A)
    val LightChipBg = Color(0xFFF0F0F0)

    // 深色
    val DarkBg = Color(0xFF1A1A1A)
    val DarkCard = Color(0xFF262626)
    val DarkDivider = Color(0xFF323232)
    val DarkTextPrimary = Color(0xFFEDEDED)
    val DarkTextSecondary = Color(0xFF9A9A9A)
    val DarkChipBg = Color(0xFF2E2E2E)

    val TagBg = Color(0x14EC4141) // 音质标签底色（主色 8%）
}

private val LightScheme = lightColorScheme(
    primary = YinYueColors.Red,
    onPrimary = Color.White,
    primaryContainer = YinYueColors.Red,
    onPrimaryContainer = Color.White,
    secondary = YinYueColors.RedLight,
    background = YinYueColors.LightBg,
    onBackground = YinYueColors.LightTextPrimary,
    surface = YinYueColors.LightCard,
    onSurface = YinYueColors.LightTextPrimary,
    surfaceVariant = YinYueColors.LightChipBg,
    onSurfaceVariant = YinYueColors.LightTextSecondary,
    outline = YinYueColors.LightDivider,
    error = Color(0xFFE64340),
)

private val DarkScheme = darkColorScheme(
    primary = YinYueColors.Red,
    onPrimary = Color.White,
    primaryContainer = YinYueColors.Red,
    onPrimaryContainer = Color.White,
    secondary = YinYueColors.RedLight,
    background = YinYueColors.DarkBg,
    onBackground = YinYueColors.DarkTextPrimary,
    surface = YinYueColors.DarkCard,
    onSurface = YinYueColors.DarkTextPrimary,
    surfaceVariant = YinYueColors.DarkChipBg,
    onSurfaceVariant = YinYueColors.DarkTextSecondary,
    outline = YinYueColors.DarkDivider,
    error = Color(0xFFE64340),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun YinYueTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}
