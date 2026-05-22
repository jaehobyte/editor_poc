package com.example.photorecipe.segmentation

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.util.Half
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * EfficientViT-SAM-L1 기반 prompt segmentation.
 *
 * Encoder 는 한 사진당 1회만 돌고 (≈ NPU에서 100ms 미만 예상), 이후 long-press 마다
 * decoder 만 돌면 됨 (≈ 10-30ms). 결과는 기존 [SegmentationEngine.DetectedInstance] 와
 * 같은 형식의 알파 비트맵으로 반환되어 마스크 칩/편집 파이프라인에 그대로 흘러간다.
 *
 * 좌표계 메모: encoder 입력은 512×512 letterbox, decoder 의 prompt encoder 는 SAM 원본과
 * 같이 1024 좌표계로 학습되어 있다. 그래서 prompt point 변환과 mask postprocessing 의
 * intermediate size 가 서로 다르다 (각각 [ENC_SIZE], [DEC_TARGET]).
 */
class PromptSegmentationEngine private constructor(
    private val env: OrtEnvironment,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
) : AutoCloseable {

    /**
     * Cached encoder output + letterbox metadata for one image. embedding 은
     * fp16 raw bytes 로 보관 → 매번 새 OnnxTensor 로 감싸서 decoder 에 전달.
     */
    class PreparedImage internal constructor(
        internal val embeddingBytes: ByteArray,
        internal val embeddingShape: LongArray,
        val originW: Int,
        val originH: Int,
    )

    /** Encoder 1회 실행 → embedding 캐시. 사진 로딩/크롭 직후 호출. */
    fun prepare(bitmap: Bitmap): PreparedImage {
        val (input, _, _) = preprocess(bitmap)
        val tensor = OnnxTensor.createTensor(
            env, input, longArrayOf(1, 3, ENC_SIZE.toLong(), ENC_SIZE.toLong()),
            OnnxJavaType.FLOAT16,
        )
        return tensor.use {
            encoder.run(mapOf(ENC_INPUT to it)).use { out ->
                val emb = out[0] as OnnxTensor
                val shape = emb.info.shape
                val src = emb.byteBuffer.order(ByteOrder.nativeOrder())
                val bytes = ByteArray(src.remaining())
                src.get(bytes)
                PreparedImage(bytes, shape, bitmap.width, bitmap.height)
            }
        }
    }

    /**
     * 지정한 이미지 좌표 [imgX], [imgY] 를 positive point prompt 로 decoder 실행 →
     * 알파 비트맵 (ARGB_8888, alpha=255 if foreground, RGB=white).
     * 반환 비트맵은 [prepared.originW] × [prepared.originH] 사이즈.
     */
    fun segmentAtPoint(prepared: PreparedImage, imgX: Float, imgY: Float): Bitmap {
        // SAM prompt encoder 는 1024 좌표계
        val scale = DEC_TARGET.toFloat() / max(prepared.originW, prepared.originH).toFloat()
        val px = imgX * scale
        val py = imgY * scale

        val coordsBuf = halfBuffer(2).apply { put(toHalf(px)); put(toHalf(py)); flip() }
        val labelsBuf = halfBuffer(1).apply { put(toHalf(1f)); flip() }

        val embBuf = ByteBuffer.wrap(prepared.embeddingBytes).order(ByteOrder.nativeOrder()).asShortBuffer()
        val embTensor = OnnxTensor.createTensor(env, embBuf, prepared.embeddingShape, OnnxJavaType.FLOAT16)
        val coords = OnnxTensor.createTensor(env, coordsBuf, longArrayOf(1, 1, 2), OnnxJavaType.FLOAT16)
        val labels = OnnxTensor.createTensor(env, labelsBuf, longArrayOf(1, 1), OnnxJavaType.FLOAT16)
        embTensor.use { e -> coords.use { c -> labels.use { l ->
            decoder.run(mapOf(
                DEC_EMB to e,
                DEC_COORDS to c,
                DEC_LABELS to l,
            )).use { out ->
                val masksTensor = out[0] as OnnxTensor
                val shape = masksTensor.info.shape // (1, 1, 256, 256)
                val maskH = shape[2].toInt()
                val maskW = shape[3].toInt()
                val raw = ShortArray(maskH * maskW)
                masksTensor.byteBuffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(raw)
                return postprocessMask(raw, maskW, maskH, prepared.originW, prepared.originH)
            }
        }}}
    }

    override fun close() {
        encoder.close()
        decoder.close()
        env.close()
    }

    // ── Preprocess ──────────────────────────────────────────────────────────

    /**
     * Bitmap → fp16 ShortBuffer of shape (1,3,512,512), letterbox to long-side 512,
     * ImageNet-normalized.
     */
    private fun preprocess(bitmap: Bitmap): Triple<ShortBuffer, Int, Int> {
        val origW = bitmap.width
        val origH = bitmap.height
        val scale = ENC_SIZE.toFloat() / max(origW, origH).toFloat()
        val newW = (origW * scale + 0.5f).toInt().coerceAtMost(ENC_SIZE)
        val newH = (origH * scale + 0.5f).toInt().coerceAtMost(ENC_SIZE)

        val resized = Bitmap.createScaledBitmap(bitmap, newW, newH, /* filter = */ true)
        val pixels = IntArray(newW * newH)
        resized.getPixels(pixels, 0, newW, 0, 0, newW, newH)
        if (resized !== bitmap) resized.recycle()

        // CHW float16, padded with 0 to ENC_SIZE×ENC_SIZE
        val buf = halfBuffer(3 * ENC_SIZE * ENC_SIZE)
        val plane = ENC_SIZE * ENC_SIZE
        // SAM ImageNet stats / 255
        // mean = [0.4850, 0.4560, 0.4060], std = [0.2290, 0.2240, 0.2250]
        val mean = floatArrayOf(0.4850f, 0.4560f, 0.4060f)
        val std = floatArrayOf(0.2290f, 0.2240f, 0.2250f)

        // pre-fill zeros (allocateDirect already zero, so only fill content rows)
        // Write CHW: channel-major
        for (c in 0 until 3) {
            val m = mean[c]; val s = std[c]
            for (y in 0 until ENC_SIZE) {
                val rowBase = c * plane + y * ENC_SIZE
                if (y >= newH) {
                    // remaining rows stay zero (padding)
                    for (x in 0 until ENC_SIZE) buf.put(rowBase + x, ZERO_HALF)
                    continue
                }
                val pxRow = y * newW
                for (x in 0 until ENC_SIZE) {
                    if (x >= newW) { buf.put(rowBase + x, ZERO_HALF); continue }
                    val p = pixels[pxRow + x]
                    val v = when (c) {
                        0 -> ((p shr 16) and 0xFF) / 255f
                        1 -> ((p shr 8) and 0xFF) / 255f
                        else -> (p and 0xFF) / 255f
                    }
                    buf.put(rowBase + x, toHalf((v - m) / s))
                }
            }
        }
        buf.position(0)
        return Triple(buf, newW, newH)
    }

    // ── Postprocess ─────────────────────────────────────────────────────────

    /**
     * Decoder 의 low-res mask (256×256 fp16 logits) → original 사이즈 ARGB_8888.
     * Reference: applications/efficientvit_sam/run_efficientvit_sam_onnx.py mask_postprocessing.
     */
    private fun postprocessMask(
        rawHalf: ShortArray, lowW: Int, lowH: Int,
        origW: Int, origH: Int,
    ): Bitmap {
        // 1) low-res (256×256) → DEC_TARGET×DEC_TARGET bilinear
        // 2) crop to prepadded size (long-side = DEC_TARGET)
        // 3) resize to (origW, origH)
        // 메모리/속도를 위해 두 resize 를 합치고 logits>0 으로 바로 alpha 만들기.

        val scale = DEC_TARGET.toFloat() / max(origW, origH).toFloat()
        val prepadW = (origW * scale).roundToInt()
        val prepadH = (origH * scale).roundToInt()

        val outPixels = IntArray(origW * origH)
        // origin (x, y) → prepad → low-res 좌표
        val sxToPrepad = prepadW.toFloat() / origW.toFloat()
        val syToPrepad = prepadH.toFloat() / origH.toFloat()
        val sxPrepadToLow = lowW.toFloat() / DEC_TARGET.toFloat()
        val syPrepadToLow = lowH.toFloat() / DEC_TARGET.toFloat()

        for (y in 0 until origH) {
            val py = y * syToPrepad   // in prepad space (0..prepadH)
            val ly = py * syPrepadToLow
            val ly0 = ly.toInt().coerceIn(0, lowH - 1)
            val ly1 = (ly0 + 1).coerceAtMost(lowH - 1)
            val fy = ly - ly0
            val rowDst = y * origW
            for (x in 0 until origW) {
                val px = x * sxToPrepad
                val lx = px * sxPrepadToLow
                val lx0 = lx.toInt().coerceIn(0, lowW - 1)
                val lx1 = (lx0 + 1).coerceAtMost(lowW - 1)
                val fx = lx - lx0
                val v00 = Half.toFloat(rawHalf[ly0 * lowW + lx0])
                val v01 = Half.toFloat(rawHalf[ly0 * lowW + lx1])
                val v10 = Half.toFloat(rawHalf[ly1 * lowW + lx0])
                val v11 = Half.toFloat(rawHalf[ly1 * lowW + lx1])
                val top = v00 + (v01 - v00) * fx
                val bot = v10 + (v11 - v10) * fx
                val v = top + (bot - top) * fy
                outPixels[rowDst + x] = if (v > 0f) WHITE_ALPHA else 0
            }
        }

        val out = Bitmap.createBitmap(origW, origH, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, origW, 0, 0, origW, origH)
        return out
    }

    // ── Half-float helpers (android.util.Half, API 26+) ─────────────────────

    private fun toHalf(f: Float): Short = Half.toHalf(f)

    private fun halfBuffer(count: Int): ShortBuffer {
        val bb = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())
        return bb.asShortBuffer()
    }

    companion object {
        private const val ENC_MODEL = "efficientvit_sam_l1_encoder_fp16.onnx"
        private const val DEC_MODEL = "efficientvit_sam_l1_decoder_fp16.onnx"

        private const val ENC_INPUT = "input_image"
        private const val DEC_EMB = "image_embeddings"
        private const val DEC_COORDS = "point_coords"
        private const val DEC_LABELS = "point_labels"

        private const val ENC_SIZE = 512
        private const val DEC_TARGET = 1024

        private val WHITE_ALPHA: Int = AndroidColor.argb(255, 255, 255, 255)
        private val ZERO_HALF: Short = Half.toHalf(0f)

        fun create(context: Context): PromptSegmentationEngine {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                // NNAPI / QNN provider 는 모델 형식이 안 맞으면 throw → 기본 CPU 로 fallback.
                runCatching { addNnapi() }
            }
            val encBytes = context.assets.open(ENC_MODEL).use { it.readBytes() }
            val decBytes = context.assets.open(DEC_MODEL).use { it.readBytes() }
            val encoder = env.createSession(encBytes, opts)
            val decoder = env.createSession(decBytes, opts)
            return PromptSegmentationEngine(env, encoder, decoder)
        }
    }
}
