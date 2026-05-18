package com.example.photorecipe.editor

/**
 * 순차 보간 인자.
 *
 * 전체 진행도 t ∈ [0, 1] 을 두 페이즈로 나눈다:
 *   - 톤 페이즈   [0, split]   — 톤 8개를 0 → target 으로 보간
 *   - 컬러 페이즈 [split, 1]   — 컬러 튜닝 21개를 0 → target 으로 보간
 *
 * 각 페이즈 안에서는 cubic smoothstep (3t² − 2t³) 이징을 적용.
 * 페이즈 밖에서는 항상 0 (시작 전) 또는 1 (끝남).
 */

private fun smoothstep(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

fun toneAnimationFactor(t: Float, split: Float = 0.5f): Float {
    require(split > 0f && split <= 1f) { "split must be in (0, 1] (got $split)" }
    val raw = (t / split).coerceIn(0f, 1f)
    return smoothstep(raw)
}

fun colorAnimationFactor(t: Float, split: Float = 0.5f): Float {
    require(split in 0f..1f) { "split must be in [0, 1] (got $split)" }
    if (split >= 1f) return 0f
    val raw = ((t - split) / (1f - split)).coerceIn(0f, 1f)
    return smoothstep(raw)
}
