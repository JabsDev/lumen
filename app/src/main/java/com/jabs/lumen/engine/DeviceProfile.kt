package com.jabs.lumen.engine

import android.os.Build
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Picks the working resolution for the editor. A weak phone gets a smaller preview while a
 * slider is being dragged, and the result is re-measured at runtime so the app adapts to the
 * device it is actually running on.
 */
object DeviceProfile {

    private const val MIN_DRAG = 520
    private const val MAX_DRAG = 900

    /**
     * Conservative peak estimate per pixel of the pipeline: the source, the output and a handful
     * of working buffers (`IntArray` pixels, `FloatArray` luminance, blur temporaries) all live at
     * once during `applyAll`. Used to size the settled preview and the 1:1 detail so a normal heap
     * never has to guess.
     */
    const val BYTES_PER_PIXEL = 8

    /** Never settle below this length: below it the "screen" level would look like a drag copy. */
    private const val MIN_SCREEN = 1024
    private const val MAX_SCREEN = 3840

    /** The 1:1 detail never needs more than this, no matter how big the screen claims to be. */
    private const val MAX_DETAIL = 4096

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

    /** Full-size preview used when nothing is moving. Kept for the drag-copy sizing callers. */
    fun initialPreviewSize(dragSize: Int): Int = (dragSize * 1.7f).roundToInt().coerceIn(880, 1400)

    /**
     * Length of the settled ("screen") preview: covers the display with slack so a moderate pinch
     * stays sharp without a re-render, capped by a share of the heap the pipeline can actually
     * afford. `screenLongSide` comes from `resources.displayMetrics`.
     */
    fun screenPreviewSize(screenLongSide: Int, heapMb: Int): Int {
        val desired = (screenLongSide * 1.5f).roundToInt() // still sharp up to ~1.5x zoom
        val budgetSide = sqrt((heapMb.toLong() * 1024L * 1024L / BYTES_PER_PIXEL) / 3.0).toInt()
        return desired.coerceAtMost(budgetSide).coerceIn(MIN_SCREEN, MAX_SCREEN)
    }

    /**
     * Largest native crop the 1:1 detail may build. The detail is a viewport cut, so this is only
     * a safety net: a huge phone screen asking for a big region still stays inside the heap.
     */
    fun detailCeiling(heapMb: Int): Int {
        val budgetSide = sqrt((heapMb.toLong() * 1024L * 1024L / BYTES_PER_PIXEL) / 2.0).toInt()
        return budgetSide.coerceIn(1024, MAX_DETAIL)
    }

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