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
 * Multi-target renderer: applies the same 9-stage pipeline TWICE per pixel —
 * once with the "global" parameter set, once with the "mask" parameter set —
 * and mixes them by the mask alpha when a mask texture is provided.
 */
class ImageRenderer : GLSurfaceView.Renderer {

    // ─── upload requests (any thread → GL thread) ────────────────────
    private val pendingBitmap = AtomicReference<Bitmap?>(null)
    private val pendingMaskBitmap = AtomicReference<Bitmap?>(null)
    private val pendingColorLut0 = AtomicReference<FloatArray?>(null)
    private val pendingColorLut1 = AtomicReference<FloatArray?>(null)

    // ─── global params (target index 0) ──────────────────────────────
    @Volatile private var wb0 = floatArrayOf(1f, 1f, 1f)
    @Volatile private var contrast0 = 1f
    @Volatile private var tint0 = 0f
    @Volatile private var saturation0 = identityMat3()
    @Volatile private var brightness0 = 0f
    @Volatile private var exposure0 = 0f
    @Volatile private var highlights0 = 0f
    @Volatile private var shadows0 = 0f
    @Volatile private var colorTuningOn0 = false

    // ─── mask params (target index 1) ─────────────────────────────────
    @Volatile private var wb1 = floatArrayOf(1f, 1f, 1f)
    @Volatile private var contrast1 = 1f
    @Volatile private var tint1 = 0f
    @Volatile private var saturation1 = identityMat3()
    @Volatile private var brightness1 = 0f
    @Volatile private var exposure1 = 0f
    @Volatile private var highlights1 = 0f
    @Volatile private var shadows1 = 0f
    @Volatile private var colorTuningOn1 = false
    @Volatile private var hasMask = false

    // ─── GL handles ──────────────────────────────────────────────────
    private var program = 0
    private var posLoc = 0
    private var uvLoc = 0
    private var texUniform = 0
    // Global uniforms
    private var u_wb0 = 0
    private var u_contrast0 = 0
    private var u_tint0 = 0
    private var u_saturation0 = 0
    private var u_brightness0 = 0
    private var u_exposure0 = 0
    private var u_highlights0 = 0
    private var u_shadows0 = 0
    private var u_colorLut0 = 0
    private var u_colorTuningOn0 = 0
    // Mask uniforms
    private var u_wb1 = 0
    private var u_contrast1 = 0
    private var u_tint1 = 0
    private var u_saturation1 = 0
    private var u_brightness1 = 0
    private var u_exposure1 = 0
    private var u_highlights1 = 0
    private var u_shadows1 = 0
    private var u_colorLut1 = 0
    private var u_colorTuningOn1 = 0
    private var u_mask = 0
    private var u_hasMask = 0

    private var imgTextureId = 0
    private var maskTextureId = 0
    private var colorLut0TextureId = 0
    private var colorLut1TextureId = 0
    private var hasTexture = false
    private var hasMaskTexture = false

    private var vao = 0
    private var vbo = 0

    private var viewWidth = 0
    private var viewHeight = 0
    private var imageWidth = 0
    private var imageHeight = 0

    // ─── public API ──────────────────────────────────────────────────

    fun setBitmap(bitmap: Bitmap) { pendingBitmap.set(bitmap) }

    /**
     * Set or clear the mask. Pass null to disable masking (returns to global-only).
     */
    fun setMask(bitmap: Bitmap?) {
        if (bitmap == null) {
            hasMask = false
            // signal "clear" — drawn next frame
        } else {
            hasMask = true
            pendingMaskBitmap.set(bitmap)
        }
    }

    /** Global (target 0) — applied to entire image, then optionally overridden by mask. */
    fun setGlobalParams(
        wb: FloatArray, contrast: Float, tint: Float, saturation: FloatArray,
        brightness: Float, exposure: Float, highlights: Float, shadows: Float,
        colorTuningOn: Boolean, colorLut: FloatArray?,
    ) {
        wb0 = wb
        contrast0 = contrast
        tint0 = tint.coerceIn(-100f, 100f)
        saturation0 = saturation
        brightness0 = brightness.coerceIn(-100f, 100f)
        exposure0 = exposure.coerceIn(-100f, 100f)
        highlights0 = highlights.coerceIn(-100f, 100f)
        shadows0 = shadows.coerceIn(-100f, 100f)
        colorTuningOn0 = colorTuningOn
        if (colorLut != null) {
            require(colorLut.size == 361 * 4)
            pendingColorLut0.set(colorLut)
        }
    }

    /** Mask (target 1). Effective only when a mask bitmap is also set. */
    fun setMaskParams(
        wb: FloatArray, contrast: Float, tint: Float, saturation: FloatArray,
        brightness: Float, exposure: Float, highlights: Float, shadows: Float,
        colorTuningOn: Boolean, colorLut: FloatArray?,
    ) {
        wb1 = wb
        contrast1 = contrast
        tint1 = tint.coerceIn(-100f, 100f)
        saturation1 = saturation
        brightness1 = brightness.coerceIn(-100f, 100f)
        exposure1 = exposure.coerceIn(-100f, 100f)
        highlights1 = highlights.coerceIn(-100f, 100f)
        shadows1 = shadows.coerceIn(-100f, 100f)
        colorTuningOn1 = colorTuningOn
        if (colorLut != null) {
            require(colorLut.size == 361 * 4)
            pendingColorLut1.set(colorLut)
        }
    }

    // ─── Renderer ────────────────────────────────────────────────────

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.16f, 0.16f, 0.16f, 1f)

        val vs = compileShader(GLES30.GL_VERTEX_SHADER, PASSTHROUGH_VERT)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, EFFECTS_FRAG)
        program = linkProgram(vs, fs)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)

        posLoc = GLES30.glGetAttribLocation(program, "a_pos")
        uvLoc = GLES30.glGetAttribLocation(program, "a_uv")
        texUniform = GLES30.glGetUniformLocation(program, "u_tex")

        u_wb0 = GLES30.glGetUniformLocation(program, "u_wb0")
        u_contrast0 = GLES30.glGetUniformLocation(program, "u_contrast0")
        u_tint0 = GLES30.glGetUniformLocation(program, "u_tint0")
        u_saturation0 = GLES30.glGetUniformLocation(program, "u_saturation0")
        u_brightness0 = GLES30.glGetUniformLocation(program, "u_brightness0")
        u_exposure0 = GLES30.glGetUniformLocation(program, "u_exposure0")
        u_highlights0 = GLES30.glGetUniformLocation(program, "u_highlights0")
        u_shadows0 = GLES30.glGetUniformLocation(program, "u_shadows0")
        u_colorLut0 = GLES30.glGetUniformLocation(program, "u_colorLut0")
        u_colorTuningOn0 = GLES30.glGetUniformLocation(program, "u_colorTuningOn0")

        u_wb1 = GLES30.glGetUniformLocation(program, "u_wb1")
        u_contrast1 = GLES30.glGetUniformLocation(program, "u_contrast1")
        u_tint1 = GLES30.glGetUniformLocation(program, "u_tint1")
        u_saturation1 = GLES30.glGetUniformLocation(program, "u_saturation1")
        u_brightness1 = GLES30.glGetUniformLocation(program, "u_brightness1")
        u_exposure1 = GLES30.glGetUniformLocation(program, "u_exposure1")
        u_highlights1 = GLES30.glGetUniformLocation(program, "u_highlights1")
        u_shadows1 = GLES30.glGetUniformLocation(program, "u_shadows1")
        u_colorLut1 = GLES30.glGetUniformLocation(program, "u_colorLut1")
        u_colorTuningOn1 = GLES30.glGetUniformLocation(program, "u_colorTuningOn1")

        u_mask = GLES30.glGetUniformLocation(program, "u_mask")
        u_hasMask = GLES30.glGetUniformLocation(program, "u_hasMask")

        setupQuad()
        imgTextureId = makeRgbaTexture()
        maskTextureId = makeRgbaTexture()
        colorLut0TextureId = makeColorLutTexture()
        colorLut1TextureId = makeColorLutTexture()
    }

    private fun setupQuad() {
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

    private fun makeRgbaTexture(): Int {
        val tex = IntArray(1); GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    private fun makeColorLutTexture(): Int {
        val tex = IntArray(1); GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val zeros = ByteBuffer.allocateDirect(361 * 4 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F,
            361, 1, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, zeros,
        )
        return tex[0]
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width; viewHeight = height
        applyViewport()
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingBitmap.getAndSet(null)?.let { uploadBitmap(it) }
        pendingMaskBitmap.getAndSet(null)?.let { uploadMask(it) }
        pendingColorLut0.getAndSet(null)?.let { uploadColorLut(colorLut0TextureId, it) }
        pendingColorLut1.getAndSet(null)?.let { uploadColorLut(colorLut1TextureId, it) }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (!hasTexture) return

        GLES30.glUseProgram(program)

        // Bind 4 texture units
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imgTextureId)
        GLES30.glUniform1i(texUniform, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorLut0TextureId)
        GLES30.glUniform1i(u_colorLut0, 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorLut1TextureId)
        GLES30.glUniform1i(u_colorLut1, 2)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
        GLES30.glUniform1i(u_mask, 3)

        // Global
        GLES30.glUniform3f(u_wb0, wb0[0], wb0[1], wb0[2])
        GLES30.glUniform1f(u_contrast0, contrast0)
        GLES30.glUniform1f(u_tint0, tint0)
        GLES30.glUniformMatrix3fv(u_saturation0, 1, false, saturation0, 0)
        GLES30.glUniform1f(u_brightness0, brightness0)
        GLES30.glUniform1f(u_exposure0, exposure0)
        GLES30.glUniform1f(u_highlights0, highlights0)
        GLES30.glUniform1f(u_shadows0, shadows0)
        GLES30.glUniform1i(u_colorTuningOn0, if (colorTuningOn0) 1 else 0)

        // Mask
        GLES30.glUniform3f(u_wb1, wb1[0], wb1[1], wb1[2])
        GLES30.glUniform1f(u_contrast1, contrast1)
        GLES30.glUniform1f(u_tint1, tint1)
        GLES30.glUniformMatrix3fv(u_saturation1, 1, false, saturation1, 0)
        GLES30.glUniform1f(u_brightness1, brightness1)
        GLES30.glUniform1f(u_exposure1, exposure1)
        GLES30.glUniform1f(u_highlights1, highlights1)
        GLES30.glUniform1f(u_shadows1, shadows1)
        GLES30.glUniform1i(u_colorTuningOn1, if (colorTuningOn1) 1 else 0)

        GLES30.glUniform1i(u_hasMask, if (hasMask && hasMaskTexture) 1 else 0)

        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        checkGLError("draw")
    }

    private fun uploadBitmap(bitmap: Bitmap) {
        imageWidth = bitmap.width
        imageHeight = bitmap.height
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imgTextureId)
        AGLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        hasTexture = true
        applyViewport()
    }

    private fun uploadMask(bitmap: Bitmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
        AGLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        hasMaskTexture = true
    }

    private fun uploadColorLut(textureId: Int, lut: FloatArray) {
        val buf = ByteBuffer.allocateDirect(lut.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(lut).rewind()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0, 0, 0,
            361, 1, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf,
        )
    }

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
