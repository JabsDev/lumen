package com.jabs.lumen.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.jabs.lumen.engine.ImageEngine
import kotlinx.coroutines.launch

@Composable
fun AdjustSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onReset: (() -> Unit)? = null,
) {
    val reset = rememberUpdatedState(onReset)
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (onReset != null) Modifier.pointerInput(Unit) {
                    detectDoubleTap {
                        // The Slider reacts to the very same second tap on its own pass; defer the
                        // reset so it always lands afterwards, or the tap would set the value back.
                        scope.launch { reset.value?.invoke() }
                    }
                } else Modifier
            )
            .padding(vertical = 6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                display(value),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range,
        )
    }
}

/**
 * Fires [onDoubleTap] on two quick taps that land close together.
 *
 * The Slider owns the press events, so this watches them on the initial pass (ancestors see a
 * pointer first) without consuming the first one, which keeps an ordinary drag reaching the
 * Slider. When the *second* press of a pair arrives it is consumed outright: the Slider then never
 * sees that tap, so it cannot move the value back over the reset.
 */
private suspend fun PointerInputScope.detectDoubleTap(onDoubleTap: () -> Unit) {
    val slop = viewConfiguration.touchSlop
    val timeout = viewConfiguration.doubleTapTimeoutMillis
    var lastTime = 0L
    var lastPos = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val isDouble = lastTime != 0L && down.uptimeMillis - lastTime <= timeout &&
            (down.position - lastPos).getDistance() <= slop * 2f
        if (isDouble) {
            lastTime = 0L
            down.consume()
            onDoubleTap()
            // Keep swallowing the rest of this press so nothing below reacts to it.
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break
            }
            return@awaitEachGesture
        }
        // Otherwise remember this press, but only if it ends up being a clean tap.
        var pos = down.position
        var moved = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (moved) {
                    lastTime = 0L
                } else {
                    lastTime = change.uptimeMillis
                    lastPos = change.position
                }
                break
            }
            if ((change.position - pos).getDistance() > slop) moved = true
            pos = change.position
        }
    }
}

/**
 * The picture's tonal distribution: the luminance silhouette with the three colour channels
 * traced over it, the way a raw editor shows it. Tapping the plot drops the colour traces and
 * leaves the plain luminance one, for when only the overall spread matters.
 */
@Composable
fun Histogram(data: ImageEngine.Histograms, modifier: Modifier = Modifier) {
    var channels by rememberSaveable { mutableStateOf(true) }
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x8017191C))
            .clickable { channels = !channels },
    ) {
        Canvas(Modifier.fillMaxSize().padding(3.dp)) {
            val bars = data.luma.size
            val barWidth = size.width / bars
            // Luminance keeps its own scale, so a picture that leans on one channel still shows a
            // full-height silhouette.
            val lumaMax = (data.luma.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
            for (i in 0 until bars) {
                val barHeight = size.height * (data.luma[i] / lumaMax)
                if (barHeight <= 0f) continue
                drawRect(
                    color = Color(0x59BFC6D1),
                    topLeft = Offset(i * barWidth, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                )
            }
            if (!channels) return@Canvas
            // The three channels share one scale, so their heights compare with each other.
            val max = maxOf(
                data.red.maxOrNull() ?: 1,
                data.green.maxOrNull() ?: 1,
                data.blue.maxOrNull() ?: 1,
            ).coerceAtLeast(1).toFloat()
            channelTrace(data.red, max, Color(0xFFE8615E))
            channelTrace(data.green, max, Color(0xFF5FC46A))
            channelTrace(data.blue, max, Color(0xFF5B8FE6))
        }
    }
}

/** One channel traced as a crisp line over the silhouette. */
private fun DrawScope.channelTrace(bins: IntArray, max: Float, color: Color) {
    val path = Path()
    val last = (bins.size - 1).coerceAtLeast(1)
    val step = size.width / last
    for (i in bins.indices) {
        val y = size.height * (1f - (bins[i] / max).coerceIn(0f, 1f))
        if (i == 0) path.moveTo(0f, y) else path.lineTo(i * step, y)
    }
    drawPath(path, color, style = Stroke(width = 1.4.dp.toPx()))
}
