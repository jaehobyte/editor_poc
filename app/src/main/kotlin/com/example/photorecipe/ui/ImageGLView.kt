package com.example.photorecipe.ui

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.photorecipe.EditorParams
import com.example.photorecipe.editor.buildColorTuningLut
import com.example.photorecipe.editor.contrastCurve
import com.example.photorecipe.editor.saturationMatrix
import com.example.photorecipe.editor.wbMultipliers
import com.example.photorecipe.gl.ImageRenderer

/**
 * Compose wrapper around the GL renderer.
 *
 * @param global  Global adjustments applied to the entire image.
 * @param maskBitmap  Optional grayscale mask (same aspect as [bitmap], R = alpha).
 * @param mask  When [maskBitmap] is set, these adjustments mix into the masked
 *              region by the mask alpha. Required if [maskBitmap] is non-null.
 */
@Composable
fun ImageGLView(
    bitmap: Bitmap,
    global: EditorParams,
    maskBitmap: Bitmap? = null,
    mask: EditorParams? = null,
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

            renderer.setGlobalParams(
                wb = wbMultipliers(global.temperature),
                contrast = contrastCurve(global.contrast),
                tint = global.tint,
                saturation = saturationMatrix(global.saturation),
                brightness = global.brightness,
                exposure = global.exposure,
                highlights = global.highlights,
                shadows = global.shadows,
                colorTuningOn = global.colorTuningEnabled,
                colorLut = buildColorTuningLut(global.colorTuning),
            )

            if (maskBitmap != null && mask != null) {
                renderer.setMaskParams(
                    wb = wbMultipliers(mask.temperature),
                    contrast = contrastCurve(mask.contrast),
                    tint = mask.tint,
                    saturation = saturationMatrix(mask.saturation),
                    brightness = mask.brightness,
                    exposure = mask.exposure,
                    highlights = mask.highlights,
                    shadows = mask.shadows,
                    colorTuningOn = mask.colorTuningEnabled,
                    colorLut = buildColorTuningLut(mask.colorTuning),
                )
                renderer.setMask(maskBitmap)
            } else {
                renderer.setMask(null)
            }

            view.requestRender()
        },
    )
}
