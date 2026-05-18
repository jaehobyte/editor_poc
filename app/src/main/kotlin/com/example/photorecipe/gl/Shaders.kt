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
 *   (다음 sprints: Tint, Saturation, Brightness, Exposure, Highlights, Shadows)
 *
 * 모든 uniform 의 identity 값:
 *   u_wb       = (1, 1, 1)
 *   u_contrast = 1.0
 */
const val EFFECTS_FRAG = """#version 300 es
precision mediump float;
in vec2 v_uv;
uniform sampler2D u_tex;
uniform vec3 u_wb;
uniform float u_contrast;
out vec4 outColor;
void main() {
    vec4 c = texture(u_tex, v_uv);
    vec3 rgb = c.rgb;

    // 1. Temperature
    rgb = clamp(rgb * u_wb, 0.0, 1.0);

    // 2. Contrast
    rgb = clamp(0.5 + u_contrast * (rgb - 0.5), 0.0, 1.0);

    outColor = vec4(rgb, c.a);
}
"""
