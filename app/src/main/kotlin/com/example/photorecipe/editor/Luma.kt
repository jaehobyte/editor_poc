package com.example.photorecipe.editor

import kotlin.math.abs

/**
 * Galaxy 톤 보정의 YCbCr 루마 단계 — Brightness, Exposure, Highlights, Shadows.
 *
 * 모든 효과는 Y 채널에만 적용되고 Cb/Cr 은 보존 (색상 유지). 모든 함수의 도메인은 [0, 1].
 *
 * 상수 (galaxy_editor_python.py:333-409):
 *   brightness w     = 0.7999
 *   highlights gain  = 0.00276, offset = 0.4978 / 200 (= 1.275 in [0,1] form)
 *   shadows-  weight = 0.00301, threshold = 159.4/255
 *   shadows+  plateau = 0.28/255 · ui, linear gain = 0.0022
 *
 * !! 캘리브레이션 상수 변경 금지.
 */

private const val Y_MID = 128f / 255f
private const val Y_DARK_BOUNDARY = 159.4f / 255f

// ── YCbCr conversion (BT.601 fixed-point) ─────────────────────────

fun rgbToYCbCr(r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
    (77f * r + 150f * g + 29f * b) / 256f,
    (-43f * r - 85f * g + 128f * b) / 256f,
    (128f * r - 107f * g - 21f * b) / 256f,
)

/** 결과는 [0, 1] 범위 밖일 수 있음 — caller 가 클램프해야 함. */
fun yCbCrToRgb(y: Float, cb: Float, cr: Float): FloatArray = floatArrayOf(
    y + cr * (359f / 256f),
    y - (cb * 88f + cr * 183f) / 256f,
    y + cb * (454f / 256f),
)

// ── Per-effect Y modifiers (each clamps to [0, 1]) ─────────────────

fun applyBrightness(y: Float, ui: Float): Float {
    val u = ui.coerceIn(-100f, 100f)
    val w = 0.7999f
    val yNew = if (y < Y_MID) {
        (w * u + 127f) * y / 127f
    } else {
        w * u * (1f - y) / 127f + y
    }
    return yNew.coerceIn(0f, 1f)
}

fun applyExposure(y: Float, ui: Float): Float {
    val u = ui.coerceIn(-100f, 100f)
    return (y + u * abs(u) / 25500f).coerceIn(0f, 1f)
}

fun applyHighlights(y: Float, ui: Float): Float {
    val u = ui.coerceIn(-100f, 100f)
    return (y + 0.00276f * u * y * (1.275f * y - 0.4978f)).coerceIn(0f, 1f)
}

fun applyShadows(y: Float, ui: Float): Float {
    val u = ui.coerceIn(-100f, 100f)
    val yNew = if (u < 0f) {
        if (y <= Y_DARK_BOUNDARY) y + 0.00301f * (Y_DARK_BOUNDARY - y) * u else y
    } else {
        if (y <= Y_MID) y + 0.28f * u / 255f else y + 0.0022f * (1f - y) * u
    }
    return yNew.coerceIn(0f, 1f)
}
