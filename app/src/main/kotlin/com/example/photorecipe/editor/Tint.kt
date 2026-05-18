package com.example.photorecipe.editor

/**
 * Galaxy `libphotoeditorEngine.so` Tint — luminance-weighted green ↔ magenta.
 *
 * 입력: RGB ∈ [0, 1], tintUi ∈ [-100, 100].
 * 출력: 새 RGB ∈ [0, 1] (각 채널 클램프됨).
 *
 * 알고리즘 (galaxy_editor_python.py:267-298, C lines 14220-14266):
 *   L     = 0.2126·R + 0.7152·G + 0.0722·B
 *   t     = 1.5·L          (L < 128/255)
 *           1.5·(1 - L)    (otherwise)
 *   fVar  = t²
 *
 *   tint < 0:  G *= 1 + fVar·0.0034·|tint|
 *   tint > 0:  R *= 1 + fVar·0.0032·tint
 *              B *= 1 + fVar·0.0034·tint
 *              if L < 51/255:  G *= 1 + fVar·0.0005·tint
 *
 * 모든 가중치와 임계값은 실측 캘리브레이션. !! DO NOT MODIFY.
 */
fun applyTint(r: Float, g: Float, b: Float, tintUi: Float): FloatArray {
    val tint = tintUi.coerceIn(-100f, 100f)
    if (tint == 0f) return floatArrayOf(r, g, b)

    val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
    val t = if (l < 128f / 255f) 1.5f * l else 1.5f * (1f - l)
    val fVar = t * t

    return if (tint < 0f) {
        val nt = -tint
        floatArrayOf(
            r,
            (g * (1f + fVar * 0.0034f * nt)).coerceIn(0f, 1f),
            b,
        )
    } else {
        val newG = if (l < 51f / 255f) {
            (g * (1f + fVar * 0.0005f * tint)).coerceIn(0f, 1f)
        } else g
        floatArrayOf(
            (r * (1f + fVar * 0.0032f * tint)).coerceIn(0f, 1f),
            newG,
            (b * (1f + fVar * 0.0034f * tint)).coerceIn(0f, 1f),
        )
    }
}
