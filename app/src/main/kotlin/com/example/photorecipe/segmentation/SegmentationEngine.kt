package com.example.photorecipe.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter.RegionOfInterest

/**
 * Tap-to-segment using MediaPipe's InteractiveSegmenter (magic_touch.tflite).
 *
 * Input:  a bitmap + a normalized (x, y) point in [0, 1] indicating the object.
 * Output: a single-channel grayscale Bitmap where each pixel is 0..255 alpha
 *         representing membership probability in the object.
 *
 * The output bitmap is the same size as the input image.
 */
class SegmentationEngine private constructor(
    private val segmenter: InteractiveSegmenter,
) : AutoCloseable {

    /**
     * @param normalizedX point in [0, 1], left → right
     * @param normalizedY point in [0, 1], top → bottom
     */
    fun segment(bitmap: Bitmap, normalizedX: Float, normalizedY: Float): Bitmap {
        val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
        val roi = RegionOfInterest.create(
            NormalizedKeypoint.create(normalizedX.coerceIn(0f, 1f), normalizedY.coerceIn(0f, 1f)),
        )
        val result = segmenter.segment(mpImage, roi)
        // categoryMask: 1 channel, each pixel = label (0/255 for foreground/background)
        // confidenceMasks: probability map. We prefer confidence for smooth alpha.
        val confidenceMasks = result.confidenceMasks().orElse(null)
        return if (confidenceMasks != null && confidenceMasks.isNotEmpty()) {
            mpImageToGrayBitmap(confidenceMasks[0])
        } else {
            val cat = result.categoryMask().orElseThrow { IllegalStateException("no mask") }
            mpImageToGrayBitmap(cat)
        }
    }

    override fun close() {
        segmenter.close()
    }

    private fun mpImageToGrayBitmap(mp: MPImage): Bitmap {
        val w = mp.width
        val h = mp.height
        val buf = ByteBufferExtractor.extract(mp)
        buf.rewind()
        val pixels = IntArray(w * h)
        // confidenceMasks 는 float32 한 채널, categoryMask 는 uint8 한 채널.
        // MediaPipe Tasks 의 ByteBuffer 는 형식이 다르므로 capacity 로 분기.
        // mask intensity 를 알파 채널에 저장하고 RGB 는 흰색. 이렇게 두면:
        //  - GL 셰이더: texture(u_mask, v_uv).a 로 마스크 알파 읽기
        //  - Compose Image: ColorFilter.tint(color) 만 걸어주면 그 색이 마스크 영역에만 그려짐
        val capacity = buf.capacity()
        if (capacity == w * h * 4) {
            val f = buf.asFloatBuffer()
            for (i in 0 until w * h) {
                val a = (f.get(i).coerceIn(0f, 1f) * 255f).toInt()
                pixels[i] = AndroidColor.argb(a, 255, 255, 255)
            }
        } else {
            for (i in 0 until w * h) {
                val a = buf.get(i).toInt() and 0xFF
                pixels[i] = AndroidColor.argb(a, 255, 255, 255)
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    companion object {
        private const val MODEL_PATH = "magic_touch.tflite"

        fun create(context: Context): SegmentationEngine {
            val baseOpts = BaseOptions.builder()
                .setModelAssetPath(MODEL_PATH)
                .build()
            val opts = InteractiveSegmenter.InteractiveSegmenterOptions.builder()
                .setBaseOptions(baseOpts)
                .setOutputCategoryMask(true)
                .setOutputConfidenceMasks(true)
                .build()
            return SegmentationEngine(InteractiveSegmenter.createFromOptions(context, opts))
        }
    }
}
