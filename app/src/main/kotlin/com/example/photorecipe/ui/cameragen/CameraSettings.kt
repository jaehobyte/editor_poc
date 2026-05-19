package com.example.photorecipe.ui.cameragen

import android.hardware.camera2.CameraMetadata
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture

enum class FlashSetting(val label: String) {
    OFF("Off"),
    AUTO("Auto"),
    ON("On");

    fun toCx(): Int = when (this) {
        OFF -> ImageCapture.FLASH_MODE_OFF
        AUTO -> ImageCapture.FLASH_MODE_AUTO
        ON -> ImageCapture.FLASH_MODE_ON
    }
}

enum class LensFacing(private val cx: Int, val label: String) {
    BACK(CameraSelector.LENS_FACING_BACK, "Back"),
    FRONT(CameraSelector.LENS_FACING_FRONT, "Front");

    fun selector(): CameraSelector =
        CameraSelector.Builder().requireLensFacing(cx).build()

    fun toggle(): LensFacing = if (this == BACK) FRONT else BACK
}

/** Camera2 의 AWB 모드와 1:1 매핑되는 White Balance 프리셋. */
enum class WbPreset(val label: String, val awbMode: Int) {
    AUTO("Auto", CameraMetadata.CONTROL_AWB_MODE_AUTO),
    DAYLIGHT("Daylight", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("Cloudy", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    SHADE("Shade", CameraMetadata.CONTROL_AWB_MODE_SHADE),
    TUNGSTEN("Tungsten", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT("Fluorescent", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
}

/** 셔터 속도 프리셋 (사진 어플에서 흔히 보이는 값들). 단위: 나노초. */
data class ShutterSpeed(val label: String, val ns: Long) {
    companion object {
        val PRESETS: List<ShutterSpeed> = listOf(
            ShutterSpeed("1/4000", 250_000L),
            ShutterSpeed("1/2000", 500_000L),
            ShutterSpeed("1/1000", 1_000_000L),
            ShutterSpeed("1/500",  2_000_000L),
            ShutterSpeed("1/250",  4_000_000L),
            ShutterSpeed("1/125",  8_000_000L),
            ShutterSpeed("1/60",  16_666_666L),
            ShutterSpeed("1/30",  33_333_333L),
            ShutterSpeed("1/15",  66_666_666L),
            ShutterSpeed("1/8",  125_000_000L),
            ShutterSpeed("1/4",  250_000_000L),
            ShutterSpeed("1/2",  500_000_000L),
            ShutterSpeed("1s",  1_000_000_000L),
        )
        val DEFAULT_INDEX = 6 // 1/60
    }
}

/** ISO 프리셋. */
val ISO_PRESETS: List<Int> = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)
const val ISO_DEFAULT_INDEX: Int = 1 // 100

/**
 * 카메라 전체 설정 — Compose state 로 보유.
 *
 * `lens` 와 `portraitMode` 가 바뀌면 카메라를 unbind/rebind 해야 하고,
 * 나머지는 runtime 에서 `CameraControl` / `Camera2CameraControl` 로 적용됨.
 */
data class CameraSettings(
    val lens: LensFacing = LensFacing.BACK,
    val flash: FlashSetting = FlashSetting.OFF,
    val zoomRatio: Float = 1.0f,
    val evIndex: Int = 0,
    val wb: WbPreset = WbPreset.AUTO,
    val portraitMode: Boolean = false,
    val manualMode: Boolean = false,
    val isoIndex: Int = ISO_DEFAULT_INDEX,
    val shutterIndex: Int = ShutterSpeed.DEFAULT_INDEX,
) {
    val iso: Int get() = ISO_PRESETS[isoIndex]
    val shutter: ShutterSpeed get() = ShutterSpeed.PRESETS[shutterIndex]
}
