package com.example.photorecipe.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * Instance segmentation by combining two MediaPipe tasks:
 *
 *   1. ObjectDetector (efficientdet_lite0) — finds bounding boxes + COCO labels.
 *   2. ImageSegmenter (DeepLab v3, Pascal VOC 21 classes) — per-pixel category mask.
 *
 * For each detection we crop the semantic mask to the bbox and keep only the
 * pixels belonging to the matching Pascal class — this gives a per-instance
 * pixel mask. Classes outside Pascal VOC fall back to a rectangular bbox mask
 * so the editor still has something to operate on.
 *
 * (Interactive / tap-to-segment removed in favor of this fully automatic path.)
 */
class SegmentationEngine private constructor(
    private val detector: ObjectDetector,
    private val imageSegmenter: ImageSegmenter,
) : AutoCloseable {

    /** A detected object with its own pre-computed alpha mask. */
    data class DetectedInstance(
        val label: String,
        val score: Float,
        val bbox: RectF,
        /** ARGB_8888, same size as input. Alpha channel = mask intensity, RGB = white. */
        val alphaBitmap: Bitmap,
    )

    /**
     * Detect objects in [bitmap] and return per-instance masks.
     * Runs ObjectDetector once and ImageSegmenter once, then composes the two.
     */
    fun detectInstances(bitmap: Bitmap, maxResults: Int = 8): List<DetectedInstance> {
        val image = BitmapImageBuilder(bitmap).build()

        // 1) bounding boxes
        val detections = detector.detect(image).detections().take(maxResults)
        if (detections.isEmpty()) return emptyList()

        // 2) semantic mask (one byte per pixel = Pascal class index)
        val segResult = imageSegmenter.segment(image)
        val catMask = segResult.categoryMask().orElse(null) ?: return detections.toBboxFallback(bitmap)
        val w = catMask.width
        val h = catMask.height
        val classes = ByteArray(w * h)
        ByteBufferExtractor.extract(catMask).apply { rewind(); get(classes) }
        catMask.close()

        // 3) compose
        return detections.mapNotNull { det ->
            val cat0 = det.categories().firstOrNull() ?: return@mapNotNull null
            val label = cat0.categoryName().lowercase()
            val pascalIdx = COCO_TO_PASCAL[label]
            val b = det.boundingBox()
            val left = b.left.toInt().coerceIn(0, w)
            val top = b.top.toInt().coerceIn(0, h)
            val right = b.right.toInt().coerceIn(0, w)
            val bottom = b.bottom.toInt().coerceIn(0, h)
            if (right <= left || bottom <= top) return@mapNotNull null
            val mask = buildInstanceMask(classes, w, h, left, top, right, bottom, pascalIdx)
            DetectedInstance(
                label = label,
                score = cat0.score(),
                bbox = RectF(b.left, b.top, b.right, b.bottom),
                alphaBitmap = mask,
            )
        }
    }

    override fun close() {
        detector.close()
        imageSegmenter.close()
    }

    private fun List<com.google.mediapipe.tasks.components.containers.Detection>.toBboxFallback(
        bitmap: Bitmap,
    ): List<DetectedInstance> = mapNotNull { det ->
        val cat = det.categories().firstOrNull() ?: return@mapNotNull null
        val b = det.boundingBox()
        DetectedInstance(
            label = cat.categoryName().lowercase(),
            score = cat.score(),
            bbox = RectF(b.left, b.top, b.right, b.bottom),
            alphaBitmap = rectangularMask(bitmap.width, bitmap.height, b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()),
        )
    }

    private fun buildInstanceMask(
        classes: ByteArray,
        w: Int, h: Int,
        left: Int, top: Int, right: Int, bottom: Int,
        pascalIdx: Int?,
    ): Bitmap {
        val pixels = IntArray(w * h)
        if (pascalIdx != null) {
            val target = pascalIdx.toByte()
            for (y in top until bottom) {
                val row = y * w
                for (x in left until right) {
                    if (classes[row + x] == target) {
                        pixels[row + x] = WHITE_ALPHA
                    }
                }
            }
        } else {
            // Pascal에 없는 COCO 클래스(e.g. cell phone, bottle, food) — bbox 직사각형으로 폴백.
            for (y in top until bottom) {
                val row = y * w
                for (x in left until right) {
                    pixels[row + x] = WHITE_ALPHA
                }
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun rectangularMask(w: Int, h: Int, l: Int, t: Int, r: Int, b: Int): Bitmap {
        val pixels = IntArray(w * h)
        val left = l.coerceIn(0, w); val top = t.coerceIn(0, h)
        val right = r.coerceIn(0, w); val bottom = b.coerceIn(0, h)
        for (y in top until bottom) {
            val row = y * w
            for (x in left until right) pixels[row + x] = WHITE_ALPHA
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    companion object {
        private const val DET_MODEL = "efficientdet_lite0.tflite"
        private const val SEG_MODEL = "deeplab_v3.tflite"
        private val WHITE_ALPHA: Int = AndroidColor.argb(255, 255, 255, 255)

        /** COCO 라벨 → Pascal VOC 클래스 인덱스 (DeepLab v3 출력 인덱스). */
        private val COCO_TO_PASCAL: Map<String, Int> = mapOf(
            "person" to 15,
            "bicycle" to 2,
            "car" to 7,
            "motorcycle" to 14,
            "airplane" to 1,
            "bus" to 6,
            "train" to 19,
            "boat" to 4,
            "bird" to 3,
            "cat" to 8,
            "dog" to 12,
            "horse" to 13,
            "sheep" to 17,
            "cow" to 10,
            "bottle" to 5,
            "chair" to 9,
            "couch" to 18,
            "potted plant" to 16,
            "dining table" to 11,
            "tv" to 20,
        )

        fun create(context: Context): SegmentationEngine {
            val detOpts = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(DET_MODEL).build())
                .setMaxResults(10)
                .setScoreThreshold(0.45f)
                .build()
            val detector = ObjectDetector.createFromOptions(context, detOpts)

            val segOpts = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(SEG_MODEL).build())
                .setOutputCategoryMask(true)
                .setOutputConfidenceMasks(false)
                .build()
            val imageSegmenter = ImageSegmenter.createFromOptions(context, segOpts)

            return SegmentationEngine(detector, imageSegmenter)
        }
    }
}
