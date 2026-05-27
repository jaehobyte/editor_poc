package com.example.photorecipe.tflite

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * TFLite 추론 래퍼. 모델: recipe_generator_v260422.tflite
 *
 * `autographer/photoEditorDemo` 의 `TfliteRecipePredictor` 와 동일한 입출력 규약:
 *
 *   Input  args_0 = content   (편집할 이미지)  [1, 3, 224, 224] float32 [0, 1]
 *   Input  args_1 = reference (룩 참조)         [1, 3, 224, 224] float32 [0, 1]
 *   Output       = 29 floats [-1, 1] — Galaxy editor parameters
 *
 * 전처리: center-crop 정방형 → 224×224 bilinear resize → CHW [0, 1].
 * ImageNet 정규화는 TFLite 그래프 내부에 포함되어 있어 외부 처리 불필요.
 */
class RecipeGenerator(
    context: Context,
    /**
     * 모델 출력 29 개 (∈ [-1, 1]) 에 적용할 saturating boost 의 강도.
     *   P_new = (α·P) / (1 + (α-1)·|P|)
     * α=1 이면 원본 그대로. α>1 이면 0 근처는 α 배에 가깝게, ±1 근처는 그대로 유지
     * (clipping 없는 채도/색 강조).
     */
    private val colorBoostAlpha: Float = COLOR_BOOST_ALPHA,
) : AutoCloseable {

    private val interpreter: Interpreter

    init {
        val model = loadModelFile(context, MODEL_PATH)
        val options = Interpreter.Options().apply {
            numThreads = 4
        }
        interpreter = Interpreter(model, options)
    }

    /**
     * 두 비트맵에서 29개 파라미터 추론.
     *
     * @param content 편집할 이미지 (args_0)
     * @param reference 참조 룩 이미지 (args_1)
     */
    fun infer(content: Bitmap, reference: Bitmap): FloatArray {
        val contentTensor = bitmapToChwFloat(content)
        val referenceTensor = bitmapToChwFloat(reference)

        val output = Array(1) { FloatArray(NUM_PARAMS) }
        val inputs = arrayOf<Any>(contentTensor, referenceTensor)
        val outputs = mutableMapOf<Int, Any>(0 to output)
        interpreter.runForMultipleInputsOutputs(inputs, outputs)

        return boost(output[0], colorBoostAlpha)
    }

    private fun boost(params: FloatArray, alpha: Float): FloatArray {
        if (alpha == 1f) return params
        for (i in params.indices) {
            val p = params[i]
            params[i] = (alpha * p) / (1f + (alpha - 1f) * kotlin.math.abs(p))
        }
        return params
    }

    override fun close() {
        interpreter.close()
    }

    private fun bitmapToChwFloat(bitmap: Bitmap): ByteBuffer {
        // 데모와 동일: center-crop 정방형 → 224×224 bilinear resize.
        // 가로/세로 비율을 보존해야 모델이 학습 분포에 맞는 입력을 받음.
        val cropped = centerCropSquare(bitmap)
        val scaled = Bitmap.createScaledBitmap(cropped, IMG_SIZE, IMG_SIZE, true)

        val pixels = IntArray(IMG_SIZE * IMG_SIZE)
        scaled.getPixels(pixels, 0, IMG_SIZE, 0, 0, IMG_SIZE, IMG_SIZE)

        val bytes = 1 * 3 * IMG_SIZE * IMG_SIZE * 4
        val buf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

        // CHW: R 평면 전체 → G 평면 전체 → B 평면 전체
        for (px in pixels) buf.putFloat(((px ushr 16) and 0xFF) / 255f)
        for (px in pixels) buf.putFloat(((px ushr 8) and 0xFF) / 255f)
        for (px in pixels) buf.putFloat((px and 0xFF) / 255f)

        buf.rewind()
        return buf
    }

    private fun centerCropSquare(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        if (w == h) return bmp
        val side = minOf(w, h)
        val x = (w - side) / 2
        val y = (h - side) / 2
        return Bitmap.createBitmap(bmp, x, y, side, side)
    }

    private fun loadModelFile(context: Context, path: String): ByteBuffer {
        val fd = context.assets.openFd(path)
        val stream = FileInputStream(fd.fileDescriptor)
        val channel = stream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    companion object {
        const val MODEL_PATH = "recipe_generator_v260422.tflite"
        const val IMG_SIZE = 224
        const val NUM_PARAMS = 29
        const val COLOR_BOOST_ALPHA = 5f
    }
}
