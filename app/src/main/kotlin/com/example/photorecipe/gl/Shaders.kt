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
 * Galaxy 톤 효과를 순차 적용하는 통합 fragment shader.
 *
 * 적용 순서 (galaxy_editor_python.py 파이프라인):
 *   1. Temperature  — RGB 채널별 wb 곱셈
 *   2. Contrast     — 0.5 + curve * (x - 0.5)
 *   3. Tint         — luminance-weighted green ↔ magenta
 *   4. Saturation   — fixed-point 3x3 mixing matrix
 *   5-8. Brightness / Exposure / Highlights / Shadows — YCbCr Y 채널 위에서 순차 적용
 *   (다음 sprint: 7색 × HSL Color Tuning)
 *
 * 모든 uniform 의 identity 값:
 *   u_wb         = (1, 1, 1)
 *   u_contrast   = 1.0
 *   u_tint       = 0.0
 *   u_saturation = identity mat3
 *   u_brightness, u_exposure, u_highlights, u_shadows = 0.0
 */
const val EFFECTS_FRAG = """#version 300 es
precision mediump float;
in vec2 v_uv;
uniform sampler2D u_tex;
uniform vec3 u_wb;
uniform float u_contrast;
uniform float u_tint;
uniform mat3 u_saturation;
uniform float u_brightness;
uniform float u_exposure;
uniform float u_highlights;
uniform float u_shadows;
out vec4 outColor;

const float L_MID         = 128.0 / 255.0;
const float L_DARK        = 51.0  / 255.0;
const float Y_DARK_BOUND  = 159.4 / 255.0;

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

void main() {
    vec4 c = texture(u_tex, v_uv);
    vec3 rgb = c.rgb;

    // 1. Temperature
    rgb = clamp(rgb * u_wb, 0.0, 1.0);

    // 2. Contrast
    rgb = clamp(0.5 + u_contrast * (rgb - 0.5), 0.0, 1.0);

    // 3. Tint
    rgb = applyTint(rgb, u_tint);

    // 4. Saturation
    rgb = clamp(u_saturation * rgb, 0.0, 1.0);

    // 5-8. Brightness / Exposure / Highlights / Shadows on Y
    vec3 ycc = rgbToYCbCr(rgb);
    float y = ycc.x;
    y = applyBrightnessY(y, u_brightness);
    y = applyExposureY  (y, u_exposure);
    y = applyHighlightsY(y, u_highlights);
    y = applyShadowsY   (y, u_shadows);
    rgb = clamp(yCbCrToRgb(y, ycc.y, ycc.z), 0.0, 1.0);

    outColor = vec4(rgb, c.a);
}
"""
