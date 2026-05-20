package com.example.photorecipe.vibe

import android.graphics.Bitmap
import android.util.Base64
import com.example.photorecipe.EditorParams
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
 * "바이브 에디터" — 자연어 프롬프트를 받아 Gemini text + tool-call 로 해석해서
 * 어떤 영역(global / 특정 mask)에 어떤 파라미터 값을 줄지 추론한다.
 *
 * 노출되는 편집 축:
 *   1. 8개 톤 슬라이더 (temperature / contrast / tint / saturation /
 *      brightness / exposure / highlights / shadows)
 *   2. Galaxy 7-색상 × HSL color tuning — band 별로 (hue / saturation /
 *      luminance) 시프트를 줄 수 있음.
 *
 * 모델은 절대값(absolute)으로 응답하고, 호출 측은 해당 필드만 덮어쓴다.
 * 빠진 필드는 현재 값 유지.
 */
data class VibeEdit(
    /** "global" 또는 사용 가능한 마스크 라벨 (lowercase). */
    val target: String,
    /** 모델이 바꾸려는 톤 필드 → 절대값 [-100, 100]. */
    val changes: Map<String, Float> = emptyMap(),
    /**
     * 색상 톤 조정. band ("red"/"orange"/"yellow"/"green"/"blue"/"navy"/"purple")
     *   → channel ("hue"/"saturation"/"luminance") → 절대값 [-100, 100].
     */
    val colorTuning: Map<String, Map<String, Float>> = emptyMap(),
    /**
     * 사용자가 reference 사진을 첨부하고 "이 사진처럼" 류로 요청했을 때, 모델이
     * 직접 슬라이더 값을 정하는 대신 recipe_generator.tflite 를 돌리도록 위임한
     * 호출. 호출 측이 (content, reference) 로 추론 → [EditorParams.applyInferred]
     * 로 [target] 에 적용해야 한다. null 이면 일반 [changes] / [colorTuning] 경로.
     */
    val recipeTransfer: RecipeTransferRequest? = null,
)

/** apply_recipe_from_reference 의 인자 — 적용 강도 스케일. */
data class RecipeTransferRequest(
    /** 톤(8축) 강도. 1.0 = 모델 출력 그대로. */
    val toneFactor: Float = 1.0f,
    /** Color tuning(21개) 강도. 1.0 = 모델 출력 그대로. */
    val colorFactor: Float = 1.0f,
)

/**
 * Gemini 응답 한 번분. 보통 [edits] 에 한두 개의 함수 호출이 채워지지만, 모델이
 * tool call 대신 텍스트로 답하면 (거부 / 추가 질문 / 모호) [message] 에 그 텍스트가
 * 들어온다. 호출 측은 둘 다 처리해야 함.
 */
data class VibeResponse(
    val edits: List<VibeEdit>,
    val message: String? = null,
)

/**
 * Multi-turn 대화의 한 항목. 모델에게 "직전 turn 에 어떤 요청과 결과가 있었는지"
 * 를 텍스트로 알려주는 용도. functionCall/functionResponse 구조 대신 단순 텍스트
 * 요약을 쓰는 이유: Gemini 의 tool 프로토콜 quirks (특히 functionResponse 매칭
 * 누락 시 5xx) 를 피하고, 어차피 system prompt 에 "current values" 가 다 박혀
 * 있어서 의미 손실이 거의 없음.
 */
data class VibeTurn(
    /** 사용자가 입력한 원문 (예: "하늘 좀 더 따뜻하게"). */
    val userPrompt: String,
    /**
     * 모델 응답 요약 — 적용된 edit 들의 인간이 읽을 수 있는 요약, 또는 거부
     * 텍스트. 예: "applied: sky temperature=25" / "refused: too vague".
     */
    val summary: String,
)

class VibeClient(private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun proposeEdits(
        userPrompt: String,
        availableTargets: List<String>,
        currentValues: Map<String, EditorParams>,
        history: List<VibeTurn> = emptyList(),
        referenceImage: Bitmap? = null,
    ): VibeResponse = withContext(Dispatchers.IO) {
        check(apiKey.isNotBlank()) { "GEMINI_KEY is missing — populate .env and rebuild" }
        require(userPrompt.isNotBlank()) { "prompt must not be blank" }
        require(availableTargets.isNotEmpty()) { "must offer at least one target (e.g. 'global')" }

        val referenceB64 = referenceImage?.let { bitmapToJpegBase64(it) }
        val body = buildRequestBody(userPrompt, availableTargets, currentValues, history, referenceB64)
        val req = Request.Builder()
            .url("$ENDPOINT?key=$apiKey")
            .post(body.toRequestBody(JSON))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("Vibe API error ${resp.code}: ${text.take(500)}")
            }
            parseResponse(text)
        }
    }

    private fun bitmapToJpegBase64(bmp: Bitmap, quality: Int = 85, maxDim: Int = 768): String {
        val long = maxOf(bmp.width, bmp.height)
        val downscaled = if (long <= maxDim) bmp else {
            val scale = maxDim.toFloat() / long
            Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }
        val out = ByteArrayOutputStream()
        downscaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (downscaled !== bmp) downscaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun buildRequestBody(
        userPrompt: String,
        targets: List<String>,
        state: Map<String, EditorParams>,
        history: List<VibeTurn>,
        referenceB64: String?,
    ): String {
        val systemInstruction = JSONObject().put(
            "parts",
            JSONArray().put(
                JSONObject().put("text", buildSystemPrompt(targets, state, referenceB64 != null)),
            ),
        )
        // 최근 turn 들을 user/model 텍스트 페어로 풀어서 contents 에 펼침.
        // 마지막에 새 user 메시지를 붙임. 모델은 이 흐름을 보고 "조금 더" / "취소"
        // 같은 후속 명령의 reference 를 직전 model summary 에서 찾을 수 있음.
        val contents = JSONArray()
        for (turn in history) {
            contents.put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.userPrompt))),
            )
            contents.put(
                JSONObject()
                    .put("role", "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.summary))),
            )
        }
        // 마지막 user 메시지에 reference 이미지(있으면)와 새 prompt 텍스트를 같이 넣는다.
        val finalParts = JSONArray()
        if (referenceB64 != null) {
            finalParts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", referenceB64),
                ),
            )
        }
        finalParts.put(JSONObject().put("text", userPrompt))
        contents.put(JSONObject().put("role", "user").put("parts", finalParts))

        val allowed = JSONArray().put(TOOL_NAME_EDIT)
        if (referenceB64 != null) allowed.put(TOOL_NAME_RECIPE)
        val declarations = JSONArray().put(toolSchemaEdit(targets))
        if (referenceB64 != null) declarations.put(toolSchemaRecipe(targets))
        val tools = JSONArray().put(JSONObject().put("function_declarations", declarations))
        val toolConfig = JSONObject().put(
            "function_calling_config",
            JSONObject()
                .put("mode", "ANY")
                .put("allowed_function_names", allowed),
        )
        return JSONObject()
            .put("system_instruction", systemInstruction)
            .put("contents", contents)
            .put("tools", tools)
            .put("tool_config", toolConfig)
            .toString()
    }

    private fun buildSystemPrompt(
        targets: List<String>,
        state: Map<String, EditorParams>,
        hasReferenceImage: Boolean,
    ): String = buildString {
        appendLine("You are a Lightroom-style photo editor assistant. The user types or speaks an edit")
        appendLine("request, often in Korean. Pick the right region and the right axes, then call")
        appendLine("apply_edit once or twice with absolute values.")
        appendLine()

        // ── Available targets and current values ────────────────────────
        appendLine("AVAILABLE TARGETS (regions you can edit) with their CURRENT values:")
        for (t in targets) {
            val p = state[t]
            if (p == null) {
                appendLine("- $t  (no current values)")
                continue
            }
            append("- ").append(t).append(":")
            append("  tone={")
            append("temperature=").append(p.temperature.toInt())
            append(", contrast=").append(p.contrast.toInt())
            append(", tint=").append(p.tint.toInt())
            append(", saturation=").append(p.saturation.toInt())
            append(", brightness=").append(p.brightness.toInt())
            append(", exposure=").append(p.exposure.toInt())
            append(", highlights=").append(p.highlights.toInt())
            append(", shadows=").append(p.shadows.toInt())
            append("}")
            val nonZero = describeNonZeroColorTuning(p.colorTuning)
            if (nonZero.isNotEmpty()) {
                append("  color_tuning={ ").append(nonZero).append(" }")
            }
            if (p.colorTuningEnabled) append("  [color_tuning ENABLED]")
            appendLine()
        }
        appendLine()

        // ── Tone axes ──────────────────────────────────────────────────
        appendLine("TONE AXES (each [-100, 100], identity=0):")
        appendLine("- temperature : -100 cool/blue ↔ +100 warm/orange. Use for time-of-day mood, warmth.")
        appendLine("- tint        : -100 green ↔ +100 magenta. Use for white-balance corrections.")
        appendLine("- contrast    : -100 flat ↔ +100 strong.")
        appendLine("- saturation  : -100 grayscale ↔ +100 vivid (affects ALL colors equally).")
        appendLine("- brightness  : overall lightness.")
        appendLine("- exposure    : camera-stop style brightness (gentler than brightness at extremes).")
        appendLine("- highlights  : tone of bright areas only.")
        appendLine("- shadows     : tone of dark areas only.")
        appendLine()

        // ── Color tuning ───────────────────────────────────────────────
        appendLine("COLOR TUNING — 7 color bands × {hue, saturation, luminance}, each [-100, 100]:")
        appendLine("- red    : warm reds around 0°. (사과, 입술, 빨간 옷)")
        appendLine("- orange : 40°. (피부톤, 노을, 주황 단풍, 일출/일몰)")
        appendLine("- yellow : 60°. (햇빛, 모래, 노란 단풍, 가로등 빛)")
        appendLine("- green  : 120°. (풀, 나뭇잎, 잔디, 식물)")
        appendLine("- blue   : 180° (cyan/하늘색). (맑은 하늘, 수영장 물, 청록빛 바다)")
        appendLine("- navy   : 240° (deep/짙은 파랑). (밤하늘, 깊은 바다, 짙은 청바지)")
        appendLine("- purple : 300° (magenta/보라). (자주색, 보랏빛 노을, 라일락)")
        appendLine()
        appendLine("Within a color band:")
        appendLine("- hue       : shift that color's hue. +→ next band (red→orange), -→ prev band (red→purple).")
        appendLine("- saturation: vividness of *that color only* (negative = wash out toward gray).")
        appendLine("- luminance : brightness of *that color only* (negative = darker, positive = lighter).")
        appendLine()

        // ── How to pick the right axis ─────────────────────────────────
        appendLine("CHOOSING THE RIGHT EDIT:")
        appendLine("- '따뜻한 분위기/cool mood' → tone.temperature (region or global).")
        appendLine("- '쨍하게/더 vivid' → tone.saturation (or specific band saturation if a color is named).")
        appendLine("- '하늘을 더 파랗게' → color_tuning on the sky mask: blue.saturation+ and maybe navy.saturation+.")
        appendLine("  ('blue' band ≈ 하늘색, 'navy' ≈ 짙은 파랑. 흐린 하늘이면 blue 위주, 청명한 깊은 파랑이면 navy 추가.)")
        appendLine("- '잔디 더 짙은 초록' → color_tuning on grass/global: green.saturation+ green.luminance- green.hue slight.")
        appendLine("- '피부톤 보정' → color_tuning.orange (saturation -10..+10, luminance slight).")
        appendLine("- '노을 더 진하게' → color_tuning.orange + color_tuning.red saturation +.")
        appendLine("- 색을 *전혀 다른 색*으로 바꾸려는 시도면 color_tuning.X.hue 를 ±30..±60 정도까지.")
        appendLine("- 일반 보정(밝게/어둡게/대비)은 톤 axes 만 쓰는게 보통 더 자연스러움.")
        appendLine()

        // ── Output rules ───────────────────────────────────────────────
        appendLine("OUTPUT RULES:")
        appendLine("- ALWAYS pick the most specific target available. If '하늘' is one of the targets, use it; don't pick 'global'.")
        appendLine("- Pick targets only from the list above. Do not invent labels.")
        appendLine("- Send ABSOLUTE final values (not deltas). App will overwrite existing values for fields you set.")
        appendLine("- Only include fields you actually want to change. Omitted fields keep current values.")
        appendLine("- Magnitudes: 살짝/slightly = ±10..±15, default = ±20..±35, 많이/strongly = ±40..±60, 극단 = ±70..")
        appendLine("- You may call apply_edit multiple times if the request mentions multiple regions.")
        appendLine("- If the user request is too vague to act on, call apply_edit with target='global' and small reasonable defaults.")
        appendLine()

        // ── Reference image / recipe-transfer path ─────────────────────
        if (hasReferenceImage) {
            appendLine("REFERENCE IMAGE ATTACHED:")
            appendLine("This turn includes a user-provided reference image (the inline_data part before the prompt).")
            appendLine("Two tools are available:")
            appendLine("- apply_edit (described above): if the user wants verbal/categorical edits, possibly *relative* to the reference (e.g. '이것보다 살짝 더 따뜻하게', 'this but bluer').")
            appendLine("- apply_recipe_from_reference: hand off to an on-device color-transfer model that learns the look from the reference and produces a full 8-tone + 21-color-band parameter set automatically. Use this when the user wants the reference's *whole look* transferred — phrases like:")
            appendLine("  '이 사진처럼' / '이 느낌으로' / '이 색감 적용' / 'make it like this' / 'this look' / '이렇게 바꿔줘'.")
            appendLine("Choose ONE tool per call:")
            appendLine("- Pure 'transfer this look' → apply_recipe_from_reference. Optional tone_factor / color_factor ∈ [0, 1.5], default 1.0; '살짝' → 0.5, '강하게' → 1.2.")
            appendLine("- 'Transfer the look AND also bump shadows' → call apply_recipe_from_reference first, then apply_edit for the extra tweak in the same response.")
            appendLine("- Verbal description only (no 'this look') → apply_edit, even when reference present.")
            appendLine()
        } else {
            appendLine("NO REFERENCE IMAGE: only apply_edit is available this turn.")
            appendLine()
        }

        // ── Multi-turn / follow-up handling ────────────────────────────
        appendLine("FOLLOW-UP COMMANDS (multi-turn):")
        appendLine("The contents array shows the recent conversation — alternating user prompts and your previous response summaries.")
        appendLine("The user may issue a *follow-up* referring to the previous turn rather than a fresh request:")
        appendLine("- '조금 더' / 'a bit more' / '더' → extend the SAME axis on the SAME target as your last response, pushing it further in the same direction. Compute new absolute value = current value + a small extra step (e.g. +10).")
        appendLine("- '덜' / '조금만' / 'less' → pull the last-changed axis back toward 0, but not to 0 (e.g. halve the distance from 0).")
        appendLine("- '취소' / 'undo' / '되돌려' → revert the last turn: emit apply_edit on the same target setting the changed axes back toward their PRE-turn value. If unknown, set to 0.")
        appendLine("- '다시' / '같은 거 다른 곳에' → apply your last response's axes/magnitudes to a different target the user names now.")
        appendLine("- '계속' / 'continue' on an open-ended request → progress the same direction further than last turn.")
        appendLine("When a follow-up is ambiguous, infer from CURRENT VALUES which axis was most recently moved (largest delta from 0 on the relevant target) and act on it.")
    }

    private fun describeNonZeroColorTuning(arr: FloatArray): String {
        if (arr.size != 21) return ""
        val parts = ArrayList<String>()
        for ((bi, band) in COLOR_BANDS.withIndex()) {
            val h = arr[bi * 3].toInt()
            val s = arr[bi * 3 + 1].toInt()
            val l = arr[bi * 3 + 2].toInt()
            if (h == 0 && s == 0 && l == 0) continue
            val inner = buildString {
                if (h != 0) append("hue=").append(h)
                if (s != 0) {
                    if (isNotEmpty()) append(", ")
                    append("saturation=").append(s)
                }
                if (l != 0) {
                    if (isNotEmpty()) append(", ")
                    append("luminance=").append(l)
                }
            }
            parts += "$band: {$inner}"
        }
        return parts.joinToString(", ")
    }

    private fun toolSchemaEdit(targets: List<String>): JSONObject {
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
            .put("saturation", numericParam("Global saturation (all colors). -100 grayscale ↔ +100 vivid."))
            .put("brightness", numericParam("Brightness. -100 darker ↔ +100 lighter."))
            .put("exposure", numericParam("Exposure (camera stops). -100 darker ↔ +100 lighter."))
            .put("highlights", numericParam("Highlights (bright tones)."))
            .put("shadows", numericParam("Shadows (dark tones)."))
            .put("color_tuning", colorTuningSchema())

        return JSONObject()
            .put("name", TOOL_NAME_EDIT)
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

    private fun toolSchemaRecipe(targets: List<String>): JSONObject {
        val targetEnum = JSONArray().also { arr -> targets.forEach { arr.put(it) } }
        val properties = JSONObject()
            .put(
                "target",
                JSONObject()
                    .put("type", "string")
                    .put("enum", targetEnum)
                    .put("description", "Which region receives the transferred look."),
            )
            .put(
                "tone_factor",
                JSONObject()
                    .put("type", "number")
                    .put(
                        "description",
                        "Strength of the 8-axis tone transfer. 1.0 = full model output; " +
                            "0.5 = subtle; 0 = no tone change. Range [0, 1.5].",
                    ),
            )
            .put(
                "color_factor",
                JSONObject()
                    .put("type", "number")
                    .put(
                        "description",
                        "Strength of the 21-band color tuning transfer. 1.0 = full; " +
                            "0 = no color shift. Range [0, 1.5].",
                    ),
            )
        return JSONObject()
            .put("name", TOOL_NAME_RECIPE)
            .put(
                "description",
                "Transfer the look of the attached reference image to a target. The app runs " +
                    "an on-device color-transfer model and applies the result. Only call this " +
                    "when a reference image is attached and the user wants its overall look.",
            )
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", JSONArray().put("target")),
            )
    }

    private fun colorTuningSchema(): JSONObject {
        val bandSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("hue", numericParam("Hue shift for this color band."))
                    .put("saturation", numericParam("Saturation of this color band only."))
                    .put("luminance", numericParam("Luminance of this color band only.")),
            )

        val bandProps = JSONObject()
        for (band in COLOR_BANDS) {
            // 각 band 의 schema 인스턴스를 새로 만들어줘야 동일 객체 공유 문제 없음.
            bandProps.put(band, freshBandSchema())
        }

        return JSONObject()
            .put("type", "object")
            .put(
                "description",
                "Per-color HSL adjustment. Each of 7 bands (red/orange/yellow/green/blue/navy/purple) " +
                    "can shift its own hue/saturation/luminance independently. Use this when the user names " +
                    "a specific color or wants a region's dominant color tweaked (e.g., make the sky a deeper blue).",
            )
            .put("properties", bandProps)
    }

    private fun freshBandSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("hue", numericParam("Hue shift for this color band."))
                .put("saturation", numericParam("Saturation of this color band only."))
                .put("luminance", numericParam("Luminance of this color band only.")),
        )

    private fun numericParam(desc: String): JSONObject = JSONObject()
        .put("type", "number")
        .put("description", "$desc Range [-100, 100], identity = 0.")

    private fun parseResponse(json: String): VibeResponse {
        val edits = ArrayList<VibeEdit>()
        val textParts = ArrayList<String>()
        val root = JSONObject(json)
        val candidates = root.optJSONArray("candidates") ?: return VibeResponse(emptyList())
        for (i in 0 until candidates.length()) {
            val parts = candidates.getJSONObject(i)
                .optJSONObject("content")
                ?.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.getJSONObject(j)
                val fc = part.optJSONObject("functionCall")
                    ?: part.optJSONObject("function_call")
                if (fc != null) {
                    parseEditFromCall(fc)?.let { edits += it }
                    continue
                }
                val text = part.optString("text").takeIf { it.isNotBlank() }
                if (text != null) textParts += text
            }
        }
        return VibeResponse(
            edits = edits,
            message = textParts.takeIf { it.isNotEmpty() }?.joinToString("\n")?.trim(),
        )
    }

    private fun parseEditFromCall(fc: JSONObject): VibeEdit? {
        val name = fc.optString("name")
        val args = fc.optJSONObject("args") ?: return null
        val target = args.optString("target").takeIf { it.isNotBlank() } ?: return null

        return when (name) {
            TOOL_NAME_EDIT -> {
                val toneChanges = LinkedHashMap<String, Float>()
                for (key in TONE_KEYS) {
                    if (!args.has(key) || args.isNull(key)) continue
                    val v = args.optDouble(key, Double.NaN)
                    if (!v.isNaN()) toneChanges[key] = v.toFloat().coerceIn(-100f, 100f)
                }
                val colorTuning = parseColorTuning(args.optJSONObject("color_tuning"))
                if (toneChanges.isEmpty() && colorTuning.isEmpty()) null
                else VibeEdit(
                    target = target.lowercase(),
                    changes = toneChanges,
                    colorTuning = colorTuning,
                )
            }
            TOOL_NAME_RECIPE -> {
                val tone = args.optDouble("tone_factor", 1.0).toFloat().coerceIn(0f, 1.5f)
                val color = args.optDouble("color_factor", 1.0).toFloat().coerceIn(0f, 1.5f)
                VibeEdit(
                    target = target.lowercase(),
                    recipeTransfer = RecipeTransferRequest(toneFactor = tone, colorFactor = color),
                )
            }
            else -> null
        }
    }

    private fun parseColorTuning(node: JSONObject?): Map<String, Map<String, Float>> {
        if (node == null) return emptyMap()
        val out = LinkedHashMap<String, Map<String, Float>>()
        for (band in COLOR_BANDS) {
            val bandObj = node.optJSONObject(band) ?: continue
            val inner = LinkedHashMap<String, Float>()
            for (channel in COLOR_CHANNELS) {
                if (!bandObj.has(channel) || bandObj.isNull(channel)) continue
                val v = bandObj.optDouble(channel, Double.NaN)
                if (!v.isNaN()) inner[channel] = v.toFloat().coerceIn(-100f, 100f)
            }
            if (inner.isNotEmpty()) out[band] = inner
        }
        return out
    }

    companion object {
        private const val MODEL = "gemini-3.5-flash"
        private const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
        private const val TOOL_NAME_EDIT = "apply_edit"
        private const val TOOL_NAME_RECIPE = "apply_recipe_from_reference"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        val TONE_KEYS: List<String> = listOf(
            "temperature", "contrast", "tint", "saturation",
            "brightness", "exposure", "highlights", "shadows",
        )
        val COLOR_BANDS: List<String> = listOf(
            "red", "orange", "yellow", "green", "blue", "navy", "purple",
        )
        val COLOR_CHANNELS: List<String> = listOf("hue", "saturation", "luminance")
        val BAND_INDEX: Map<String, Int> = COLOR_BANDS.withIndex().associate { (i, n) -> n to i }
        val CHANNEL_OFFSET: Map<String, Int> = mapOf("hue" to 0, "saturation" to 1, "luminance" to 2)
    }
}
