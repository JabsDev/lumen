package com.jabs.lumen.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
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
            .padding(vertical = 2.dp)
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

@Composable
fun Histogram(bins: IntArray, modifier: Modifier = Modifier) {
    val maxBin = (bins.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
    Canvas(
        modifier
            .background(Color(0x33000000), RoundedCornerShape(8.dp))
    ) {
        val binWidth = size.width / bins.size
        bins.forEachIndexed { i, count ->
            val barHeight = size.height * (count / maxBin)
            drawRect(
                color = Color(0xFFBFC6D1),
                topLeft = Offset(i * binWidth, size.height - barHeight),
                size = Size(binWidth, barHeight),
            )
        }
    }
}
