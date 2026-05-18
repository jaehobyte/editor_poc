package com.example.photorecipe.ui

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.photorecipe.editor.contrastCurve
import com.example.photorecipe.editor.saturationMatrix
import com.example.photorecipe.editor.wbMultipliers
import com.example.photorecipe.gl.ImageRenderer

/**
 * Compose 안에 GLSurfaceView 를 띄우는 래퍼. 모든 ui 값은 [-100, 100], 0 = identity.
 */
@Composable
fun ImageGLView(
    bitmap: Bitmap,
    temperatureUi: Float = 0f,
    contrastUi: Float = 0f,
    tintUi: Float = 0f,
    saturationUi: Float = 0f,
    brightnessUi: Float = 0f,
    exposureUi: Float = 0f,
    highlightsUi: Float = 0f,
    shadowsUi: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val renderer = remember { ImageRenderer() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(3)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }
        },
        update = { view ->
            renderer.setBitmap(bitmap)
            renderer.setWbMultipliers(wbMultipliers(temperatureUi))
            renderer.setContrastCurve(contrastCurve(contrastUi))
            renderer.setTintUi(tintUi)
            renderer.setSaturationMatrix(saturationMatrix(saturationUi))
            renderer.setLumaParams(brightnessUi, exposureUi, highlightsUi, shadowsUi)
            view.requestRender()
        },
    )
}
