package com.example.photorecipe.editor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * GLSL `rgbToHsl` / `hslToRgb` 와 동일 알고리즘의 Kotlin 포팅.
 * 결과: H ∈ [0, 360), S/L ∈ [0, 1].
 */

fun rgbToHsl(r: Float, g: Float, b: Float, out: FloatArray) {
    val cmax = max(r, max(g, b))
    val cmin = min(r, min(g, b))
    val sum = cmax + cmin
    val l = sum * 0.5f
    if (cmax == cmin) {
        out[0] = 0f; out[1] = 0f; out[2] = l
        return
    }
    val d = cmax - cmin
    val s = if (l > 0.5f) d / (2f - sum) else d / sum
    var h = when (cmax) {
        r -> ((g - b) / d) + (if (g < b) 6f else 0f)
        g -> ((b - r) / d) + 2f
        else -> ((r - g) / d) + 4f
    }
    h *= 60f
    if (h < 0f) h += 360f
    out[0] = h; out[1] = s; out[2] = l
}

fun hslToRgb(h: Float, s: Float, l: Float, out: FloatArray) {
    if (s <= 0f) { out[0] = l; out[1] = l; out[2] = l; return }
    val c = (1f - abs(2f * l - 1f)) * s
    val hp = h / 60f
    val x = c * (1f - abs(hp.mod(2f) - 1f))
    val m = l - c * 0.5f
    val r1: Float; val g1: Float; val b1: Float
    when {
        hp < 1f -> { r1 = c; g1 = x; b1 = 0f }
        hp < 2f -> { r1 = x; g1 = c; b1 = 0f }
        hp < 3f -> { r1 = 0f; g1 = c; b1 = x }
        hp < 4f -> { r1 = 0f; g1 = x; b1 = c }
        hp < 5f -> { r1 = x; g1 = 0f; b1 = c }
        else    -> { r1 = c; g1 = 0f; b1 = x }
    }
    out[0] = r1 + m; out[1] = g1 + m; out[2] = b1 + m
}
