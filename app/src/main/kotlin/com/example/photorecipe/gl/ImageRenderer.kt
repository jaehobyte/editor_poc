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

    private var program = 0
    private var posLoc = 0
    private var uvLoc = 0
    private var texUniform = 0
    private var wbUniform = 0
    private var contrastUniform = 0

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

        setupQuad()
        setupTexture()
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

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (!hasTexture) return

        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(texUniform, 0)
        val wbSnapshot = wb
        GLES30.glUniform3f(wbUniform, wbSnapshot[0], wbSnapshot[1], wbSnapshot[2])
        GLES30.glUniform1f(contrastUniform, contrast)
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
