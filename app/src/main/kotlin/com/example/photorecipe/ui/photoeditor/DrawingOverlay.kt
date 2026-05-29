package com.example.photorecipe.ui.photoeditor

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.photorecipe.segmentation.PromptSegmentationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Drawing 기반 interactive segmentation overlay.
 *
 * 사용자가 캔버스 위에 손가락으로 스크리블을 하면 그 stroke 의 점들을 prompt 로
 * [PromptSegmentationEngine.segmentFromScribble] 에 넘겨 마스크를 만들고, 추가 스크리블이
 * 들어올 때마다 이전 iteration 의 low-res mask 를 다시 dense prompt 로 공급해서 마스크를
 * 정밀화한다.
 *
 * 토글 버튼 두 개 (+/-) 로 positive/negative 모드를 전환. 완료 버튼은 결과 마스크와
 * 자동 라벨을 [onCommit] 으로 부모에 넘긴다.
 */
@Composable
fun DrawingOverlay(
    promptSegmenter: PromptSegmentationEngine,
    prepared: PromptSegmentationEngine.PreparedImage,
    imageWidth: Int,
    imageHeight: Int,
    proposedLabel: String,
    onCancel: () -> Unit,
    onCommit: (label: String, alphaBitmap: Bitmap) -> Unit,
    modifier: Modifier = Modifier,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var positive by remember { mutableStateOf(true) }

    // 누적된 prompt 점들 (이미지 좌표계).
    val positivePoints = remember { mutableStateListOf<Float>() }
    val negativePoints = remember { mutableStateListOf<Float>() }

    // 현재 stroke 의 viewport 좌표 (그리는 동안만 시각화용).
    val activeStroke = remember { mutableStateListOf<androidx.compose.ui.geometry.Offset>() }

    var currentMask by remember { mutableStateOf<Bitmap?>(null) }
    var prevLowRes by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }

    // 점이 추가될 때마다 (drag 끝) SAM 재호출.
    var pendingSegment by remember { mutableStateOf(0) }
    LaunchedEffect(pendingSegment) {
        if (pendingSegment == 0) return@LaunchedEffect
        if (positivePoints.isEmpty() && negativePoints.isEmpty()) return@LaunchedEffect
        busy = true
        val pos = positivePoints.toFloatArray()
        val neg = negativePoints.toFloatArray()
        val prev = prevLowRes
        val result = withContext(Dispatchers.Default) {
            runCatching {
                promptSegmenter.segmentFromScribble(prepared, pos, neg, prev)
            }.getOrNull()
        }
        if (result != null) {
            currentMask = result.alpha
            prevLowRes = result.lowResMaskBytes
        }
        busy = false
    }

    Box(modifier = modifier
        .fillMaxSize()
        .onSizeChanged { boxSize = it }
    ) {
        // 현재 결과 마스크 오버레이 (반투명 흰색).
        currentMask?.let { mask ->
            androidx.compose.foundation.Image(
                bitmap = mask.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent),
                alpha = 0.45f,
            )
        }

        // Drag 캡처 + 스크리블 그리기.
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(positive, imageWidth, imageHeight) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            activeStroke.clear()
                            activeStroke.add(offset)
                        },
                        onDrag = { change, _ ->
                            activeStroke.add(change.position)
                            change.consume()
                        },
                        onDragEnd = {
                            val sampled = sampleStrokeToImage(
                                stroke = activeStroke.toList(),
                                viewW = boxSize.width,
                                viewH = boxSize.height,
                                imgW = imageWidth,
                                imgH = imageHeight,
                                spacingPx = SAMPLE_SPACING_VIEWPORT,
                            )
                            if (sampled.isNotEmpty()) {
                                val target = if (positive) positivePoints else negativePoints
                                for (v in sampled) target.add(v)
                                pendingSegment++
                            }
                            activeStroke.clear()
                        },
                        onDragCancel = { activeStroke.clear() },
                    )
                }
        ) {
            // 누적 점들 마커
            for (i in positivePoints.indices step 2) {
                val ix = positivePoints[i]; val iy = positivePoints[i + 1]
                val vx = ix * size.width / imageWidth.toFloat()
                val vy = iy * size.height / imageHeight.toFloat()
                drawCircle(POS_COLOR, radius = 6f, center = androidx.compose.ui.geometry.Offset(vx, vy))
            }
            for (i in negativePoints.indices step 2) {
                val ix = negativePoints[i]; val iy = negativePoints[i + 1]
                val vx = ix * size.width / imageWidth.toFloat()
                val vy = iy * size.height / imageHeight.toFloat()
                drawCircle(NEG_COLOR, radius = 6f, center = androidx.compose.ui.geometry.Offset(vx, vy))
            }
            // 진행 중인 stroke
            if (activeStroke.size > 1) {
                val path = Path().apply {
                    moveTo(activeStroke[0].x, activeStroke[0].y)
                    for (k in 1 until activeStroke.size) lineTo(activeStroke[k].x, activeStroke[k].y)
                }
                drawPath(
                    path = path,
                    color = if (positive) POS_COLOR else NEG_COLOR,
                    style = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }

        // 하단 컨트롤
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { positive = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (positive) POS_COLOR else Color.DarkGray,
                ),
                shape = RoundedCornerShape(50),
            ) { Text("+ Add") }
            Button(
                onClick = { positive = false },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (!positive) NEG_COLOR else Color.DarkGray,
                ),
                shape = RoundedCornerShape(50),
            ) { Text("- Remove") }
            Button(onClick = {
                positivePoints.clear()
                negativePoints.clear()
                currentMask = null
                prevLowRes = null
            }, shape = RoundedCornerShape(50)) { Text("Clear") }
            Button(onClick = onCancel, shape = RoundedCornerShape(50)) { Text("Cancel") }
            Button(
                onClick = {
                    val mask = currentMask ?: return@Button
                    onCommit(proposedLabel, mask)
                },
                enabled = currentMask != null && !busy,
                shape = RoundedCornerShape(50),
            ) { Text("Done") }
        }
    }
}

/**
 * Viewport 좌표 스크리블을 일정 간격으로 샘플링하고 이미지 좌표로 변환.
 * Returns flat [x0, y0, x1, y1, ...] in image-space pixels.
 */
private fun sampleStrokeToImage(
    stroke: List<androidx.compose.ui.geometry.Offset>,
    viewW: Int, viewH: Int,
    imgW: Int, imgH: Int,
    spacingPx: Float,
): FloatArray {
    if (stroke.isEmpty() || viewW == 0 || viewH == 0) return FloatArray(0)
    val sxView = imgW.toFloat() / viewW
    val syView = imgH.toFloat() / viewH
    val out = mutableListOf<Float>()
    var lastX = stroke[0].x; var lastY = stroke[0].y
    out.add(lastX * sxView); out.add(lastY * syView)
    for (k in 1 until stroke.size) {
        val dx = stroke[k].x - lastX
        val dy = stroke[k].y - lastY
        if (dx * dx + dy * dy < spacingPx * spacingPx) continue
        lastX = stroke[k].x; lastY = stroke[k].y
        out.add(lastX * sxView); out.add(lastY * syView)
    }
    return out.toFloatArray()
}

private val POS_COLOR = Color(0xFF34C759)
private val NEG_COLOR = Color(0xFFFF3B30)
private const val SAMPLE_SPACING_VIEWPORT = 18f
