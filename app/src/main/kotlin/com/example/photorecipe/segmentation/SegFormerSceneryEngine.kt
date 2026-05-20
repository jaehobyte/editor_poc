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
 *   1) bitmap → 512×512 stretch resize → ImageNet 정규화 → NHWC float buffer
 *   2) interpreter.run → logits
 *   3) per-pixel argmax → 128×128 라벨 맵
 *   4) [SCENERY_CLASSES] 화이트리스트의 클래스 중 [minAreaFrac] 이상 차지하는
 *      클래스마다 512×512 alpha mask Bitmap 생성 (nearest-neighbor 업샘플).
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
        val resized = Bitmap.createScaledBitmap(bitmap, IN_SIZE, IN_SIZE, true)
        fillInputBuffer(resized)

        outputBuf.rewind()
        interpreter.run(inputBuf, outputBuf)
        outputBuf.rewind()

        val labels = argmaxToLabels(outputBuf)
        val up = upsampleNearest(labels, OUT_SIZE, IN_SIZE)

        val counts = IntArray(NUM_CLASSES)
        for (b in up) counts[b.toInt() and 0xFF]++

        val minArea = (IN_SIZE * IN_SIZE * minAreaFrac).toInt()
        val out = ArrayList<SegmentationEngine.DetectedInstance>()
        for ((id, label) in SCENERY_CLASSES) {
            if (counts[id] < minArea) continue
            val mask = buildAlphaMask(up, id.toByte())
            val frac = counts[id].toFloat() / (IN_SIZE * IN_SIZE)
            out += SegmentationEngine.DetectedInstance(
                label = label,
                score = frac,
                bbox = RectF(0f, 0f, IN_SIZE.toFloat(), IN_SIZE.toFloat()),
                alphaBitmap = mask,
            )
        }
        out.sortByDescending { it.score }
        return out
    }

    override fun close() {
        interpreter.close()
    }

    private fun fillInputBuffer(bmp: Bitmap) {
        val pixels = IntArray(IN_SIZE * IN_SIZE)
        bmp.getPixels(pixels, 0, IN_SIZE, 0, 0, IN_SIZE, IN_SIZE)
        inputBuf.rewind()
        for (px in pixels) {
            val r = ((px ushr 16) and 0xFF) / 255f
            val g = ((px ushr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            inputBuf.putFloat((r - 0.485f) / 0.229f)
            inputBuf.putFloat((g - 0.456f) / 0.224f)
            inputBuf.putFloat((b - 0.406f) / 0.225f)
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

    private fun upsampleNearest(src: ByteArray, srcSize: Int, dstSize: Int): ByteArray {
        val out = ByteArray(dstSize * dstSize)
        val scale = dstSize / srcSize
        for (y in 0 until dstSize) {
            val sy = y / scale
            val srcRow = sy * srcSize
            val dstRow = y * dstSize
            for (x in 0 until dstSize) {
                out[dstRow + x] = src[srcRow + (x / scale)]
            }
        }
        return out
    }

    private fun buildAlphaMask(labels: ByteArray, target: Byte): Bitmap {
        val pixels = IntArray(IN_SIZE * IN_SIZE)
        for (i in labels.indices) {
            if (labels[i] == target) pixels[i] = WHITE_ALPHA
        }
        val out = Bitmap.createBitmap(IN_SIZE, IN_SIZE, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, IN_SIZE, 0, 0, IN_SIZE, IN_SIZE)
        return out
    }

    companion object {
        private const val MODEL_PATH = "segformer_b0_ade20k.tflite"
        private const val IN_SIZE = 512
        private const val OUT_SIZE = 128
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
