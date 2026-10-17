package com.jabs.lumen.engine

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline person/subject segmentation. A small bundled TFLite model (MediaPipe Selfie Segmenter,
 * Apache-2.0, ~250 KB) turns a frame into a per-pixel person probability, which is what drives the
 * "Pessoa" mask. Everything runs on-device: no network, no model download.
 *
 * Loading is best-effort. If the model is missing or the runtime refuses to build, [segment]
 * returns null and the caller falls back to the classical tap-to-grow selection, so a subject mask
 * still does something useful instead of failing.
 */
object Segmenter {

    private const val ASSET = "subject.tflite"

    /** The model's fixed input/output side. */
    private const val SIZE = 256

    /** RGB input, float32 normalised to [-1, 1]; output is one float32 channel in [0, 1]. */
    private const val CHANNELS = 3

    private var interpreter: Interpreter? = null
    private var initTried = false

    // The TFLite interpreter is not thread-safe; every call runs under this lock. Masks rasterise
    // on the render pool, so more than one thread can reach here at once.
    private val lock = Any()

    /** Loads the model once. Safe to call from any thread; later calls are no-ops. */
    fun init(context: Context) {
        synchronized(lock) {
            if (initTried) return
            initTried = true
            interpreter = runCatching {
                val bytes = context.assets.open(ASSET).use { it.readBytes() }
                val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                buffer.put(bytes).rewind()
                Interpreter(buffer, Interpreter.Options().apply { setNumThreads(2) })
            }.getOrNull()
        }
    }

    val available: Boolean get() = interpreter != null

    /**
     * Person probability for every cell of a [gw]x[gh] grid over the [imgW]x[imgH] ARGB [pixels],
     * or null when the model is unavailable. The frame is bilinearly resized to the model's 256x256
     * square (the network is fully convolutional, so a stretched input is fine); the 256x256 output
     * is resampled (bilinear) onto the requested grid.
     */
    fun segment(pixels: IntArray, imgW: Int, imgH: Int, gw: Int, gh: Int): FloatArray? {
        val net = interpreter ?: return null
        if (imgW < 1 || imgH < 1 || gw < 1 || gh < 1) return null
        return synchronized(lock) {
            runCatching { infer(net, pixels, imgW, imgH, gw, gh) }.getOrNull()
        }
    }

    private fun infer(
        net: Interpreter, pixels: IntArray, imgW: Int, imgH: Int, gw: Int, gh: Int,
    ): FloatArray {
        val input = ByteBuffer.allocateDirect(SIZE * SIZE * CHANNELS * 4).order(ByteOrder.nativeOrder())
        val step = 1f / SIZE
        for (y in 0 until SIZE) {
            val sy = (y + 0.5f) * imgH * step - 0.5f
            for (x in 0 until SIZE) {
                val sx = (x + 0.5f) * imgW * step - 0.5f
                val c = sampleBilinear(pixels, imgW, imgH, sx, sy)
                input.putFloat((((c shr 16) and 0xFF) / 127.5f) - 1f)
                input.putFloat((((c shr 8) and 0xFF) / 127.5f) - 1f)
                input.putFloat(((c and 0xFF) / 127.5f) - 1f)
            }
        }
        input.rewind()

        val out = Array(1) { Array(SIZE) { Array(SIZE) { FloatArray(1) } } }
        net.run(input, out)

        // Resample the 256x256 probability onto the grid the engine speaks. The grid is finer than
        // the model output, so this upsamples: a box-average would leave unwritten holes.
        val grid = FloatArray(gw * gh)
        val stepX = SIZE / gw.toFloat()
        val stepY = SIZE / gh.toFloat()
        for (gy in 0 until gh) {
            val sy = ((gy + 0.5f) * stepY - 0.5f).coerceIn(0f, (SIZE - 1).toFloat())
            val y0 = sy.toInt()
            val y1 = (y0 + 1).coerceAtMost(SIZE - 1)
            val fy = sy - y0
            val row0 = out[0][y0]
            val row1 = out[0][y1]
            for (gx in 0 until gw) {
                val sx = ((gx + 0.5f) * stepX - 0.5f).coerceIn(0f, (SIZE - 1).toFloat())
                val x0 = sx.toInt()
                val x1 = (x0 + 1).coerceAtMost(SIZE - 1)
                val fx = sx - x0
                val top = row0[x0][0] + (row0[x1][0] - row0[x0][0]) * fx
                val bottom = row1[x0][0] + (row1[x1][0] - row1[x0][0]) * fx
                grid[gy * gw + gx] = top + (bottom - top) * fy
            }
        }
        return grid
    }

    /** Bilinear ARGB sample at a fractional source position, edges clamped. */
    private fun sampleBilinear(px: IntArray, w: Int, h: Int, sx: Float, sy: Float): Int {
        val x = sx.coerceIn(0f, (w - 1).toFloat())
        val y = sy.coerceIn(0f, (h - 1).toFloat())
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = (x0 + 1).coerceAtMost(w - 1)
        val y1 = (y0 + 1).coerceAtMost(h - 1)
        val fx = x - x0
        val fy = y - y0
        var r = 0f
        var g = 0f
        var b = 0f
        for (k in 0 until 4) {
            val xx = if (k and 1 == 0) x0 else x1
            val yy = if (k and 2 == 0) y0 else y1
            val weight = (if (xx == x0) 1f - fx else fx) * (if (yy == y0) 1f - fy else fy)
            val c = px[yy * w + xx]
            r += ((c shr 16) and 0xFF) * weight
            g += ((c shr 8) and 0xFF) * weight
            b += (c and 0xFF) * weight
        }
        return (0xFF shl 24) or
            (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or
            b.toInt().coerceIn(0, 255)
    }
}
