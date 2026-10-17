package com.jabs.lumen.engine

import android.os.Build
import kotlin.math.roundToInt

/**
 * Picks the working resolution for the editor. A weak phone gets a smaller preview while a
 * slider is being dragged, and the result is re-measured at runtime so the app adapts to the
 * device it is actually running on.
 */
object DeviceProfile {

    private const val MIN_DRAG = 520
    private const val MAX_DRAG = 900

    fun initialDragSize(): Int {
        val heapMb = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()
        val cores = Runtime.getRuntime().availableProcessors()
        val baseline = when {
            heapMb < 512 || cores <= 2 -> 560
            heapMb < 1024 -> 640
            cores <= 4 -> 720
            Build.VERSION.SDK_INT < 24 -> 680
            else -> 760
        }
        return baseline.coerceIn(MIN_DRAG, MAX_DRAG)
    }

    /** Full-size preview used when nothing is moving. */
    fun initialPreviewSize(dragSize: Int): Int = (dragSize * 1.7f).roundToInt().coerceIn(880, 1400)

    /**
     * Keeps the preview inside the frame budget: on a slow phone the working copy is shrunk while
     * a slider moves and grown back as soon as the renders allow it. The settled preview always
     * comes back at full size, so the trade only ever applies to a moving finger.
     */
    fun tune(dragSize: Int, lastRenderMs: Long, initial: Int): Int? {
        if (lastRenderMs > FRAME_BUDGET_MS && dragSize > MIN_DRAG) {
            return (dragSize - SHRINK_STEP).coerceAtLeast(MIN_DRAG)
        }
        if (lastRenderMs < FRAME_BUDGET_MS / 2 && dragSize < initial) {
            return (dragSize + GROW_STEP).coerceAtMost(initial)
        }
        return null
    }

    /** ~60 Hz with headroom for the UI thread while the render pool is busy. */
    private const val FRAME_BUDGET_MS = 34L
    private const val SHRINK_STEP = 90
    private const val GROW_STEP = 60
}