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
 *   (다음 sprints: Brightness, Exposure, Highlights, Shadows)
 *
 * 모든 uniform 의 identity 값:
 *   u_wb         = (1, 1, 1)
 *   u_contrast   = 1.0
 *   u_tint       = 0.0
 *   u_saturation = identity mat3
 */
const val EFFECTS_FRAG = """#version 300 es
precision mediump float;
in vec2 v_uv;
uniform sampler2D u_tex;
uniform vec3 u_wb;
uniform float u_contrast;
uniform float u_tint;
uniform mat3 u_saturation;
out vec4 outColor;

const float L_MID  = 128.0 / 255.0;
const float L_DARK = 51.0  / 255.0;

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

    outColor = vec4(rgb, c.a);
}
"""
