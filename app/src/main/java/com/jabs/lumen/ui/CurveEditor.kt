package com.jabs.lumen.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.jabs.lumen.model.CurvePoint
import com.jabs.lumen.model.ToneCurve

private val GRID = Color(0xFF343A42)
private const val GRAB_RADIUS = 0.07f

/** Tone curve editor: press near a point and drag to move it, press empty space for a new one. */
@Composable
fun CurveEditor(
    curve: ToneCurve,
    onChange: (ToneCurve) -> Unit,
    onCommit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    // The gesture is keyed on Unit so it survives the recompositions each drag move triggers; the
    // latest curve and callbacks are read through these states instead of restarting the gesture.
    val current = rememberUpdatedState(curve)
    val change = rememberUpdatedState(onChange)
    val commit = rememberUpdatedState(onCommit)
    Canvas(
        modifier
            .background(Color(0xFF14161A))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    change.value(current.value.editAt(down.position, size.width.toFloat(), size.height.toFloat(), add = true))
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val moved = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (moved.changedToUpIgnoreConsumed() || !moved.pressed) break
                        change.value(current.value.editAt(moved.position, size.width.toFloat(), size.height.toFloat(), add = false))
                        moved.consume()
                    }
                    commit.value()
                }
            },
    ) {
        drawGrid()
        drawCurve(curve.points, accent)
    }
}

private fun DrawScope.drawGrid() {
    for (i in 1..3) {
        val f = i / 4f
        drawLine(GRID, Offset(size.width * f, 0f), Offset(size.width * f, size.height), 1f)
        drawLine(GRID, Offset(0f, size.height * f), Offset(size.width, size.height * f), 1f)
    }
}

private fun DrawScope.drawCurve(points: List<CurvePoint>, color: Color) {
    val sorted = points.sortedBy { it.x }
    val path = Path()
    val steps = 120
    for (i in 0..steps) {
        val x = i / steps.toFloat()
        val y = interpolate(sorted, x)
        val point = Offset(x * size.width, (1f - y) * size.height)
        if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    drawPath(path, color, style = Stroke(width = 2.5f))
    sorted.forEach { p ->
        val center = Offset(p.x * size.width, (1f - p.y) * size.height)
        drawCircle(Color(0xFF14161A), 7f, center)
        drawCircle(color, 5f, center, style = Stroke(width = 2f))
    }
}

/** Straight-line sample used only for drawing; the render uses a smooth spline. */
private fun interpolate(points: List<CurvePoint>, x: Float): Float {
    if (points.isEmpty()) return x
    if (x <= points.first().x) return points.first().y
    if (x >= points.last().x) return points.last().y
    for (i in 0 until points.lastIndex) {
        val a = points[i]
        val b = points[i + 1]
        if (x in a.x..b.x) {
            val span = b.x - a.x
            val t = if (span <= 0f) 0f else (x - a.x) / span
            return a.y + (b.y - a.y) * t
        }
    }
    return x
}

private fun ToneCurve.editAt(offset: Offset, width: Float, height: Float, add: Boolean): ToneCurve {
    val x = (offset.x / width).coerceIn(0f, 1f)
    val y = (1f - offset.y / height).coerceIn(0f, 1f)
    val sorted = points.sortedBy { it.x }
    val target = CurvePoint(x, y)

    var index = sorted.indices.minByOrNull { distance(sorted[it], target) } ?: return this
    val near = distance(sorted[index], target) <= GRAB_RADIUS

    if (!near && !add) return this
    if (near && index == 0) return copy(points = sorted.replaceAt(0, target.copy(x = 0f)))
    if (near && index == sorted.lastIndex) return copy(points = sorted.replaceAt(index, target.copy(x = 1f)))

    if (!near) {
        val updated = sorted.toMutableList()
        updated.add(index, target)
        return copy(points = updated.sortedBy { it.x })
    }

    if (sorted.size >= 12) return this // keep the curve light enough to render fast
    val updated = sorted.toMutableList()
    updated[index] = target
    val ordered = updated.sortedBy { it.x }
    if (ordered.zipWithNext().any { (a, b) -> b.x - a.x < 0.004f }) return this
    return copy(points = ordered)
}

private fun distance(a: CurvePoint, b: CurvePoint): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

private fun List<CurvePoint>.replaceAt(index: Int, value: CurvePoint): List<CurvePoint> =
    toMutableList().also { it[index] = value }

/** Channel selector above the curve: RGB plus the three colour channels. */
@Composable
fun CurveChannelRow(
    selected: Int,
    onSelect: (Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = listOf(Color(0xFFD5D8DD), Color(0xFFE05555), Color(0xFF43B05A), Color(0xFF4E86E8))
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        colors.forEachIndexed { index, color ->
            val active = selected == index
            Box(
                Modifier
                    .size(if (active) 20.dp else 16.dp)
                    .border(if (active) 2.dp else 1.dp, color, CircleShape)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                if (index == 0) {
                    Box(Modifier.size(7.dp).background(color, CircleShape))
                }
            }
        }
        Box(Modifier.weight(1f))
        TextButton(onClick = onReset) { Text("Redefinir") }
    }
}