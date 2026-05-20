package com.example.photorecipe

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 단일 효과의 슬라이더 값 (UI 스케일 [-100, 100], 0 = identity). */
typealias EffectUi = Float

/** 8개 톤 효과 + 21개 컬러 튜닝 파라미터의 묶음 상태. */
@Stable
class EditorParams {
    var temperature by mutableStateOf<EffectUi>(0f)
    var contrast by mutableStateOf<EffectUi>(0f)
    var tint by mutableStateOf<EffectUi>(0f)
    var saturation by mutableStateOf<EffectUi>(0f)
    var brightness by mutableStateOf<EffectUi>(0f)
    var exposure by mutableStateOf<EffectUi>(0f)
    var highlights by mutableStateOf<EffectUi>(0f)
    var shadows by mutableStateOf<EffectUi>(0f)
    var colorTuning by mutableStateOf(FloatArray(21))
    var colorTuningEnabled by mutableStateOf(false)

    fun reset() {
        temperature = 0f; contrast = 0f; tint = 0f; saturation = 0f
        brightness = 0f; exposure = 0f; highlights = 0f; shadows = 0f
        colorTuning = FloatArray(21)
        colorTuningEnabled = false
    }

    /** 현재 값들을 복사한 새 EditorParams. 마스크가 global 기준으로 시작하도록 할 때 사용. */
    fun copyValuesFrom(src: EditorParams) {
        temperature = src.temperature
        contrast = src.contrast
        tint = src.tint
        saturation = src.saturation
        brightness = src.brightness
        exposure = src.exposure
        highlights = src.highlights
        shadows = src.shadows
        colorTuning = src.colorTuning.copyOf()
        colorTuningEnabled = src.colorTuningEnabled
    }

    /**
     * 동등 비교용 스냅샷 키. 9개 톤/스위치 + colorTuning 의 contentHashCode 를 묶은
     * 리스트로, derivedStateOf 안에서 LaunchedEffect 키로 쓰기 좋게 만든 헬퍼.
     * 어느 한 필드라도 바뀌면 리스트가 달라져서 effect 가 재발화한다.
     */
    fun snapshotKey(): Any = listOf(
        temperature, contrast, tint, saturation,
        brightness, exposure, highlights, shadows,
        colorTuningEnabled, colorTuning.contentHashCode(),
    )

    fun applyInferred(model29: FloatArray, toneFactor: Float = 1f, colorFactor: Float = 1f) {
        require(model29.size == 29) { "model output must be 29 floats" }
        temperature = model29[0] * 100f * toneFactor
        contrast    = model29[1] * 100f * toneFactor
        tint        = model29[2] * 100f * toneFactor
        saturation  = model29[3] * 100f * toneFactor
        brightness  = model29[4] * 100f * toneFactor
        exposure    = model29[5] * 100f * toneFactor
        highlights  = model29[6] * 100f * toneFactor
        shadows     = model29[7] * 100f * toneFactor
        colorTuning = FloatArray(21) { model29[8 + it] * 100f * colorFactor }
        colorTuningEnabled = true
    }
}

/** 픽업/추론/편집 단계를 표현하는 화면 상태. */
sealed interface AppPhase {
    data object Picker : AppPhase
    data class Editing(
        /** GL 프리뷰용 다운샘플된 비트맵 (긴 변 ≤ 2048px). */
        val inputBitmap: Bitmap,
        /** 저장 시 원본 해상도 디코딩 (긴 변 ≤ 4096px) 에 사용. */
        val inputUri: Uri,
        val params: FloatArray,
        /** Gemini Nano Banana 가 만들어준 reference 이미지. null = 일반 추론 흐름. */
        val stylizedReference: Bitmap? = null,
    ) : AppPhase
}

/** Picker 화면의 양쪽 슬롯. */
@Stable
class PickState {
    var reference by mutableStateOf<Uri?>(null)
    var input by mutableStateOf<Uri?>(null)
    val ready get() = reference != null && input != null
}
