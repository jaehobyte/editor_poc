package com.example.photorecipe.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Runway 토큰 (DESIGN.md) ─────────────────────────────────────────
object PhotoColors {
    val RunwayBlack = Color(0xFF000000)
    val DeepBlack = Color(0xFF030303)
    val DarkSurface = Color(0xFF1A1A1A)
    val BorderDark = Color(0xFF27272A)
    val PureWhite = Color(0xFFFFFFFF)
    val CoolSlate = Color(0xFF767D88)
    val MidSlate = Color(0xFF7D848E)
    val MutedGray = Color(0xFFA7A7A7)
    val CoolSilver = Color(0xFFC9CCD1)
    // 캔버스 배경: 사진의 색상 인식을 방해하지 않는 중성 회색 (AGENTS.md 규칙)
    val Canvas = Color(0xFF2A2A2A)
}

private val Scheme = darkColorScheme(
    primary = PhotoColors.PureWhite,
    onPrimary = PhotoColors.RunwayBlack,
    secondary = PhotoColors.CoolSlate,
    background = PhotoColors.RunwayBlack,
    onBackground = PhotoColors.PureWhite,
    surface = PhotoColors.DarkSurface,
    onSurface = PhotoColors.PureWhite,
    surfaceVariant = PhotoColors.DeepBlack,
    onSurfaceVariant = PhotoColors.CoolSilver,
    outline = PhotoColors.BorderDark,
    outlineVariant = PhotoColors.BorderDark,
)

// abcNormal 은 라이선스 자산이라 시스템 sans-serif 로 대체.
// 핵심 규칙: 단일 폰트 패밀리, tight letter-spacing.
private val DisplayFont = FontFamily.SansSerif
private val MonoFont = FontFamily.Monospace

private val PhotoTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = DisplayFont, fontSize = 48.sp, fontWeight = FontWeight.Medium,
        lineHeight = 48.sp, letterSpacing = (-1.0).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = DisplayFont, fontSize = 28.sp, fontWeight = FontWeight.Medium,
        lineHeight = 32.sp, letterSpacing = (-0.6).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = DisplayFont, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
        lineHeight = 22.sp, letterSpacing = (-0.2).sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = DisplayFont, fontSize = 14.sp, fontWeight = FontWeight.Normal,
        lineHeight = 20.sp, letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = DisplayFont, fontSize = 12.sp, fontWeight = FontWeight.Normal,
        lineHeight = 16.sp, letterSpacing = 0.sp, color = PhotoColors.MidSlate,
    ),
    labelLarge = TextStyle(
        fontFamily = DisplayFont, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        lineHeight = 18.sp, letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = DisplayFont, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        lineHeight = 14.sp, letterSpacing = 0.8.sp, // 11px 대문자 라벨 컨벤션
    ),
)

@Composable
fun NewCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = PhotoTypography,
        content = content,
    )
}

@Composable
fun monoStyle(): TextStyle =
    MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont)
