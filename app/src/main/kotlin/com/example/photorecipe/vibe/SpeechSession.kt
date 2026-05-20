package com.example.photorecipe.vibe

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * 인앱 음성 입력 세션 — Android [SpeechRecognizer] 의 얇은 래퍼.
 *
 * 기존 [RecognizerIntent.ACTION_RECOGNIZE_SPEECH] 방식은 구글 시스템 다이얼로그가
 * 모달로 뜨고 디자인이 앱과 동떨어진 느낌을 줬다. 이 세션은 그 모달 없이 partial
 * 결과 / RMS 진폭 / 에러를 [androidx.compose.runtime.State] 로 노출해서 push-to-talk
 * UI 를 직접 그릴 수 있게 해준다.
 *
 * Lifecycle:
 * - `start { final -> ... }` — 마이크 듣기 시작, 인식 완료시 final 콜백.
 * - `stop()` — 사용자가 손을 떼면 호출. recognizer 가 마지막 결과를 만들고 onResults
 *   를 통해 final 콜백을 한 번 부른다.
 * - `cancel()` — 결과 없이 종료.
 * - `dispose()` — Composable 이 떠날 때 호출 (네이티브 핸들 정리). [rememberSpeechSession]
 *   이 [DisposableEffect] 로 자동 호출.
 */
class SpeechSession internal constructor(private val context: Context) {
    /** Recognizer 가 보낸 가장 최근 partial 전사 (사용자가 말하는 동안 갱신). */
    var partial: String by mutableStateOf("")
        private set

    /** 0..1 로 정규화 + 저역통과된 마이크 RMS. 마이크 버튼 펄스에 사용. */
    var rms: Float by mutableStateOf(0f)
        private set

    /** 현재 듣는 중? */
    var recording: Boolean by mutableStateOf(false)
        private set

    /** 마지막 에러 — 다음 [start] 호출 때 null 로 초기화. */
    var error: String? by mutableStateOf(null)
        private set

    private var recognizer: SpeechRecognizer? = null
    private var onFinal: ((String) -> Unit)? = null

    /**
     * @return true 이면 listen 시작 성공. false 이면 권한이나 가용성 문제로 시작 못함
     *         ([error] 에 사유). 호출 측은 false 일 때 권한 요청 흐름으로 넘기면 됨.
     */
    fun start(onFinal: (String) -> Unit): Boolean {
        if (recording) return true
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED) {
            error = "Microphone permission required"
            return false
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            error = "Speech recognition not available on this device"
            return false
        }
        this.onFinal = onFinal
        partial = ""
        error = null
        rms = 0f
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(makeListener())
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recording = true
        rec.startListening(intent)
        return true
    }

    /** 사용자가 손을 뗐을 때. 결과 한 번 더 받고 final 콜백 fire. */
    fun stop() {
        if (!recording) return
        recognizer?.stopListening()
        // 나머지는 onResults / onError 가 처리.
    }

    /** 결과 무시하고 종료. */
    fun cancel() {
        recognizer?.cancel()
        recording = false
        rms = 0f
        partial = ""
    }

    fun dispose() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        recording = false
    }

    private fun makeListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            // SpeechRecognizer 의 rmsdB 는 기기마다 다르지만 보통 ~-2 .. ~12 dB.
            // [0, 1] 로 정규화하고 지수이동평균으로 부드럽게.
            val normalized = ((rmsdB + 2f) / 14f).coerceIn(0f, 1f)
            rms = rms * 0.6f + normalized * 0.4f
        }

        override fun onEndOfSpeech() {
            // recognizer 가 처리하는 짧은 구간 동안에는 recording 유지. onResults / onError
            // 가 fire 되면 false 로 떨어진다.
        }

        override fun onError(code: Int) {
            recording = false
            rms = 0f
            error = errorText(code)
        }

        override fun onResults(results: Bundle?) {
            recording = false
            rms = 0f
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            partial = text
            if (text.isNotBlank()) onFinal?.invoke(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotEmpty()) partial = text
        }
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "오디오 입력 오류"
        SpeechRecognizer.ERROR_CLIENT -> "Speech client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 필요해요"
        SpeechRecognizer.ERROR_NETWORK -> "네트워크 오류"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "음성을 인식하지 못했어요"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy — 잠시 후 다시 시도"
        SpeechRecognizer.ERROR_SERVER -> "Speech server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "음성이 감지되지 않았어요"
        else -> "Speech error ($code)"
    }
}

@Composable
fun rememberSpeechSession(): SpeechSession {
    val context = LocalContext.current
    val session = remember { SpeechSession(context) }
    DisposableEffect(session) { onDispose { session.dispose() } }
    return session
}
