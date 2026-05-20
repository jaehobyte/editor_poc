package com.example.photorecipe.gl

const val PASSTHROUGH_VERT = """#version 300 es
in vec2 a_pos;
in vec2 a_uv;
out vec2 v_uv;
void main() {
    v_uv = a_uv;
    gl_Position = vec4(a_pos, 0.0, 1.0);
}
"""

const val PASSTHROUGH_FRAG = """#version 300 es
precision mediump float;
in vec2 v_uv;
uniform sampler2D u_tex;
out vec4 outColor;
void main() {
    outColor = texture(u_tex, v_uv);
}
"""

/**
 * Galaxy 톤 효과 + 색상 튜닝 + 마스크 합성을 처리하는 통합 fragment shader.
 *
 * 픽셀별 흐름:
 *   1. base = sample input
 *   2. global = applyAll(base, global params, u_colorLut0)
 *   3. if (u_hasMask) {
 *        m = applyAll(base, mask params, u_colorLut1)
 *        alpha = sample mask texture R
 *        out = mix(global, m, alpha)
 *      } else out = global
 *
 * 텍스처 유닛:
 *   TEXTURE0 = u_tex          (input image, RGBA8)
 *   TEXTURE1 = u_colorLut0    (global color tuning LUT, 361×1 RGBA32F)
 *   TEXTURE2 = u_colorLut1    (mask color tuning LUT)
 *   TEXTURE3 = u_mask         (mask alpha, RGBA8 grayscale)
 */
const val EFFECTS_FRAG = """#version 300 es
precision mediump float;
in vec2 v_uv;

uniform sampler2D u_tex;

// Global params
uniform vec3 u_wb0;
uniform float u_contrast0;
uniform float u_tint0;
uniform mat3 u_saturation0;
uniform float u_brightness0;
uniform float u_exposure0;
uniform float u_highlights0;
uniform float u_shadows0;
uniform sampler2D u_colorLut0;
uniform bool u_colorTuningOn0;

// Mask params
uniform vec3 u_wb1;
uniform float u_contrast1;
uniform float u_tint1;
uniform mat3 u_saturation1;
uniform float u_brightness1;
uniform float u_exposure1;
uniform float u_highlights1;
uniform float u_shadows1;
uniform sampler2D u_colorLut1;
uniform bool u_colorTuningOn1;

// Mask
uniform sampler2D u_mask;
uniform bool u_hasMask;

out vec4 outColor;

const float L_MID         = 128.0 / 255.0;
const float L_DARK        = 51.0  / 255.0;
const float Y_DARK_BOUND  = 159.4 / 255.0;

// ── Tint -----------------------------------------------------------
vec3 applyTint(vec3 rgb, float tint) {
    if (tint == 0.0) return rgb;
    float L = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    float t = (L < L_MID) ? (1.5 * L) : (1.5 * (1.0 - L));
    float fVar = t * t;
    if (tint < 0.0) {
        float nt = -tint;
        rgb.g = clamp(rgb.g * (1.0 + fVar * 0.0034 * nt), 0.0, 1.0);
    } else {
        rgb.r = clamp(rgb.r * (1.0 + fVar * 0.0032 * tint), 0.0, 1.0);
        rgb.b = clamp(rgb.b * (1.0 + fVar * 0.0034 * tint), 0.0, 1.0);
        if (L < L_DARK) {
            rgb.g = clamp(rgb.g * (1.0 + fVar * 0.0005 * tint), 0.0, 1.0);
        }
    }
    return rgb;
}

// ── YCbCr ---------------------------------------------------------
vec3 rgbToYCbCr(vec3 rgb) {
    return vec3(
        dot(rgb, vec3( 77.0, 150.0,  29.0)) / 256.0,
        dot(rgb, vec3(-43.0, -85.0, 128.0)) / 256.0,
        dot(rgb, vec3(128.0, -107.0, -21.0)) / 256.0
    );
}
vec3 yCbCrToRgb(float y, float cb, float cr) {
    return vec3(
        y + cr * (359.0 / 256.0),
        y - (cb * 88.0 + cr * 183.0) / 256.0,
        y + cb * (454.0 / 256.0)
    );
}

float applyBrightnessY(float y, float ui) {
    float w = 0.7999;
    float yn = (y < L_MID)
        ? (w * ui + 127.0) * y / 127.0
        : w * ui * (1.0 - y) / 127.0 + y;
    return clamp(yn, 0.0, 1.0);
}
float applyExposureY(float y, float ui) {
    return clamp(y + ui * abs(ui) / 25500.0, 0.0, 1.0);
}
float applyHighlightsY(float y, float ui) {
    return clamp(y + 0.00276 * ui * y * (1.275 * y - 0.4978), 0.0, 1.0);
}
float applyShadowsY(float y, float ui) {
    float yn;
    if (ui < 0.0) {
        yn = (y <= Y_DARK_BOUND) ? (y + 0.00301 * (Y_DARK_BOUND - y) * ui) : y;
    } else {
        yn = (y <= L_MID) ? (y + 0.28 * ui / 255.0) : (y + 0.0022 * (1.0 - y) * ui);
    }
    return clamp(yn, 0.0, 1.0);
}

// ── HSL -----------------------------------------------------------
vec3 rgbToHsl(vec3 c) {
    float maxC = max(max(c.r, c.g), c.b);
    float minC = min(min(c.r, c.g), c.b);
    float l = (maxC + minC) * 0.5;
    float h = 0.0;
    float s = 0.0;
    if (maxC != minC) {
        float d = maxC - minC;
        s = (l > 0.5) ? d / (2.0 - maxC - minC) : d / (maxC + minC);
        if (maxC == c.r) h = (c.g - c.b) / d + (c.g < c.b ? 6.0 : 0.0);
        else if (maxC == c.g) h = (c.b - c.r) / d + 2.0;
        else h = (c.r - c.g) / d + 4.0;
        h *= 60.0;
    }
    return vec3(h, s, l);
}
float hueToRgb(float p, float q, float t) {
    if (t < 0.0) t += 1.0;
    if (t > 1.0) t -= 1.0;
    if (t < 1.0 / 6.0) return p + (q - p) * 6.0 * t;
    if (t < 0.5)       return q;
    if (t < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
    return p;
}
vec3 hslToRgb(float h, float s, float l) {
    if (s == 0.0) return vec3(l);
    float q = (l < 0.5) ? (l * (1.0 + s)) : (l + s - l * s);
    float p = 2.0 * l - q;
    float hn = h / 360.0;
    return vec3(
        hueToRgb(p, q, hn + 1.0 / 3.0),
        hueToRgb(p, q, hn),
        hueToRgb(p, q, hn - 1.0 / 3.0)
    );
}

// ── Full 9-stage pipeline as a function -----------------------------
vec3 applyAll(
    vec3 rgb,
    vec3 wb, float contrast, float tint, mat3 sat,
    float brightness, float exposure, float highlights, float shadows,
    bool colorTuningOn, sampler2D colorLut
) {
    // 1. Temperature
    rgb = clamp(rgb * wb, 0.0, 1.0);
    // 2. Contrast
    rgb = clamp(0.5 + contrast * (rgb - 0.5), 0.0, 1.0);
    // 3. Tint
    rgb = applyTint(rgb, tint);
    // 4. Saturation
    rgb = clamp(sat * rgb, 0.0, 1.0);
    // 5-8. Luma on Y
    vec3 ycc = rgbToYCbCr(rgb);
    float y = ycc.x;
    y = applyBrightnessY(y, brightness);
    y = applyExposureY  (y, exposure);
    y = applyHighlightsY(y, highlights);
    y = applyShadowsY   (y, shadows);
    rgb = clamp(yCbCrToRgb(y, ycc.y, ycc.z), 0.0, 1.0);
    // 9. Color Tuning
    if (colorTuningOn) {
        vec3 hsl = rgbToHsl(rgb);
        int idx = clamp(int(floor(hsl.x + 0.5)), 0, 360);
        vec4 shift = texelFetch(colorLut, ivec2(idx, 0), 0);
        float hN = mod(hsl.x + shift.r, 360.0);
        if (hN < 0.0) hN += 360.0;
        float sN = clamp(hsl.y + shift.g, 0.0, 1.0);
        float lN = clamp(hsl.z + shift.b, 0.0, 1.0);
        rgb = clamp(hslToRgb(hN, sN, lN), 0.0, 1.0);
    }
    return rgb;
}

void main() {
    vec4 c = texture(u_tex, v_uv);
    vec3 rgb = c.rgb;

    vec3 globalResult = applyAll(
        rgb, u_wb0, u_contrast0, u_tint0, u_saturation0,
        u_brightness0, u_exposure0, u_highlights0, u_shadows0,
        u_colorTuningOn0, u_colorLut0
    );

    vec3 finalRgb = globalResult;
    if (u_hasMask) {
        float a = texture(u_mask, v_uv).a;
        if (a > 0.001) {
            vec3 maskResult = applyAll(
                rgb, u_wb1, u_contrast1, u_tint1, u_saturation1,
                u_brightness1, u_exposure1, u_highlights1, u_shadows1,
                u_colorTuningOn1, u_colorLut1
            );
            finalRgb = mix(globalResult, maskResult, a);
        }
    }

    outColor = vec4(finalRgb, c.a);
}
"""
