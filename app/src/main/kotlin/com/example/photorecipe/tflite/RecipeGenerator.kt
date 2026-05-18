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
 * Input  args_0: [1, 3, 224, 224] float32 — Reference 이미지 (CHW, RGB, [0,1] 추정)
 * Input  args_1: [1, 3, 224, 224] float32 — Input 이미지     (CHW, RGB, [0,1] 추정)
 * Output       : [1, 29]          float32 — 보정 파라미터 [-1, 1]
 *
 * 정규화 [0,1]은 추정값. 실제 학습 파이프라인과 일치하는지 결과를 보고 검증 필요.
 */
class RecipeGenerator(context: Context) : AutoCloseable {

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
     * 비트맵 크기는 자유 — 내부에서 224×224로 리사이즈.
     */
    fun infer(reference: Bitmap, input: Bitmap): FloatArray {
        val refTensor = bitmapToChwFloat(reference)
        val inTensor = bitmapToChwFloat(input)

        val output = Array(1) { FloatArray(NUM_PARAMS) }
        val inputs = arrayOf<Any>(refTensor, inTensor)
        val outputs = mutableMapOf<Int, Any>(0 to output)
        interpreter.runForMultipleInputsOutputs(inputs, outputs)

        return output[0]
    }

    override fun close() {
        interpreter.close()
    }

    private fun bitmapToChwFloat(bitmap: Bitmap): ByteBuffer {
        val resized = if (bitmap.width == IMG_SIZE && bitmap.height == IMG_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, IMG_SIZE, IMG_SIZE, true)
        }

        val pixels = IntArray(IMG_SIZE * IMG_SIZE)
        resized.getPixels(pixels, 0, IMG_SIZE, 0, 0, IMG_SIZE, IMG_SIZE)

        val bytes = 1 * 3 * IMG_SIZE * IMG_SIZE * 4
        val buf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

        // CHW 순서: R 평면 전체 → G 평면 전체 → B 평면 전체
        for (px in pixels) {
            buf.putFloat(((px ushr 16) and 0xFF) / 255f)
        }
        for (px in pixels) {
            buf.putFloat(((px ushr 8) and 0xFF) / 255f)
        }
        for (px in pixels) {
            buf.putFloat((px and 0xFF) / 255f)
        }

        buf.rewind()
        return buf
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
    }
}
