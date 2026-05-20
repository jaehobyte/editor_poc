package com.example.photorecipe.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter.RegionOfInterest
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * Two-stage instance segmentation pipeline:
 *   1. [detect] uses MediaPipe ObjectDetector (efficientdet_lite0) to find
 *      candidate objects with bounding boxes + COCO class labels.
 *   2. [segment] uses MediaPipe InteractiveSegmenter (magic_touch) to obtain
 *      a per-object alpha mask given a point inside the bbox.
 *
 * Combine the two to get instance-like masks per detected object.
 */
class SegmentationEngine private constructor(
    private val segmenter: InteractiveSegmenter,
    private val detector: ObjectDetector,
) : AutoCloseable {

    /** Pre-detected object instance — used to enumerate things the user can mask. */
    data class DetectedInstance(
        val label: String,
        val score: Float,
        val bbox: RectF,                // pixel coordinates on the input bitmap
        val centerNormalized: Pair<Float, Float>,
    )

    /** Run object detection on [bitmap]. Returns top candidates sorted by score. */
    fun detect(
        bitmap: Bitmap,
        maxResults: Int = 8,
    ): List<DetectedInstance> {
        val image = BitmapImageBuilder(bitmap).build()
        val result = detector.detect(image)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        return result.detections().take(maxResults).mapNotNull { det ->
            val box = det.boundingBox()
            val cat = det.categories().firstOrNull() ?: return@mapNotNull null
            val cx = ((box.left + box.right) / 2f) / w
            val cy = ((box.top + box.bottom) / 2f) / h
            DetectedInstance(
                label = cat.categoryName(),
                score = cat.score(),
                bbox = RectF(box.left, box.top, box.right, box.bottom),
                centerNormalized = cx.coerceIn(0f, 1f) to cy.coerceIn(0f, 1f),
            )
        }
    }

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
        val confidenceMasks = result.confidenceMasks().orElse(null)
        return if (confidenceMasks != null && confidenceMasks.isNotEmpty()) {
            mpImageToAlphaBitmap(confidenceMasks[0])
        } else {
            val cat = result.categoryMask().orElseThrow { IllegalStateException("no mask") }
            mpImageToAlphaBitmap(cat)
        }
    }

    override fun close() {
        segmenter.close()
        detector.close()
    }

    /**
     * MPImage (single-channel mask) → ARGB_8888 bitmap with mask intensity in
     * the ALPHA channel and RGB = white. This lets the GL shader sample `.a`
     * and Compose Image overlays use `ColorFilter.tint(SrcIn)` directly.
     */
    private fun mpImageToAlphaBitmap(mp: MPImage): Bitmap {
        val w = mp.width
        val h = mp.height
        val buf = ByteBufferExtractor.extract(mp)
        buf.rewind()
        val pixels = IntArray(w * h)
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
        private const val SEG_MODEL = "magic_touch.tflite"
        private const val DET_MODEL = "efficientdet_lite0.tflite"

        fun create(context: Context): SegmentationEngine {
            val segOpts = InteractiveSegmenter.InteractiveSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(SEG_MODEL).build())
                .setOutputCategoryMask(true)
                .setOutputConfidenceMasks(true)
                .build()
            val segmenter = InteractiveSegmenter.createFromOptions(context, segOpts)

            val detOpts = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(DET_MODEL).build())
                .setMaxResults(10)
                .setScoreThreshold(0.45f)
                .build()
            val detector = ObjectDetector.createFromOptions(context, detOpts)

            return SegmentationEngine(segmenter, detector)
        }
    }
}
