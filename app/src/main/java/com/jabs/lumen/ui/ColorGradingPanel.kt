package com.jabs.lumen.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.jabs.lumen.model.GradeWheel
import kotlin.math.cos
import kotlin.math.sin

/** One colour grading wheel: drag the dot to pick hue and strength together. */
@Composable
fun ColorWheel(
    wheel: GradeWheel,
    onChange: (GradeWheel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .size(124.dp)
            .background(Color(0xFF14161A), CircleShape)
            .pointerInput(wheel) {
                detectDragGestures(
                    onDragStart = { onChange(wheel.fromOffset(it, size.width.toFloat())) },
                    onDragEnd = {},
                    onDragCancel = {},
                    onDrag = { change, _ ->
                        change.consume()
                        onChange(wheel.fromOffset(change.position, size.width.toFloat()))
                    },
                )
            },
    ) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        // sweepGradient takes normalised stops (0..1) and starts at 3 o'clock, going clockwise.
        val hueBrush = Brush.sweepGradient(
            0f to Color.Red,
            1f / 6f to Color.Yellow,
            2f / 6f to Color.Green,
            3f / 6f to Color.Cyan,
            4f / 6f to Color.Blue,
            5f / 6f to Color.Magenta,
            1f to Color.Red,
            center = center,
        )
        drawCircle(brush = hueBrush, radius = radius, center = center)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0f)),
                center = center,
                radius = radius * 0.75f,
            ),
            radius = radius,
            center = center,
        )

        val dot = wheel.position(radius, center)
        drawCircle(Color.Black.copy(alpha = 0.55f), 9f, dot)
        drawCircle(Color.White, 6f, dot)
        drawCircle(Color(0xFF14161A), 6f, dot, style = Stroke(1.5f))
    }
}

private fun GradeWheel.position(radius: Float, center: Offset): Offset {
    if (amount == 0f) return center
    val radians = Math.toRadians(hue.toDouble())
    val distance = radius * amount.coerceIn(0f, 1f)
    return Offset(
        center.x + distance * cos(radians).toFloat(),
        center.y + distance * sin(radians).toFloat(),
    )
}

private fun GradeWheel.fromOffset(offset: Offset, size: Float): GradeWheel {
    val center = Offset(size / 2f, size / 2f)
    val dx = offset.x - center.x
    val dy = offset.y - center.y
    val radius = size / 2f
    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
    val strength = (distance / radius).coerceIn(0f, 1f)
    if (strength < 0.02f) return GradeWheel()
    var degrees = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    if (degrees < 0f) degrees += 360f
    return GradeWheel(hue = degrees, amount = strength)
}

@Composable
fun GradingWheel(
    title: String,
    wheel: GradeWheel,
    onChange: (GradeWheel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        ColorWheel(wheel, onChange, Modifier.size(124.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
fun GradingPanel(
    shadows: GradeWheel,
    midtones: GradeWheel,
    highlights: GradeWheel,
    onChange: (Int, GradeWheel) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Three equal wheels in a triangle: midtones on top, shadows and highlights below.
        GradingWheel("Meios-tons", midtones, { onChange(1, it) })
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            GradingWheel("Sombras", shadows, { onChange(0, it) })
            GradingWheel("Altas luzes", highlights, { onChange(2, it) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            androidx.compose.material3.TextButton(onClick = onReset) { Text("Redefinir") }
        }
    }
}