package com.jabs.lumen.engine

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Offline interactive object segmentation: the "Select Objects" of Lightroom.
 *
 * A bundled TFLite model (MediaPipe MagicTouch, Apache-2.0, ~5.9 MB) receives the photo with the
 * tapped point painted into the alpha channel and answers with the object sitting under it. It is
 * not tied to any class — a flower, a sword, a cup or a person all go through the same network,
 * which is exactly what the person-only model could not do.
 *
 * The pipeline mirrors mediapipe/tasks vision interactive_segmenter_legacy, because the model is
 * picky about it. The two traps, both verified against the official implementation:
 *   - the RGB range. In [0, 1] it answers with the tapped object; in [-1, 1] or [0, 255] it
 *     answers "everything" or "nothing", which looks like a broken model but is just a wrong range;
 *   - the tap. It has to be a thin dot (~1 px at model scale) in the alpha channel. A fat dot
 *     turns the answer inside out and returns the background.
 *   So: RGBA with the tap painted into the alpha, stretched to 512x512 (no crop, no letterbox),
 *   float32 in [0, 1] in, sigmoid out, resampled onto the engine's grid.
 *
 * Loading is best-effort. When the model is missing or the runtime refuses to build, [segment]
 * returns null and the caller falls back to the colour-grown selection, so the chip still works.
 */
object ObjectSegmenter {

    private const val ASSET = "magic_touch.tflite"

    /** The model's fixed input and output side. */
    private const val SIZE = 512

    /** RGBA: the photo plus the tap in the alpha channel. */
    private const val CHANNELS = 4

    private var interpreter: Interpreter? = null
    private var initTried = false

    // A 512x512x4 float input is 4 MB, so the buffers are allocated once and refilled per call.
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null

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
                Interpreter(buffer, Interpreter.Options().apply { setNumThreads(4) })
            }.getOrNull()
        }
    }

    /** True when the model loaded and the object chip can rely on it. */
    val available: Boolean get() = interpreter != null

    /**
     * Object probability for every cell of a [gw]x[gh] grid over the [imgW]x[imgH] ARGB [pixels]:
     * the union of the objects under each [adds] point, minus whatever sits under each [subs]
     * point. Points are normalised (0..1). Returns null when the model is unusable, so the caller
     * can fall back to the classical selection.
     */
    fun segment(
        pixels: IntArray, imgW: Int, imgH: Int, gw: Int, gh: Int,
        adds: List<Pair<Float, Float>>, subs: List<Pair<Float, Float>>,
    ): FloatArray? {
        val net = interpreter ?: return null
        if (imgW < 1 || imgH < 1 || gw < 1 || gh < 1 || adds.isEmpty()) return null
        return synchronized(lock) {
            try {
                val grid = FloatArray(gw * gh)
                for (point in adds) {
                    val current = infer(net, pixels, imgW, imgH, gw, gh, point.first, point.second)
                    for (i in grid.indices) if (current[i] > grid[i]) grid[i] = current[i]
                }
                for (point in subs) {
                    val current = infer(net, pixels, imgW, imgH, gw, gh, point.first, point.second)
                    for (i in grid.indices) if (current[i] > 0.5f) grid[i] = 0f
                }
                grid
            } catch (t: Throwable) {
                null
            }
        }
    }

    private fun infer(
        net: Interpreter, pixels: IntArray, imgW: Int, imgH: Int, gw: Int, gh: Int,
        tapX: Float, tapY: Float,
    ): FloatArray {
        val input = inputBuffer ?: ByteBuffer
            .allocateDirect(SIZE * SIZE * CHANNELS * 4)
            .order(ByteOrder.nativeOrder())
            .also { inputBuffer = it }
        input.clear()

        // The tap in model coordinates, and the dot's radius there. MediaPipe renders the point in
        // source pixels with thickness max(w/size, h/size, 1), which lands on ~1 px once stretched
        // to 512; small photos get a slightly fatter dot.
        val dotX = tapX * SIZE
        val dotY = tapY * SIZE
        val radius = 0.5f * max(1f, SIZE / max(imgW, imgH).toFloat())

        val stepX = imgW / SIZE.toFloat()
        val stepY = imgH / SIZE.toFloat()
        for (y in 0 until SIZE) {
            val sy = (y + 0.5f) * stepY - 0.5f
            val row = y * SIZE
            for (x in 0 until SIZE) {
                val sx = (x + 0.5f) * stepX - 0.5f
                val c = sampleBilinear(pixels, imgW, imgH, sx, sy)
                input.putFloat(((c shr 16) and 0xFF) / 255f)
                input.putFloat(((c shr 8) and 0xFF) / 255f)
                input.putFloat((c and 0xFF) / 255f)
                // The alpha carries the tap: a soft-edged disc, so a sub-pixel tap is not lost.
                val distance = hypot(x + 0.5f - dotX, y + 0.5f - dotY)
                input.putFloat((radius + 0.5f - distance).coerceIn(0f, 1f))
            }
        }
        input.rewind()

        val output = outputBuffer ?: ByteBuffer
            .allocateDirect(SIZE * SIZE * 4)
            .order(ByteOrder.nativeOrder())
            .also { outputBuffer = it }
        output.clear()
        net.run(input, output)

        // The 512x512 answer is coarser than the grid it has to fill, so this upsamples with
        // bilinear (a box average would leave unwritten holes).
        val grid = FloatArray(gw * gh)
        val cellX = SIZE / gw.toFloat()
        val cellY = SIZE / gh.toFloat()
        for (gy in 0 until gh) {
            val sy = ((gy + 0.5f) * cellY - 0.5f).coerceIn(0f, (SIZE - 1).toFloat())
            val y0 = sy.toInt()
            val y1 = min(y0 + 1, SIZE - 1)
            val fy = sy - y0
            for (gx in 0 until gw) {
                val sx = ((gx + 0.5f) * cellX - 0.5f).coerceIn(0f, (SIZE - 1).toFloat())
                val x0 = sx.toInt()
                val x1 = min(x0 + 1, SIZE - 1)
                val fx = sx - x0
                val top = output.getFloat(((y0 * SIZE + x0) shl 2)) +
                    (output.getFloat(((y0 * SIZE + x1) shl 2)) - output.getFloat(((y0 * SIZE + x0) shl 2))) * fx
                val bottom = output.getFloat(((y1 * SIZE + x0) shl 2)) +
                    (output.getFloat(((y1 * SIZE + x1) shl 2)) - output.getFloat(((y1 * SIZE + x0) shl 2))) * fx
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
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
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
