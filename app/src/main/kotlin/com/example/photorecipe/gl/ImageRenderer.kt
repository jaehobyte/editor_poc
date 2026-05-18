package com.example.photorecipe.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import android.opengl.GLUtils as AGLUtils

/**
 * Sprint 1: 한 장의 비트맵을 풀스크린 quad에 텍스처로 렌더링.
 * 효과는 없음 — 다음 Sprint에서 효과 셰이더가 이 자리를 대체함.
 */
class ImageRenderer : GLSurfaceView.Renderer {

    private val pendingBitmap = AtomicReference<Bitmap?>(null)

    // 현재 적용할 효과 파라미터. identity = passthrough.
    @Volatile private var wb = floatArrayOf(1f, 1f, 1f)
    @Volatile private var contrast = 1f
    @Volatile private var tint = 0f
    @Volatile private var saturation = identityMat3()
    @Volatile private var brightness = 0f
    @Volatile private var exposure = 0f
    @Volatile private var highlights = 0f
    @Volatile private var shadows = 0f
    @Volatile private var colorTuningOn = false
    private val pendingColorLut = AtomicReference<FloatArray?>(null)

    private var program = 0
    private var posLoc = 0
    private var uvLoc = 0
    private var texUniform = 0
    private var wbUniform = 0
    private var contrastUniform = 0
    private var tintUniform = 0
    private var saturationUniform = 0
    private var brightnessUniform = 0
    private var exposureUniform = 0
    private var highlightsUniform = 0
    private var shadowsUniform = 0
    private var colorLutUniform = 0
    private var colorTuningOnUniform = 0
    private var colorLutTextureId = 0

    private var textureId = 0
    private var hasTexture = false

    private var vao = 0
    private var vbo = 0

    private var viewWidth = 0
    private var viewHeight = 0
    private var imageWidth = 0
    private var imageHeight = 0

    /** 비트맵 업로드 요청. GL 스레드에서 다음 frame에 처리됨. */
    fun setBitmap(bitmap: Bitmap) {
        pendingBitmap.set(bitmap)
    }

    /** Temperature WB 곱셈 계수 업데이트 (`wbMultipliers` 결과). */
    fun setWbMultipliers(wb: FloatArray) {
        require(wb.size == 3) { "wb must have 3 elements" }
        this.wb = wb
    }

    /** Contrast 곡선 계수 업데이트 (`contrastCurve` 결과). 1.0 = identity. */
    fun setContrastCurve(curve: Float) {
        this.contrast = curve
    }

    /** Tint UI 값 직접 전달 [-100, 100]. 0 = identity. 셰이더가 동일한 수식을 실행. */
    fun setTintUi(ui: Float) {
        this.tint = ui
    }

    /** Saturation 행렬 (`saturationMatrix` 의 9-element column-major 결과). */
    fun setSaturationMatrix(m: FloatArray) {
        require(m.size == 9) { "saturation matrix must have 9 elements" }
        this.saturation = m
    }

    /** Brightness / Exposure / Highlights / Shadows UI 값 — 각 [-100, 100], 0 = identity. */
    fun setLumaParams(brightnessUi: Float, exposureUi: Float, highlightsUi: Float, shadowsUi: Float) {
        this.brightness = brightnessUi.coerceIn(-100f, 100f)
        this.exposure = exposureUi.coerceIn(-100f, 100f)
        this.highlights = highlightsUi.coerceIn(-100f, 100f)
        this.shadows = shadowsUi.coerceIn(-100f, 100f)
    }

    /**
     * Color tuning 토글. enabled=true 면 [lut] (361*4 RGBA32F 평탄 배열) 을 다음 frame 에서
     * 업로드하고 셰이더가 적용. lut 가 null 이면 기존 텍스처 유지.
     */
    fun setColorTuning(enabled: Boolean, lut: FloatArray?) {
        colorTuningOn = enabled
        if (lut != null) {
            require(lut.size == 361 * 4) { "color LUT must be 361*4 = 1444 elements" }
            pendingColorLut.set(lut)
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // DESIGN.md: 캔버스 영역은 중간 회색 (이미지 색상 인식 방해 방지)
        GLES30.glClearColor(0.16f, 0.16f, 0.16f, 1f)

        val vs = compileShader(GLES30.GL_VERTEX_SHADER, PASSTHROUGH_VERT)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, EFFECTS_FRAG)
        program = linkProgram(vs, fs)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)

        posLoc = GLES30.glGetAttribLocation(program, "a_pos")
        uvLoc = GLES30.glGetAttribLocation(program, "a_uv")
        texUniform = GLES30.glGetUniformLocation(program, "u_tex")
        wbUniform = GLES30.glGetUniformLocation(program, "u_wb")
        contrastUniform = GLES30.glGetUniformLocation(program, "u_contrast")
        tintUniform = GLES30.glGetUniformLocation(program, "u_tint")
        saturationUniform = GLES30.glGetUniformLocation(program, "u_saturation")
        brightnessUniform = GLES30.glGetUniformLocation(program, "u_brightness")
        exposureUniform   = GLES30.glGetUniformLocation(program, "u_exposure")
        highlightsUniform = GLES30.glGetUniformLocation(program, "u_highlights")
        shadowsUniform    = GLES30.glGetUniformLocation(program, "u_shadows")
        colorLutUniform        = GLES30.glGetUniformLocation(program, "u_colorLut")
        colorTuningOnUniform   = GLES30.glGetUniformLocation(program, "u_colorTuningOn")

        setupQuad()
        setupTexture()
        setupColorLutTexture()
    }

    private fun setupColorLutTexture() {
        val tex = IntArray(1); GLES30.glGenTextures(1, tex, 0); colorLutTextureId = tex[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorLutTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        // 초기 zero LUT 업로드 (color tuning 꺼져 있어도 sampler binding 은 필요).
        val zeros = java.nio.ByteBuffer.allocateDirect(361 * 4 * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F,
            361, 1, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, zeros,
        )
    }

    private fun setupQuad() {
        // Triangle strip 풀스크린 quad. UV.y 는 비트맵 origin(top-left)에 맞게 뒤집음.
        val verts = floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f,
        )
        val buf = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(verts); rewind() }

        val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0); vao = vaos[0]
        val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0);       vbo = vbos[0]

        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES30.GL_STATIC_DRAW)

        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(uvLoc)
        GLES30.glVertexAttribPointer(uvLoc, 2, GLES30.GL_FLOAT, false, 16, 8)

        GLES30.glBindVertexArray(0)
    }

    private fun setupTexture() {
        val tex = IntArray(1); GLES30.glGenTextures(1, tex, 0); textureId = tex[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        applyViewport()
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingBitmap.getAndSet(null)?.let { uploadBitmap(it) }
        pendingColorLut.getAndSet(null)?.let { uploadColorLut(it) }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (!hasTexture) return

        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(texUniform, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorLutTextureId)
        GLES30.glUniform1i(colorLutUniform, 1)
        GLES30.glUniform1i(colorTuningOnUniform, if (colorTuningOn) 1 else 0)
        val wbSnapshot = wb
        GLES30.glUniform3f(wbUniform, wbSnapshot[0], wbSnapshot[1], wbSnapshot[2])
        GLES30.glUniform1f(contrastUniform, contrast)
        GLES30.glUniform1f(tintUniform, tint.coerceIn(-100f, 100f))
        GLES30.glUniformMatrix3fv(saturationUniform, 1, false, saturation, 0)
        GLES30.glUniform1f(brightnessUniform, brightness)
        GLES30.glUniform1f(exposureUniform,   exposure)
        GLES30.glUniform1f(highlightsUniform, highlights)
        GLES30.glUniform1f(shadowsUniform,    shadows)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        checkGLError("draw")
    }

    private fun uploadBitmap(bitmap: Bitmap) {
        imageWidth = bitmap.width
        imageHeight = bitmap.height
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        AGLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        hasTexture = true
        applyViewport()
    }

    private fun uploadColorLut(lut: FloatArray) {
        val buf = java.nio.ByteBuffer.allocateDirect(lut.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(lut).rewind()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorLutTextureId)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0, 0, 0,
            361, 1, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf,
        )
    }

    /** 이미지 aspect 를 유지하면서 뷰포트를 중앙 정렬 (letterbox). */
    private fun applyViewport() {
        if (viewWidth == 0 || viewHeight == 0 || imageWidth == 0 || imageHeight == 0) {
            GLES30.glViewport(0, 0, viewWidth, viewHeight)
            return
        }
        val viewAspect = viewWidth.toFloat() / viewHeight
        val imageAspect = imageWidth.toFloat() / imageHeight
        val (vw, vh) = if (imageAspect > viewAspect) {
            viewWidth to (viewWidth / imageAspect).toInt()
        } else {
            (viewHeight * imageAspect).toInt() to viewHeight
        }
        val vx = (viewWidth - vw) / 2
        val vy = (viewHeight - vh) / 2
        GLES30.glViewport(vx, vy, vw, vh)
    }
}

private fun identityMat3(): FloatArray = floatArrayOf(
    1f, 0f, 0f,
    0f, 1f, 0f,
    0f, 0f, 1f,
)
