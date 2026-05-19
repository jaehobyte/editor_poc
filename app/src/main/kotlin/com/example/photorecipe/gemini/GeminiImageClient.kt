package com.example.photorecipe.gemini

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Gemini 2.5 Flash Image (codename: Nano Banana) 클라이언트.
 *
 * 입력: reference + content 비트맵 + 프롬프트 텍스트
 * 출력: 모델이 생성한 새 비트맵 (reference 의 스타일이 content 에 적용된)
 *
 * GEMINI_KEY 가 비어있으면 호출 시 IllegalStateException.
 */
class GeminiImageClient(private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * @param reference 참조 룩 이미지
     * @param content 편집 대상 이미지
     * @param prompt 사용자 지시문. 비어있으면 기본 프롬프트 사용.
     */
    suspend fun stylize(
        reference: Bitmap,
        content: Bitmap,
        prompt: String = DEFAULT_PROMPT,
    ): Bitmap = withContext(Dispatchers.IO) {
        check(apiKey.isNotBlank()) { "GEMINI_KEY is missing — populate .env and rebuild" }

        val refB64 = bitmapToJpegBase64(reference)
        val contentB64 = bitmapToJpegBase64(content)

        val body = buildRequestBody(prompt, refB64, contentB64)
        val req = Request.Builder()
            .url("$ENDPOINT?key=$apiKey")
            .post(body.toRequestBody(JSON))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("Gemini API error ${resp.code}: ${text.take(500)}")
            }
            extractImage(text) ?: error("Gemini response had no image. Body: ${text.take(500)}")
        }
    }

    private fun bitmapToJpegBase64(bmp: Bitmap, quality: Int = 90, maxDim: Int = 1024): String {
        val downscaled = downscale(bmp, maxDim)
        val out = ByteArrayOutputStream()
        downscaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun downscale(bmp: Bitmap, maxDim: Int): Bitmap {
        val long = maxOf(bmp.width, bmp.height)
        if (long <= maxDim) return bmp
        val scale = maxDim.toFloat() / long
        return Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun buildRequestBody(prompt: String, refB64: String, contentB64: String): String {
        // 첫 번째 inline_data = reference, 두 번째 = content. 프롬프트에서 순서를 명시.
        val parts = JSONArray().apply {
            put(JSONObject().put("text", prompt))
            put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", refB64)))
            put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", contentB64)))
        }
        val contents = JSONArray().put(JSONObject().put("parts", parts))
        val generationConfig = JSONObject().put(
            "responseModalities",
            JSONArray().put("TEXT").put("IMAGE"),
        )
        return JSONObject()
            .put("contents", contents)
            .put("generationConfig", generationConfig)
            .toString()
    }

    private fun extractImage(json: String): Bitmap? {
        val root = JSONObject(json)
        val candidates = root.optJSONArray("candidates") ?: return null
        for (i in 0 until candidates.length()) {
            val parts = candidates.getJSONObject(i)
                .optJSONObject("content")
                ?.optJSONArray("parts")
                ?: continue
            for (j in 0 until parts.length()) {
                val inline = parts.getJSONObject(j).optJSONObject("inline_data")
                    ?: parts.getJSONObject(j).optJSONObject("inlineData") // camelCase variant
                if (inline != null) {
                    val b64 = inline.optString("data").takeIf { it.isNotEmpty() } ?: continue
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
        }
        return null
    }

    companion object {
        private const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-image-preview:generateContent"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        const val DEFAULT_PROMPT =
            "첫 번째 이미지는 reference, 두 번째 이미지는 input입니다. " +
                "reference 이미지의 스타일/색감/룩을 input 이미지에 적용한 새 이미지를 생성해주세요. " +
                "구도와 피사체는 input 이미지의 것을 그대로 유지하고, 톤과 색감만 reference 처럼 만들어주세요."
    }
}
