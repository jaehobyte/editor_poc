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
 * Temperature: 픽셀별 RGB 채널을 wb 벡터로 곱하고 [0,1] 클램프.
 * wb = (1,1,1) 이면 passthrough 와 동일.
 */
const val TEMPERATURE_FRAG = """#version 300 es
precision mediump float;
in vec2 v_uv;
uniform sampler2D u_tex;
uniform vec3 u_wb;
out vec4 outColor;
void main() {
    vec4 c = texture(u_tex, v_uv);
    outColor = vec4(clamp(c.rgb * u_wb, 0.0, 1.0), c.a);
}
"""
