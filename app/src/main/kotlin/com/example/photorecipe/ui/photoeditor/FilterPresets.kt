package com.example.photorecipe.ui.photoeditor

import androidx.compose.ui.graphics.Color
import com.example.photorecipe.EditorParams

/**
 * 한 필터가 적용하는 톤 값들. EditorParams 의 슬라이더 8개 + Color tuning 21개.
 */
data class FilterPreset(
    val name: String,
    val chipColor: Color,
    val temperature: Float = 0f,
    val contrast: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 0f,
    val brightness: Float = 0f,
    val exposure: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val colorTuning: FloatArray = FloatArray(21),
    val enableColorTuning: Boolean = false,
) {
    /** 현재 EditorParams 에 이 프리셋을 덮어쓴다. */
    fun applyTo(p: EditorParams) {
        p.temperature = temperature
        p.contrast = contrast
        p.tint = tint
        p.saturation = saturation
        p.brightness = brightness
        p.exposure = exposure
        p.highlights = highlights
        p.shadows = shadows
        p.colorTuning = colorTuning.copyOf()
        p.colorTuningEnabled = enableColorTuning
    }
}

/**
 * 상용 포토 에디터 (Snapseed/VSCO/Lightroom/Galaxy/Apple Photos) 공통 프리셋 톤들.
 * 효과는 너무 강하지 않게 — "필터" 컨셉은 살리되 원본 보존 우선.
 */
val FILTER_PRESETS: List<FilterPreset> = listOf(
    FilterPreset(
        name = "Original",
        chipColor = Color(0xFF6B7280),
    ),
    FilterPreset(
        name = "Vivid",
        chipColor = Color(0xFFFF6B6B),
        saturation = 28f,
        contrast = 14f,
        brightness = 4f,
    ),
    FilterPreset(
        name = "Warm",
        chipColor = Color(0xFFFFB37A),
        temperature = 30f,
        saturation = 8f,
        highlights = -5f,
    ),
    FilterPreset(
        name = "Cool",
        chipColor = Color(0xFF6BD3FF),
        temperature = -28f,
        tint = -8f,
        saturation = -5f,
    ),
    FilterPreset(
        name = "Drama",
        chipColor = Color(0xFFB26BFF),
        contrast = 40f,
        shadows = -30f,
        highlights = 18f,
        saturation = -8f,
    ),
    FilterPreset(
        name = "Vintage",
        chipColor = Color(0xFFD4A26B),
        temperature = 22f,
        saturation = -18f,
        highlights = -12f,
        shadows = 18f,
        contrast = -8f,
    ),
    FilterPreset(
        name = "Fade",
        chipColor = Color(0xFFD0D4D4),
        contrast = -20f,
        shadows = 22f,
        highlights = -8f,
        saturation = -10f,
    ),
    FilterPreset(
        name = "B & W",
        chipColor = Color(0xFFE5E5E5),
        saturation = -100f,
        contrast = 12f,
    ),
    FilterPreset(
        name = "Noir",
        chipColor = Color(0xFF1A1A1A),
        saturation = -100f,
        contrast = 35f,
        shadows = -22f,
        brightness = -8f,
    ),
    FilterPreset(
        name = "Bright",
        chipColor = Color(0xFFFFE99A),
        brightness = 14f,
        exposure = 10f,
        shadows = 14f,
        saturation = 6f,
    ),
)
