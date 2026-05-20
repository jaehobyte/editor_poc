package com.example.photorecipe.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * SegFormer-B0 ADE20K (150 classes) — 풍경(scenery) 세그멘테이션.
 *
 * Input  : [1, 512, 512, 3] float32, ImageNet 정규화 (mean/std).
 * Output : [1, 128, 128, 150] float32 logits (입력 1/4 해상도).
 *
 * 흐름:
 *   1) bitmap → aspect-ratio 를 유지하면서 letterbox 로 512×512 에 맞춤.
 *      (stretch 시키면 풍경 추론이 비정상으로 망가지고, 정사각 알파 비트맵을
 *       만들어 Compose `ContentScale.Fit` 오버레이가 실제 이미지 영역과
 *       어긋나는 버그가 있었음.)
 *   2) interpreter.run → logits, per-pixel argmax → 128×128 label map.
 *   3) padding 영역은 잘라내고, 실제 컨텐츠 영역의 label 만 사용해서
 *      입력 비트맵 해상도(bitmap.width × bitmap.height) 그대로의 alpha
 *      mask Bitmap 을 생성. (DeepLab 마스크와 동일한 좌표계.)
 */
class SegFormerSceneryEngine private constructor(
    private val interpreter: Interpreter,
) : AutoCloseable {

    private val inputBuf: ByteBuffer =
        ByteBuffer.allocateDirect(IN_SIZE * IN_SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val outputBuf: ByteBuffer =
        ByteBuffer.allocateDirect(OUT_SIZE * OUT_SIZE * NUM_CLASSES * 4).order(ByteOrder.nativeOrder())

    /** 풍경 클래스 마스크들. 영역 큰 순서로 정렬되어 반환. */
    fun detectScenery(
        bitmap: Bitmap,
        minAreaFrac: Float = 0.04f,
    ): List<SegmentationEngine.DetectedInstance> {
        val srcW = bitmap.width
        val srcH = bitmap.height

        // ── 1) Letterbox-resize to 512×512 ──────────────────────────────
        val scale = minOf(IN_SIZE.toFloat() / srcW, IN_SIZE.toFloat() / srcH)
        val contentW = (srcW * scale).toInt().coerceIn(1, IN_SIZE)
        val contentH = (srcH * scale).toInt().coerceIn(1, IN_SIZE)
        val padLeft = (IN_SIZE - contentW) / 2
        val padTop = (IN_SIZE - contentH) / 2
        val resized = Bitmap.createScaledBitmap(bitmap, contentW, contentH, true)
        fillInputBufferLetterboxed(resized, padLeft, padTop, contentW, contentH)

        // ── 2) Inference + argmax ───────────────────────────────────────
        outputBuf.rewind()
        interpreter.run(inputBuf, outputBuf)
        outputBuf.rewind()
        val labels = argmaxToLabels(outputBuf)

        // ── 3) Content region in 128×128 output coords ──────────────────
        // 정수 나눗셈으로 보수적으로 안쪽만 사용 → padding 픽셀이 결과에 새지 않음.
        val outPadLeft = (padLeft + (RATIO - 1)) / RATIO          // ceil
        val outPadTop = (padTop + (RATIO - 1)) / RATIO
        val outRight = (padLeft + contentW) / RATIO               // floor
        val outBottom = (padTop + contentH) / RATIO
        val outContentW = (outRight - outPadLeft).coerceAtLeast(1)
        val outContentH = (outBottom - outPadTop).coerceAtLeast(1)

        // padding 을 제외한 컨텐츠 영역에서 클래스별 픽셀 카운트.
        val counts = IntArray(NUM_CLASSES)
        for (y in outPadTop until outPadTop + outContentH) {
            val row = y * OUT_SIZE
            for (x in outPadLeft until outPadLeft + outContentW) {
                counts[labels[row + x].toInt() and 0xFF]++
            }
        }

        val totalArea = outContentW * outContentH
        val minArea = (totalArea * minAreaFrac).toInt()

        val out = ArrayList<SegmentationEngine.DetectedInstance>()
        for ((id, label) in SCENERY_CLASSES) {
            if (counts[id] < minArea) continue
            val mask = buildAlphaMaskFromCrop(
                labels = labels,
                target = id.toByte(),
                cropX = outPadLeft, cropY = outPadTop,
                cropW = outContentW, cropH = outContentH,
                dstW = srcW, dstH = srcH,
            )
            out += SegmentationEngine.DetectedInstance(
                label = label,
                score = counts[id].toFloat() / totalArea,
                bbox = RectF(0f, 0f, srcW.toFloat(), srcH.toFloat()),
                alphaBitmap = mask,
            )
        }
        out.sortByDescending { it.score }
        return out
    }

    override fun close() {
        interpreter.close()
    }

    private fun fillInputBufferLetterboxed(
        content: Bitmap,
        padLeft: Int, padTop: Int,
        contentW: Int, contentH: Int,
    ) {
        // padding 영역의 normalize 값. raw RGB 0 을 ImageNet normalize 한 값.
        val padR = (-0.485f) / 0.229f
        val padG = (-0.456f) / 0.224f
        val padB = (-0.406f) / 0.225f

        val pixels = IntArray(contentW * contentH)
        content.getPixels(pixels, 0, contentW, 0, 0, contentW, contentH)

        inputBuf.rewind()
        for (y in 0 until IN_SIZE) {
            val cy = y - padTop
            val inY = cy in 0 until contentH
            for (x in 0 until IN_SIZE) {
                val cx = x - padLeft
                if (inY && cx in 0 until contentW) {
                    val px = pixels[cy * contentW + cx]
                    val r = ((px ushr 16) and 0xFF) / 255f
                    val g = ((px ushr 8) and 0xFF) / 255f
                    val b = (px and 0xFF) / 255f
                    inputBuf.putFloat((r - 0.485f) / 0.229f)
                    inputBuf.putFloat((g - 0.456f) / 0.224f)
                    inputBuf.putFloat((b - 0.406f) / 0.225f)
                } else {
                    inputBuf.putFloat(padR)
                    inputBuf.putFloat(padG)
                    inputBuf.putFloat(padB)
                }
            }
        }
        inputBuf.rewind()
    }

    private fun argmaxToLabels(buf: ByteBuffer): ByteArray {
        val labels = ByteArray(OUT_SIZE * OUT_SIZE)
        val fb = buf.asFloatBuffer()
        val tmp = FloatArray(NUM_CLASSES)
        for (i in 0 until OUT_SIZE * OUT_SIZE) {
            fb.get(tmp)
            var best = 0
            var bestVal = tmp[0]
            for (c in 1 until NUM_CLASSES) {
                if (tmp[c] > bestVal) { bestVal = tmp[c]; best = c }
            }
            labels[i] = best.toByte()
        }
        return labels
    }

    /**
     * 128×128 label map 의 `[cropX, cropY, cropW, cropH]` 영역을 nearest-neighbor
     * 로 `(dstW, dstH)` alpha bitmap 으로 업샘플. `target` 라벨인 픽셀만 불투명.
     */
    private fun buildAlphaMaskFromCrop(
        labels: ByteArray,
        target: Byte,
        cropX: Int, cropY: Int, cropW: Int, cropH: Int,
        dstW: Int, dstH: Int,
    ): Bitmap {
        val pixels = IntArray(dstW * dstH)
        for (y in 0 until dstH) {
            // 정수 매핑으로 src y 결정 (clamp 로 끝 안전).
            val sy = (cropY + (y * cropH) / dstH).coerceAtMost(cropY + cropH - 1)
            val srcRow = sy * OUT_SIZE
            val dstRow = y * dstW
            for (x in 0 until dstW) {
                val sx = (cropX + (x * cropW) / dstW).coerceAtMost(cropX + cropW - 1)
                if (labels[srcRow + sx] == target) {
                    pixels[dstRow + x] = WHITE_ALPHA
                }
            }
        }
        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, dstW, 0, 0, dstW, dstH)
        return out
    }

    companion object {
        private const val MODEL_PATH = "segformer_b0_ade20k.tflite"
        private const val IN_SIZE = 512
        private const val OUT_SIZE = 128
        private const val RATIO = IN_SIZE / OUT_SIZE  // 4
        private const val NUM_CLASSES = 150
        private val WHITE_ALPHA: Int = AndroidColor.argb(255, 255, 255, 255)

        /** ADE20K class id → 사용자에게 보여줄 라벨. 풍경/배경 위주. */
        private val SCENERY_CLASSES: Map<Int, String> = mapOf(
            1 to "building",
            2 to "sky",
            4 to "tree",
            6 to "road",
            9 to "grass",
            11 to "sidewalk",
            13 to "earth",
            16 to "mountain",
            17 to "plant",
            21 to "water",
            26 to "sea",
            29 to "field",
            34 to "rock",
            46 to "sand",
            48 to "skyscraper",
            52 to "path",
            60 to "river",
            61 to "bridge",
            68 to "hill",
            72 to "palm",
            91 to "dirt road",
            94 to "land",
            113 to "waterfall",
            128 to "lake",
        )

        fun create(context: Context): SegFormerSceneryEngine {
            val model = loadModelFile(context, MODEL_PATH)
            val opts = Interpreter.Options().apply { numThreads = 4 }
            return SegFormerSceneryEngine(Interpreter(model, opts))
        }

        private fun loadModelFile(context: Context, path: String): ByteBuffer {
            val fd = context.assets.openFd(path)
            val stream = FileInputStream(fd.fileDescriptor)
            return stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }
}
