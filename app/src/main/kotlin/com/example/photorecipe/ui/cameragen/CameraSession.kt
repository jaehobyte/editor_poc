package com.example.photorecipe.ui.cameragen

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CameraSession"

/**
 * Suspend wrapper for [ProcessCameraProvider.getInstance].
 */
suspend fun getCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cont.resume(future.get())
            } catch (t: Throwable) {
                cont.resumeWithException(t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

/**
 * Suspend wrapper for [ExtensionsManager.getInstanceAsync].
 */
suspend fun getExtensionsManager(
    context: Context,
    provider: ProcessCameraProvider,
): ExtensionsManager =
    suspendCancellableCoroutine { cont ->
        val future = ExtensionsManager.getInstanceAsync(context, provider)
        future.addListener({
            try {
                cont.resume(future.get())
            } catch (t: Throwable) {
                cont.resumeWithException(t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

/**
 * Bind preview + imageCapture for the given lens. If [portraitMode] is true
 * AND the device supports BOKEH extension on that lens, bind with extension
 * enabled; otherwise fall back to plain binding.
 *
 * Returns the resulting [Camera] (whose `cameraControl` is used for runtime
 * settings) or null if anything failed.
 */
suspend fun bindCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    imageCapture: ImageCapture,
    lens: LensFacing,
    portraitMode: Boolean,
): Camera? {
    val provider = runCatching { getCameraProvider(context) }
        .getOrElse {
            Log.e(TAG, "cameraProvider failed", it); return null
        }
    val baseSelector = lens.selector()

    val selector: CameraSelector = if (portraitMode) {
        runCatching {
            val em = getExtensionsManager(context, provider)
            if (em.isExtensionAvailable(baseSelector, ExtensionMode.BOKEH)) {
                em.getExtensionEnabledCameraSelector(baseSelector, ExtensionMode.BOKEH)
            } else {
                Log.w(TAG, "BOKEH not available on $lens lens")
                baseSelector
            }
        }.getOrElse {
            Log.e(TAG, "ExtensionsManager init failed", it)
            baseSelector
        }
    } else {
        baseSelector
    }

    val preview = Preview.Builder().build().also {
        it.surfaceProvider = previewView.surfaceProvider
    }

    return runCatching {
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
    }.getOrElse {
        Log.e(TAG, "bindToLifecycle failed", it)
        null
    }
}

/**
 * Apply runtime-mutable settings via CameraControl / Camera2CameraControl.
 * Safe to call on every settings change after binding.
 */
@OptIn(ExperimentalCamera2Interop::class)
fun applyRuntimeSettings(
    camera: Camera,
    imageCapture: ImageCapture,
    settings: CameraSettings,
) {
    // Flash (lives on ImageCapture, not CameraControl)
    imageCapture.flashMode = settings.flash.toCx()

    // Zoom + EV via CameraControl
    runCatching { camera.cameraControl.setZoomRatio(settings.zoomRatio) }
    runCatching { camera.cameraControl.setExposureCompensationIndex(settings.evIndex) }

    // White balance + manual AE via Camera2 interop
    val builder = CaptureRequestOptions.Builder()
        .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, settings.wb.awbMode)

    if (settings.manualMode) {
        builder
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, settings.iso)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.shutter.ns)
    } else {
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
    }

    runCatching {
        Camera2CameraControl.from(camera.cameraControl).captureRequestOptions = builder.build()
    }.onFailure { Log.w(TAG, "Camera2 options apply failed", it) }
}
