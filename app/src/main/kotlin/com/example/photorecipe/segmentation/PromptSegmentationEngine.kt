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
import kotlin.math.roundToInt

/**
 * EfficientViT-SAM-L1 기반 prompt segmentation.
 *
 * 두 가지 호출 경로를 한 decoder 그래프에서 처리한다:
 *   - [segmentAtPoint]            : long-press 1점 prompt (PhotoEditor 빈 영역).
 *   - [segmentFromScribble]       : drawing UI 의 positive/negative 점 시퀀스 +
 *                                    이전 iteration 의 low-res mask 를 dense prompt 로 재공급
 *                                    → iterative refinement.
 *
 * Encoder 는 사진당 1회, decoder 는 prompt 당 1회.
 *
 * 좌표계: encoder 입력은 512×512 letterbox, decoder 의 prompt encoder 는 SAM 원본과
 * 같이 1024 좌표계로 학습돼 있어 prompt point 와 mask postprocessing 의 intermediate
 * size 가 서로 다르다 (각각 [ENC_SIZE], [DEC_TARGET]).
 */
class PromptSegmentationEngine private constructor(
    private val env: OrtEnvironment,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
) : AutoCloseable {

    /** 사진당 1번 만들어두는 encoder 결과 + 메타데이터. */
    class PreparedImage internal constructor(
        internal val embeddingBytes: ByteArray,
        internal val embeddingShape: LongArray,
        val originW: Int,
        val originH: Int,
    )

    /** Scribble 결과 — alpha 비트맵 + 다음 호출에 mask_input 으로 넣을 fp16 256×256 logits. */
    class ScribbleResult internal constructor(
        val alpha: Bitmap,
        /** 256×256 fp16 logits raw bytes. [segmentFromScribble] 의 prevLowResMask 로 전달. */
        val lowResMaskBytes: ByteArray,
    )

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

    /** Long-press 1점 → 알파 비트맵. 내부적으로 [segmentFromScribble] 의 single-positive 케이스. */
    fun segmentAtPoint(prepared: PreparedImage, imgX: Float, imgY: Float): Bitmap {
        return segmentFromScribble(
            prepared = prepared,
            positivePoints = floatArrayOf(imgX, imgY),
            negativePoints = FloatArray(0),
            prevLowResMask = null,
        ).alpha
    }

    /**
     * Scribble / multi-point prompt.
     *
     * @param positivePoints x0,y0,x1,y1,... 원본 이미지 좌표계의 positive 점들.
     * @param negativePoints 동일 포맷. 빈 배열이면 negative 없음.
     * @param prevLowResMask 직전 iteration 의 [ScribbleResult.lowResMaskBytes] (256×256 fp16).
     *                       처음 호출이면 null.
     */
    fun segmentFromScribble(
        prepared: PreparedImage,
        positivePoints: FloatArray,
        negativePoints: FloatArray,
        prevLowResMask: ByteArray?,
    ): ScribbleResult {
        require(positivePoints.size % 2 == 0 && negativePoints.size % 2 == 0) {
            "points must be flat x,y pairs"
        }
        val posCount = positivePoints.size / 2
        val negCount = negativePoints.size / 2
        require(posCount + negCount > 0) { "need at least one point" }

        val scale = DEC_TARGET.toFloat() / max(prepared.originW, prepared.originH).toFloat()
        val totalCount = posCount + negCount
        val coordsBuf = halfBuffer(totalCount * 2)
        val labelsBuf = halfBuffer(totalCount)
        for (i in 0 until posCount) {
            coordsBuf.put(toHalf(positivePoints[2 * i] * scale))
            coordsBuf.put(toHalf(positivePoints[2 * i + 1] * scale))
            labelsBuf.put(toHalf(1f))
        }
        for (i in 0 until negCount) {
            coordsBuf.put(toHalf(negativePoints[2 * i] * scale))
            coordsBuf.put(toHalf(negativePoints[2 * i + 1] * scale))
            labelsBuf.put(toHalf(0f))
        }
        coordsBuf.flip(); labelsBuf.flip()

        // mask_input + has_mask_input
        val maskInputBuf: ShortBuffer
        val hasMask: Float
        if (prevLowResMask != null && prevLowResMask.size == LOW_RES * LOW_RES * 2) {
            maskInputBuf = ByteBuffer.wrap(prevLowResMask).order(ByteOrder.nativeOrder()).asShortBuffer()
            hasMask = 1f
        } else {
            maskInputBuf = halfBuffer(LOW_RES * LOW_RES) // direct-allocated, zero-filled
            hasMask = 0f
        }
        val hasMaskBuf = halfBuffer(1).apply { put(toHalf(hasMask)); flip() }

        val embBuf = ByteBuffer.wrap(prepared.embeddingBytes).order(ByteOrder.nativeOrder()).asShortBuffer()
        val emb = OnnxTensor.createTensor(env, embBuf, prepared.embeddingShape, OnnxJavaType.FLOAT16)
        val coords = OnnxTensor.createTensor(env, coordsBuf, longArrayOf(1, totalCount.toLong(), 2), OnnxJavaType.FLOAT16)
        val labels = OnnxTensor.createTensor(env, labelsBuf, longArrayOf(1, totalCount.toLong()), OnnxJavaType.FLOAT16)
        val maskIn = OnnxTensor.createTensor(env, maskInputBuf, longArrayOf(1, 1, LOW_RES.toLong(), LOW_RES.toLong()), OnnxJavaType.FLOAT16)
        val hasMaskIn = OnnxTensor.createTensor(env, hasMaskBuf, longArrayOf(1), OnnxJavaType.FLOAT16)

        emb.use { e -> coords.use { c -> labels.use { l -> maskIn.use { m -> hasMaskIn.use { h ->
            decoder.run(mapOf(
                DEC_EMB to e, DEC_COORDS to c, DEC_LABELS to l,
                DEC_MASK_IN to m, DEC_HAS_MASK to h,
            )).use { out ->
                val masksTensor = out[0] as OnnxTensor
                val shape = masksTensor.info.shape // (1, 1, 256, 256)
                val maskH = shape[2].toInt()
                val maskW = shape[3].toInt()
                val rawShorts = ShortArray(maskH * maskW)
                val byteBuf = masksTensor.byteBuffer.order(ByteOrder.nativeOrder())
                byteBuf.asShortBuffer().get(rawShorts)
                val rawBytes = ByteArray(rawShorts.size * 2)
                ByteBuffer.wrap(rawBytes).order(ByteOrder.nativeOrder())
                    .asShortBuffer().put(rawShorts)
                val alpha = postprocessMask(rawShorts, maskW, maskH, prepared.originW, prepared.originH)
                return ScribbleResult(alpha, rawBytes)
            }
        }}}}}
    }

    override fun close() {
        encoder.close()
        decoder.close()
        env.close()
    }

    // ── Preprocess ──────────────────────────────────────────────────────────

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

        val buf = halfBuffer(3 * ENC_SIZE * ENC_SIZE)
        val plane = ENC_SIZE * ENC_SIZE
        val mean = floatArrayOf(0.4850f, 0.4560f, 0.4060f)
        val std = floatArrayOf(0.2290f, 0.2240f, 0.2250f)

        for (c in 0 until 3) {
            val m = mean[c]; val s = std[c]
            for (y in 0 until ENC_SIZE) {
                val rowBase = c * plane + y * ENC_SIZE
                if (y >= newH) {
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

    private fun postprocessMask(
        rawHalf: ShortArray, lowW: Int, lowH: Int,
        origW: Int, origH: Int,
    ): Bitmap {
        val scale = DEC_TARGET.toFloat() / max(origW, origH).toFloat()
        val prepadW = (origW * scale).roundToInt()
        val prepadH = (origH * scale).roundToInt()

        val outPixels = IntArray(origW * origH)
        val sxToPrepad = prepadW.toFloat() / origW.toFloat()
        val syToPrepad = prepadH.toFloat() / origH.toFloat()
        val sxPrepadToLow = lowW.toFloat() / DEC_TARGET.toFloat()
        val syPrepadToLow = lowH.toFloat() / DEC_TARGET.toFloat()

        for (y in 0 until origH) {
            val py = y * syToPrepad
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

    // ── Half-float helpers ──────────────────────────────────────────────────

    private fun toHalf(f: Float): Short = Half.toHalf(f)

    private fun halfBuffer(count: Int): ShortBuffer {
        val bb = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())
        return bb.asShortBuffer()
    }

    companion object {
        private const val ENC_MODEL = "efficientvit_sam_l1_encoder_fp16.onnx"
        private const val DEC_MODEL = "efficientvit_sam_l1_decoder_v2_fp16.onnx"

        private const val ENC_INPUT = "input_image"
        private const val DEC_EMB = "image_embeddings"
        private const val DEC_COORDS = "point_coords"
        private const val DEC_LABELS = "point_labels"
        private const val DEC_MASK_IN = "mask_input"
        private const val DEC_HAS_MASK = "has_mask_input"

        private const val ENC_SIZE = 512
        private const val DEC_TARGET = 1024
        private const val LOW_RES = 256

        private val WHITE_ALPHA: Int = AndroidColor.argb(255, 255, 255, 255)
        private val ZERO_HALF: Short = Half.toHalf(0f)

        fun create(context: Context): PromptSegmentationEngine {
            val env = OrtEnvironment.getEnvironment()
            // NNAPI provider 는 v2 decoder 의 mask_downscaling Conv 스택을 못 받아 session
            // 생성이 실패할 수 있다. 일단 안전하게 CPU 만. 추후 QNN EP 로 분기 예정.
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
            }
            val encBytes = context.assets.open(ENC_MODEL).use { it.readBytes() }
            val decBytes = context.assets.open(DEC_MODEL).use { it.readBytes() }
            val encoder = env.createSession(encBytes, opts)
            val decoder = env.createSession(decBytes, opts)
            return PromptSegmentationEngine(env, encoder, decoder)
        }
    }
}
