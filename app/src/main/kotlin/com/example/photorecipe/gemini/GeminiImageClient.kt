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

    /**
     * 사용자 프롬프트 + 한 장의 이미지로 새 이미지 생성. (Camera-Generative 시나리오)
     *
     * 사용자가 입력하는 프롬프트는 "매칭률 높은 데이팅 프로필" 같은 짧은 *의도* 이지
     * 모델에게 주는 완전한 지시가 아닙니다. 따라서 이 메서드는 사용자 입력을
     * [GENERATE_INSTRUCTION_TEMPLATE] 로 감싸서 "첨부 이미지를 ~ 스타일로 새로
     * 생성해줘" 라는 명시적 변환 명령으로 만든 뒤 모델에 보냅니다.
     *
     * @param image 사용자가 카메라로 막 찍은 이미지 (변환의 입력)
     * @param userPrompt 자유 형식 의도 (예: "매칭률 높은 데이팅 프로필")
     */
    suspend fun generate(image: Bitmap, userPrompt: String): Bitmap = withContext(Dispatchers.IO) {
        check(apiKey.isNotBlank()) { "GEMINI_KEY is missing — populate .env and rebuild" }
        require(userPrompt.isNotBlank()) { "prompt must not be blank" }

        val wrappedPrompt = GENERATE_INSTRUCTION_TEMPLATE.format(userPrompt.trim())
        val b64 = bitmapToJpegBase64(image)
        val body = buildSingleImageRequestBody(wrappedPrompt, b64)
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

    private fun buildSingleImageRequestBody(prompt: String, imageB64: String): String {
        val parts = JSONArray().apply {
            put(JSONObject().put("text", prompt))
            put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", imageB64)))
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
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-image-preview:generateContent"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        const val DEFAULT_PROMPT =
            "첫 번째 이미지는 reference(색감 참조용), 두 번째 이미지는 input(보정 대상)입니다. " +
                "input 이미지를 그대로 유지한 채, reference의 색온도·틴트·노출·대비·채도·톤 곡선만 분석해서 " +
                "Lightroom 색 보정(color grading)을 적용한 것처럼 input 이미지를 보정해주세요. " +
                "절대 새로운 장면을 생성하지 말고, input 이미지의 구도·피사체·디테일·텍스처·해상도를 픽셀 단위로 보존하세요. " +
                "결과물은 input 이미지에 색 보정 필터만 입힌 retouched 사진이어야 합니다."

        /**
         * Camera-Generative 시나리오용 wrapping. `%s` 자리에 사용자 의도가 들어감.
         *
         * Nano Banana (Gemini Flash Image) 는 "편집" 의도가 명확할 때 정체성 보존이
         * 잘 됩니다. 이 템플릿은 작업을 "생성" 이 아닌 "같은 사람의 다른 사진을 만드는
         * 편집" 으로 명시적으로 프레이밍해서 얼굴이 다른 사람으로 바뀌는 것을 방지.
         */
        const val GENERATE_INSTRUCTION_TEMPLATE: String =
            "TASK: Edit the attached photograph according to the user's request below. " +
                "Treat this strictly as a PHOTO EDIT of the SAME person — never generate a different individual.\n\n" +
                "USER REQUEST: \"%s\"\n\n" +
                "ABSOLUTE IDENTITY PRESERVATION (highest priority):\n" +
                "- The person in the output MUST be unmistakably the SAME individual as in the attached photo.\n" +
                "- Preserve face geometry, eye shape & color, nose shape, lip shape, skin tone, ear shape, " +
                "hair color & texture, eyebrow shape, age, race, gender, and any moles / scars / freckles / glasses.\n" +
                "- DO NOT replace the subject with a generic-looking model. DO NOT \"beautify\" by changing features.\n" +
                "- If you cannot fulfill the request while preserving identity, prioritize identity over the request.\n\n" +
                "ALLOWED EDITS (within request):\n" +
                "- Outfit, accessories, background, scene, lighting, color grading, framing, camera angle, " +
                "pose, expression, hairstyle styling (not changing color/length drastically unless asked).\n\n" +
                "OUTPUT:\n" +
                "- Return exactly ONE photorealistic image. Do not return text. Do not return multiple variants.\n" +
                "- Use a different medium (illustration, anime, painting, sketch) ONLY if the user explicitly requests it.\n" +
                "- 사용자 요청이 한국어이면 자연스럽게 해석하되 위 정체성 보존 규칙은 절대 우선이다."
    }
}
