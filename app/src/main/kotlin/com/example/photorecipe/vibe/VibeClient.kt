package com.example.photorecipe.vibe

import com.example.photorecipe.EditorParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * "바이브 에디터" — 자연어 프롬프트를 받아 Gemini text + tool-call 로 해석해서
 * 어떤 영역(global / 특정 mask)에 어떤 파라미터 값을 줄지 추론한다.
 *
 * 모델은 절대값(absolute)으로 응답하므로 호출 측은 `EditorParams` 의 해당 필드를
 * 그대로 덮어쓰면 된다. 모델이 변경하고 싶지 않은 필드는 args 에 포함하지 않음.
 */
data class VibeEdit(
    /** "global" 또는 사용 가능한 마스크 라벨 (lowercase). */
    val target: String,
    /** 모델이 바꾸려는 필드 → 절대값 [-100, 100]. 빠진 필드는 그대로 유지. */
    val changes: Map<String, Float>,
)

class VibeClient(private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * @param userPrompt 자유 형식 자연어 요청 (예: "하늘을 좀 더 따뜻하게").
     * @param availableTargets 모델이 고를 수 있는 target 후보. 첫 항목은 보통 "global",
     *                         나머지는 현재 검출된 마스크 라벨 (lowercase).
     * @param currentValues target → 현재 EditorParams. 모델이 컨텍스트로 참고.
     * @return 0개 이상의 VibeEdit. 비어 있으면 모델이 적합한 편집을 못 찾았다는 뜻.
     */
    suspend fun proposeEdits(
        userPrompt: String,
        availableTargets: List<String>,
        currentValues: Map<String, EditorParams>,
    ): List<VibeEdit> = withContext(Dispatchers.IO) {
        check(apiKey.isNotBlank()) { "GEMINI_KEY is missing — populate .env and rebuild" }
        require(userPrompt.isNotBlank()) { "prompt must not be blank" }
        require(availableTargets.isNotEmpty()) { "must offer at least one target (e.g. 'global')" }

        val body = buildRequestBody(userPrompt, availableTargets, currentValues)
        val req = Request.Builder()
            .url("$ENDPOINT?key=$apiKey")
            .post(body.toRequestBody(JSON))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("Vibe API error ${resp.code}: ${text.take(500)}")
            }
            parseEdits(text)
        }
    }

    private fun buildRequestBody(
        userPrompt: String,
        targets: List<String>,
        state: Map<String, EditorParams>,
    ): String {
        val systemInstruction = JSONObject().put(
            "parts", JSONArray().put(JSONObject().put("text", buildSystemPrompt(targets, state))),
        )
        val contents = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", userPrompt))),
        )
        val tools = JSONArray().put(
            JSONObject().put("function_declarations", JSONArray().put(toolSchema(targets))),
        )
        val toolConfig = JSONObject().put(
            "function_calling_config",
            JSONObject()
                .put("mode", "ANY")
                .put("allowed_function_names", JSONArray().put(TOOL_NAME)),
        )
        return JSONObject()
            .put("system_instruction", systemInstruction)
            .put("contents", contents)
            .put("tools", tools)
            .put("tool_config", toolConfig)
            .toString()
    }

    private fun buildSystemPrompt(targets: List<String>, state: Map<String, EditorParams>): String {
        val sb = StringBuilder()
        sb.appendLine("You are a Lightroom-style photo editor assistant.")
        sb.appendLine("The user's photo is loaded. They will describe an edit in natural language, often Korean.")
        sb.appendLine()
        sb.appendLine("Editable targets (regions) and their CURRENT parameter values:")
        for (t in targets) {
            val p = state[t]
            if (p == null) {
                sb.appendLine("- $t  (no current values)")
            } else {
                sb.appendLine(
                    "- $t: temperature=${p.temperature.toInt()}, contrast=${p.contrast.toInt()}, " +
                        "tint=${p.tint.toInt()}, saturation=${p.saturation.toInt()}, " +
                        "brightness=${p.brightness.toInt()}, exposure=${p.exposure.toInt()}, " +
                        "highlights=${p.highlights.toInt()}, shadows=${p.shadows.toInt()}",
                )
            }
        }
        sb.appendLine()
        sb.appendLine("Parameter semantics (all on a [-100, 100] scale, 0 = identity):")
        sb.appendLine("- temperature: -100 cool/blue ↔ +100 warm/orange.")
        sb.appendLine("- tint: -100 green ↔ +100 magenta.")
        sb.appendLine("- contrast: -100 flat ↔ +100 strong.")
        sb.appendLine("- saturation: -100 grayscale ↔ +100 vivid.")
        sb.appendLine("- brightness: overall lightness.")
        sb.appendLine("- exposure: camera-stop style brightness.")
        sb.appendLine("- highlights: tone of bright areas.")
        sb.appendLine("- shadows: tone of dark areas.")
        sb.appendLine()
        sb.appendLine("Decide which target best matches the request.")
        sb.appendLine("- If the user mentions a region (sky / road / building / person / dog / tree / sea ...), pick the matching mask label from the list above.")
        sb.appendLine("- If the user means the whole photo, pick 'global'.")
        sb.appendLine("- Only choose from the listed targets. Do not invent labels.")
        sb.appendLine()
        sb.appendLine("Call apply_edit one or more times.")
        sb.appendLine("Send ABSOLUTE final values (not deltas) — the app will replace the existing values.")
        sb.appendLine("Use moderate magnitudes: typical edits ±15..±35, '살짝/slightly' ±10..±15, '많이/a lot' ±40..±60.")
        sb.appendLine("Only include the fields you actually want to change; leave others out.")
        return sb.toString()
    }

    private fun toolSchema(targets: List<String>): JSONObject {
        val targetEnum = JSONArray().also { arr -> targets.forEach { arr.put(it) } }
        val properties = JSONObject()
            .put(
                "target",
                JSONObject()
                    .put("type", "string")
                    .put("enum", targetEnum)
                    .put("description", "Which region to edit. 'global' = entire image; otherwise a detected mask label."),
            )
            .put("temperature", numericParam("Color temperature. -100 cool/blue ↔ +100 warm/orange."))
            .put("contrast", numericParam("Contrast. -100 flat ↔ +100 strong."))
            .put("tint", numericParam("Tint. -100 green ↔ +100 magenta."))
            .put("saturation", numericParam("Saturation. -100 grayscale ↔ +100 vivid."))
            .put("brightness", numericParam("Brightness. -100 darker ↔ +100 lighter."))
            .put("exposure", numericParam("Exposure (camera stops). -100 darker ↔ +100 lighter."))
            .put("highlights", numericParam("Highlights (bright tones)."))
            .put("shadows", numericParam("Shadows (dark tones)."))

        return JSONObject()
            .put("name", TOOL_NAME)
            .put(
                "description",
                "Apply photo editing parameter changes to a region (mask) or globally. " +
                    "Send absolute values; omit fields you do not want to change.",
            )
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", JSONArray().put("target")),
            )
    }

    private fun numericParam(desc: String): JSONObject = JSONObject()
        .put("type", "number")
        .put("description", "$desc Range [-100, 100], identity = 0.")

    private fun parseEdits(json: String): List<VibeEdit> {
        val out = ArrayList<VibeEdit>()
        val root = JSONObject(json)
        val candidates = root.optJSONArray("candidates") ?: return emptyList()
        for (i in 0 until candidates.length()) {
            val parts = candidates.getJSONObject(i)
                .optJSONObject("content")
                ?.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.getJSONObject(j)
                val fc = part.optJSONObject("functionCall")
                    ?: part.optJSONObject("function_call")
                    ?: continue
                if (fc.optString("name") != TOOL_NAME) continue
                val args = fc.optJSONObject("args") ?: continue
                val target = args.optString("target").takeIf { it.isNotBlank() } ?: continue
                val changes = LinkedHashMap<String, Float>()
                for (key in PARAM_KEYS) {
                    if (!args.has(key) || args.isNull(key)) continue
                    val v = args.optDouble(key, Double.NaN)
                    if (!v.isNaN()) changes[key] = v.toFloat().coerceIn(-100f, 100f)
                }
                if (changes.isNotEmpty()) {
                    out += VibeEdit(target = target.lowercase(), changes = changes)
                }
            }
        }
        return out
    }

    companion object {
        private const val MODEL = "gemini-3.5-flash"
        private const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
        private const val TOOL_NAME = "apply_edit"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        val PARAM_KEYS: List<String> = listOf(
            "temperature", "contrast", "tint", "saturation",
            "brightness", "exposure", "highlights", "shadows",
        )
    }
}
