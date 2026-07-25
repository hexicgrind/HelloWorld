package ai.sotto.assistant.ui.live

import ai.sotto.assistant.data.model.TargetState
import ai.sotto.assistant.ui.theme.SottoColors
import ai.sotto.assistant.vision.DetectedFace
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.max

/**
 * The reticle drawn over the viewfinder.
 *
 * Design Doc 1 § UI Components: "Main camera view with face detection overlay showing
 * detected person's name if matched."
 *
 * Colour carries the state and nothing else has to be read: white while acquiring,
 * teal once recognised, dim grey for a face we don't know. The corner-bracket style is
 * used instead of a full rectangle so the person's face stays unobstructed.
 */
@Composable
fun FaceOverlay(
    face: DetectedFace?,
    target: TargetState,
    frameWidth: Int,
    frameHeight: Int,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "reticle")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_100), RepeatMode.Reverse),
        label = "reticlePulse",
    )

    val accent = when (target) {
        is TargetState.Matched -> SottoColors.Teal
        is TargetState.Unrecognised -> SottoColors.Slate
        is TargetState.Tracking -> SottoColors.Cloud
        TargetState.NoFace -> Color.Transparent
    }

    val alpha = when (target) {
        is TargetState.Matched -> 1f
        is TargetState.Tracking -> pulse
        else -> 0.65f
    }

    Canvas(modifier.fillMaxSize()) {
        if (face == null || frameWidth <= 0 || frameHeight <= 0) return@Canvas

        val rect = mapToView(face, frameWidth, frameHeight, size) ?: return@Canvas
        val strokeWidth = dp(3f)
        val cornerLength = max(rect.width, rect.height) * 0.24f

        drawCornerBrackets(rect, accent.copy(alpha = alpha), strokeWidth, cornerLength)

        // A soft halo confirms a positive identification without hiding the face.
        if (target is TargetState.Matched) {
            drawRoundRectHalo(rect, SottoColors.Teal.copy(alpha = 0.14f))
        }

        // Dwell progress: the ring fills while the doc's one-second tracking window runs.
        if (target is TargetState.Tracking) {
            val progress = (target.dwellMs / 1_000f).coerceIn(0f, 1f)
            drawDwellArc(rect, SottoColors.Cloud.copy(alpha = 0.9f), progress, strokeWidth)
        }
    }
}

/**
 * Maps a face box from analysis-image pixels into view pixels.
 *
 * The preview uses FILL_CENTER, which scales by the larger of the two axis ratios and
 * centre-crops the overflow — the overlay has to do exactly the same or the box drifts
 * off the face.
 */
internal fun mapToView(
    face: DetectedFace,
    frameWidth: Int,
    frameHeight: Int,
    viewSize: Size,
): Rect? {
    if (viewSize.width <= 0f || viewSize.height <= 0f) return null

    val scale = max(viewSize.width / frameWidth, viewSize.height / frameHeight)
    val scaledWidth = frameWidth * scale
    val scaledHeight = frameHeight * scale
    val offsetX = (viewSize.width - scaledWidth) / 2f
    val offsetY = (viewSize.height - scaledHeight) / 2f

    val left = face.bounds.left * scale + offsetX
    val top = face.bounds.top * scale + offsetY
    val right = face.bounds.right * scale + offsetX
    val bottom = face.bounds.bottom * scale + offsetY

    if (right <= left || bottom <= top) return null
    return Rect(left, top, right, bottom)
}

private fun DrawScope.drawCornerBrackets(
    rect: Rect,
    color: Color,
    strokeWidth: Float,
    cornerLength: Float,
) {
    val path = Path().apply {
        // top-left
        moveTo(rect.left, rect.top + cornerLength)
        lineTo(rect.left, rect.top)
        lineTo(rect.left + cornerLength, rect.top)
        // top-right
        moveTo(rect.right - cornerLength, rect.top)
        lineTo(rect.right, rect.top)
        lineTo(rect.right, rect.top + cornerLength)
        // bottom-right
        moveTo(rect.right, rect.bottom - cornerLength)
        lineTo(rect.right, rect.bottom)
        lineTo(rect.right - cornerLength, rect.bottom)
        // bottom-left
        moveTo(rect.left + cornerLength, rect.bottom)
        lineTo(rect.left, rect.bottom)
        lineTo(rect.left, rect.bottom - cornerLength)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round),
    )
}

private fun DrawScope.drawRoundRectHalo(rect: Rect, color: Color) {
    drawRoundRect(
        color = color,
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(rect.width * 0.18f),
    )
}

private fun DrawScope.drawDwellArc(
    rect: Rect,
    color: Color,
    progress: Float,
    strokeWidth: Float,
) {
    val diameter = max(rect.width, rect.height) * 1.16f
    val topLeft = Offset(
        rect.center.x - diameter / 2f,
        rect.center.y - diameter / 2f,
    )
    drawArc(
        color = color,
        startAngle = -90f,
        sweepAngle = 360f * progress,
        useCenter = false,
        topLeft = topLeft,
        size = Size(diameter, diameter),
        style = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round),
    )
}

/** Converts dp to px inside a draw scope. */
private fun DrawScope.dp(value: Float): Float = value * density
