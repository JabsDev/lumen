package com.jabs.lumen.engine

import android.graphics.Bitmap
import com.jabs.lumen.model.Mask
import com.jabs.lumen.model.MaskPoint
import com.jabs.lumen.model.MaskType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns a [Mask] into a weight grid (0 = no effect, 1 = full effect) and samples it during the
 * render. Everything here is original code: analytic gradients for the geometric masks, a sky
 * detector that weighs colour, brightness and smoothness, a Lab tap-to-grow region for
 * object/background, and the offline segmentation model for people (falling back to the same
 * tap-to-grow when the model is unavailable). The content masks are finished with a guided filter
 * so their edges land on the picture rather than on the grid.
 */
object MaskEngine {

    private const val GRID_LONG = 384

    class Grid(val w: Int, val h: Int, val data: FloatArray)

    fun gridSize(imgW: Int, imgH: Int): Pair<Int, Int> {
        val scale = GRID_LONG / maxOf(imgW, imgH).toFloat()
        return ((imgW * scale).toInt().coerceAtLeast(8)) to ((imgH * scale).toInt().coerceAtLeast(8))
    }

    /** Builds the weight grid for [mask] over an image of [imgW]x[imgH] described by [pixels]. */
    fun rasterize(mask: Mask, pixels: IntArray, imgW: Int, imgH: Int): Grid {
        val (gw, gh) = gridSize(imgW, imgH)
        val data = FloatArray(gw * gh)
        val aspect = imgW.toFloat() / imgH
        when (mask.type) {
            MaskType.LINEAR -> rasterLinear(data, gw, gh, aspect, mask)
            MaskType.RADIAL -> rasterRadial(data, gw, gh, aspect, mask)
            MaskType.BRUSH -> rasterBrush(data, gw, gh, mask)
            MaskType.SKY -> rasterSky(data, gw, gh, pixels, imgW, imgH, mask)
            MaskType.SELECT -> rasterSelect(data, gw, gh, pixels, imgW, imgH, mask)
            MaskType.SUBJECT -> rasterSubject(data, gw, gh, pixels, imgW, imgH, mask)
        }
        if (mask.invert) {
            for (i in data.indices) data[i] = 1f - data[i]
        }
        if (mask.opacity < 1f) {
            for (i in data.indices) data[i] *= mask.opacity
        }
        return Grid(gw, gh, data)
    }

    fun sample(grid: Grid, x01: Float, y01: Float): Float {
        val gx = (x01 * (grid.w - 1)).coerceIn(0f, (grid.w - 1).toFloat())
        val gy = (y01 * (grid.h - 1)).coerceIn(0f, (grid.h - 1).toFloat())
        val x0 = gx.toInt()
        val y0 = gy.toInt()
        val x1 = minOf(x0 + 1, grid.w - 1)
        val y1 = minOf(y0 + 1, grid.h - 1)
        val fx = gx - x0
        val fy = gy - y0
        val d = grid.data
        val top = d[y0 * grid.w + x0] + (d[y0 * grid.w + x1] - d[y0 * grid.w + x0]) * fx
        val bottom = d[y1 * grid.w + x0] + (d[y1 * grid.w + x1] - d[y1 * grid.w + x0]) * fx
        return top + (bottom - top) * fy
    }

    /** Red overlay bitmap at grid resolution for the editor UI; caller scales it to the preview. */
    fun overlayBitmap(grid: Grid): Bitmap {
        val px = IntArray(grid.w * grid.h)
        for (i in px.indices) {
            val a = (grid.data[i].coerceIn(0f, 1f) * 130f + 0.5f).toInt()
            px[i] = (a shl 24) or (0xFF shl 16) or (0x38 shl 8) or 0x38
        }
        val out = Bitmap.createBitmap(grid.w, grid.h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, grid.w, 0, 0, grid.w, grid.h)
        return out
    }

    private fun rasterLinear(data: FloatArray, w: Int, h: Int, aspect: Float, mask: Mask) {
        val a = Math.toRadians(mask.angle.toDouble())
        val nx = cos(a).toFloat()
        val ny = sin(a).toFloat()
        val halfDiag = sqrt(0.25f + 0.25f * aspect * aspect)
        val fw = 0.015f + mask.feather * 0.22f
        var i = 0
        for (gy in 0 until h) {
            val y = (gy + 0.5f) / h
            for (gx in 0 until w) {
                val x = (gx + 0.5f) / w
                val t = ((x - 0.5f) * aspect * nx + (y - 0.5f) * ny) / halfDiag + 0.5f
                data[i++] = smoothstep((t - mask.position) / fw + 0.5f)
            }
        }
    }

    private fun rasterRadial(data: FloatArray, w: Int, h: Int, aspect: Float, mask: Mask) {
        val a = Math.toRadians(mask.rotation.toDouble())
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        val fw = 0.03f + mask.feather * 0.55f
        var i = 0
        for (gy in 0 until h) {
            val y = (gy + 0.5f) / h
            for (gx in 0 until w) {
                val x = (gx + 0.5f) / w
                val dx = (x - mask.cx) * aspect
                val dy = y - mask.cy
                val ex = (dx * ca + dy * sa) / maxOf(mask.rx * aspect, 1e-4f)
                val ey = (-dx * sa + dy * ca) / maxOf(mask.ry, 1e-4f)
                val d = sqrt(ex * ex + ey * ey)
                data[i++] = smoothstep((1f - d) / fw + 0.5f)
            }
        }
    }

    private fun rasterBrush(data: FloatArray, w: Int, h: Int, mask: Mask) {
        for (stroke in mask.strokes) {
            val r = maxOf(stroke.size * minOf(w, h), 1f)
            val pts = stroke.points
            if (pts.isEmpty()) continue
            if (pts.size == 1) {
                stamp(data, w, h, pts[0].x * w, pts[0].y * h, r, !stroke.erase)
                continue
            }
            for (s in 0 until pts.size - 1) {
                val ax = pts[s].x * w
                val ay = pts[s].y * h
                val bx = pts[s + 1].x * w
                val by = pts[s + 1].y * h
                val dist = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
                val steps = maxOf(1, (dist / (r * 0.5f)).toInt())
                for (k in 0..steps) {
                    val t = k / steps.toFloat()
                    stamp(data, w, h, ax + (bx - ax) * t, ay + (by - ay) * t, r, !stroke.erase)
                }
            }
        }
        val blurR = (mask.feather * 6f).toInt()
        if (blurR > 0) boxBlurInPlace(data, w, h, blurR)
    }

    private fun stamp(data: FloatArray, w: Int, h: Int, cx: Float, cy: Float, r: Float, on: Boolean) {
        val x0 = maxOf(0, (cx - r).toInt())
        val x1 = minOf(w - 1, (cx + r).toInt())
        val y0 = maxOf(0, (cy - r).toInt())
        val y1 = minOf(h - 1, (cy + r).toInt())
        val v = if (on) 1f else 0f
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy > r * r) continue
                // Soft disc edge so strokes never look stamped.
                val edge = sqrt((dx * dx + dy * dy)) / r
                val a = (1f - edge * edge).coerceIn(0f, 1f)
                val idx = y * w + x
                data[idx] = data[idx] * (1f - a) + v * a
            }
        }
    }

    /**
     * Sky cue stack: blue-dominant pixels score high, but so do bright, low-saturation, low-detail
     * tops (overcast, haze). Everything is gated to the upper part of the frame and then snapped to
     * the image's own edges, so the horizon lands on the real boundary instead of a grid seam.
     */
    private fun rasterSky(
        data: FloatArray, w: Int, h: Int,
        pixels: IntArray, imgW: Int, imgH: Int, mask: Mask,
    ) {
        val avg = cellColors(pixels, imgW, imgH, w, h)
        val lab = labGrid(avg, w, h)
        val lNorm = FloatArray(w * h) { lab[it * 3] / 100f }
        val smooth = boxBlur(lNorm, w, h, 3)
        val detail = FloatArray(w * h) { abs(lNorm[it] - smooth[it]) }
        var i = 0
        for (gy in 0 until h) {
            val y = (gy + 0.5f) / h
            // Sky lives up top; the bias fades below the middle so foreground blues survive.
            val vertical = ((0.65f - y) / 0.55f).coerceIn(0f, 1f) * 0.85f + 0.15f
            for (gx in 0 until w) {
                val c = avg[i]
                val r = ((c shr 16) and 0xFF) / 255f
                val g = ((c shr 8) and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                val maxc = maxOf(r, g, b)
                val minc = minOf(r, g, b)
                val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val blueness = ((b - maxOf(r, g) * 1.02f) / 0.25f).coerceIn(0f, 1f)
                val bright = ((lum - 0.55f) / 0.35f).coerceIn(0f, 1f)
                val lowSat = 1f - ((maxc - minc) / 0.35f).coerceIn(0f, 1f)
                val flat = 1f - (detail[i] / 0.12f).coerceIn(0f, 1f)
                val score = blueness * 0.70f + bright * lowSat * flat * 0.55f
                data[i] = (score * vertical * 1.5f).coerceIn(0f, 1f)
                i++
            }
        }
        guidedRefine(data, lNorm, w, h, mask.feather)
    }

    /**
     * Tap-to-grow in Lab, best-first. Positive points add, subtract points carve away; the result is
     * the union of the added regions minus the union of the subtracted ones. Tolerance opens two
     * gates: how far one step may differ locally (loose, so soft shading is followed) and how far a
     * region may drift from its tapped colour (a cap, so it cannot wander off).
     */
    private fun rasterSelect(
        data: FloatArray, w: Int, h: Int,
        pixels: IntArray, imgW: Int, imgH: Int, mask: Mask,
    ) {
        val avg = cellColors(pixels, imgW, imgH, w, h)
        val lab = labGrid(avg, w, h)
        val n = w * h
        val points = mask.points.ifEmpty { listOf(MaskPoint(mask.seedX, mask.seedY)) }
        val added = BooleanArray(n)
        val removed = BooleanArray(n)
        val region = BooleanArray(n)
        for (point in points) {
            region.fill(false)
            growRegion(region, lab, w, h, point.x, point.y, mask.tolerance)
            val target = if (point.subtract) removed else added
            for (i in 0 until n) if (region[i]) target[i] = true
        }
        for (i in 0 until n) data[i] = if (added[i] && !removed[i]) 1f else 0f
        guidedRefine(data, FloatArray(n) { lab[it * 3] / 100f }, w, h, mask.feather)
    }

    /**
     * Person mask from the offline segmentation model. The neural probabilities set the base; the
     * tapped points then refine them the way they refine the object mask — positive pins add a
     * colour-grown patch the model may have missed, negative pins carve one away. If the model is
     * unavailable the classical tap-to-grow selection takes over, so the chip always does something.
     */
    private fun rasterSubject(
        data: FloatArray, w: Int, h: Int,
        pixels: IntArray, imgW: Int, imgH: Int, mask: Mask,
    ) {
        val probs = Segmenter.segment(pixels, imgW, imgH, w, h)
        if (probs == null) {
            rasterSelect(data, w, h, pixels, imgW, imgH, mask)
            return
        }
        val n = w * h
        System.arraycopy(probs, 0, data, 0, n)
        val lab = labGrid(cellColors(pixels, imgW, imgH, w, h), w, h)
        if (mask.points.isNotEmpty()) {
            val region = BooleanArray(n)
            for (point in mask.points) {
                region.fill(false)
                growRegion(region, lab, w, h, point.x, point.y, mask.tolerance)
                val v = if (point.subtract) 0f else 1f
                for (i in 0 until n) if (region[i]) data[i] = v
            }
        }
        guidedRefine(data, FloatArray(n) { lab[it * 3] / 100f }, w, h, mask.feather)
    }

    /** Best-first flood from one tapped point, bounded by the local and global tolerance gates. */
    private fun growRegion(
        out: BooleanArray, lab: FloatArray, w: Int, h: Int,
        seedX: Float, seedY: Float, tolerance: Float,
    ) {
        val seed = seedLab(lab, w, h, seedX, seedY)
        val lumaWeight = 0.70f
        val localTol = 2.5f + tolerance * 22f
        val globalTol = 10f + tolerance * 150f
        val n = w * h
        val seedDist = FloatArray(n) { i ->
            val dl = (lab[i * 3] - seed[0]) * lumaWeight
            val da = lab[i * 3 + 1] - seed[1]
            val db = lab[i * 3 + 2] - seed[2]
            sqrt(dl * dl + da * da + db * db)
        }
        val sx = (seedX * w).toInt().coerceIn(0, w - 1)
        val sy = (seedY * h).toInt().coerceIn(0, h - 1)
        val start = sy * w + sx
        val best = FloatArray(n) { Float.MAX_VALUE }
        val chosen = BooleanArray(n)
        val heap = PairHeap(n)
        best[start] = 0f
        heap.add(0f, start)
        while (!heap.isEmpty()) {
            val d = heap.poll()
            val idx = heap.lastIndex
            if (chosen[idx] || d > best[idx]) continue
            if (idx != start && (d > localTol || seedDist[idx] > globalTol)) continue
            chosen[idx] = true
            out[idx] = true
            val x = idx % w
            val y = idx / w
            if (x > 0) relax(heap, best, chosen, lab, lumaWeight, idx - 1, idx)
            if (x < w - 1) relax(heap, best, chosen, lab, lumaWeight, idx + 1, idx)
            if (y > 0) relax(heap, best, chosen, lab, lumaWeight, idx - w, idx)
            if (y < h - 1) relax(heap, best, chosen, lab, lumaWeight, idx + w, idx)
        }
    }

    private fun relax(
        heap: PairHeap, best: FloatArray, chosen: BooleanArray,
        lab: FloatArray, lumaWeight: Float, next: Int, from: Int,
    ) {
        if (chosen[next]) return
        val dl = (lab[next * 3] - lab[from * 3]) * lumaWeight
        val da = lab[next * 3 + 1] - lab[from * 3 + 1]
        val db = lab[next * 3 + 2] - lab[from * 3 + 2]
        val d = sqrt(dl * dl + da * da + db * db)
        if (d < best[next]) {
            best[next] = d
            heap.add(d, next)
        }
    }

    /** Binary min-heap of (key, cell index), kept in plain arrays to avoid boxing on the hot path. */
    private class PairHeap(capacity: Int) {
        private var keys = FloatArray(capacity.coerceAtLeast(16))
        private var cells = IntArray(capacity.coerceAtLeast(16))
        private var size = 0

        var lastIndex = 0
            private set

        fun isEmpty(): Boolean = size == 0

        fun add(key: Float, index: Int) {
            if (size == keys.size) {
                keys = keys.copyOf(size * 2)
                cells = cells.copyOf(size * 2)
            }
            var i = size++
            keys[i] = key
            cells[i] = index
            while (i > 0) {
                val parent = (i - 1) / 2
                if (keys[parent] <= keys[i]) break
                swap(parent, i)
                i = parent
            }
        }

        /** Removes the smallest key and returns it; the matching cell lands in [lastIndex]. */
        fun poll(): Float {
            val key = keys[0]
            lastIndex = cells[0]
            size--
            if (size > 0) {
                keys[0] = keys[size]
                cells[0] = cells[size]
                var i = 0
                while (true) {
                    val left = 2 * i + 1
                    if (left >= size) break
                    val right = left + 1
                    val smaller = if (right < size && keys[right] < keys[left]) right else left
                    if (keys[i] <= keys[smaller]) break
                    swap(i, smaller)
                    i = smaller
                }
            }
            return key
        }

        private fun swap(a: Int, b: Int) {
            val k = keys[a]
            keys[a] = keys[b]
            keys[b] = k
            val c = cells[a]
            cells[a] = cells[b]
            cells[b] = c
        }
    }

    /** Average source colour per grid cell, so content masks work without extra bitmaps. */
    private fun cellColors(pixels: IntArray, imgW: Int, imgH: Int, gw: Int, gh: Int): IntArray {
        val out = IntArray(gw * gh)
        val strideY = maxOf(1, imgH / (gh * 2))
        val strideX = maxOf(1, imgW / (gw * 2))
        for (gy in 0 until gh) {
            val y0 = gy * imgH / gh
            val y1 = minOf(imgH - 1, (gy + 1) * imgH / gh)
            for (gx in 0 until gw) {
                val x0 = gx * imgW / gw
                val x1 = minOf(imgW - 1, (gx + 1) * imgW / gw)
                var r = 0L
                var g = 0L
                var b = 0L
                var n = 0L
                var y = y0
                while (y <= y1) {
                    var x = x0
                    while (x <= x1) {
                        val c = pixels[y * imgW + x]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                        n++
                        x += strideX
                    }
                    y += strideY
                }
                if (n == 0L) n = 1
                out[gy * gw + gx] = (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
            }
        }
        return out
    }

    /** Perceptual (CIE L*a*b*) colour of every grid cell, used by the content masks. */
    private fun labGrid(avg: IntArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h * 3)
        val tmp = FloatArray(3)
        for (i in 0 until w * h) {
            val c = avg[i]
            rgbToLab(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, tmp)
            out[i * 3] = tmp[0]
            out[i * 3 + 1] = tmp[1]
            out[i * 3 + 2] = tmp[2]
        }
        return out
    }

    /** Average Lab of a small patch around the tap, steadier than reading a single cell. */
    private fun seedLab(lab: FloatArray, w: Int, h: Int, sx: Float, sy: Float): FloatArray {
        val cx = (sx * (w - 1)).toInt().coerceIn(0, w - 1)
        val cy = (sy * (h - 1)).toInt().coerceIn(0, h - 1)
        var l = 0f
        var a = 0f
        var b = 0f
        var n = 0
        for (y in (cy - 1).coerceAtLeast(0)..(cy + 1).coerceAtMost(h - 1)) {
            for (x in (cx - 1).coerceAtLeast(0)..(cx + 1).coerceAtMost(w - 1)) {
                val i = y * w + x
                l += lab[i * 3]
                a += lab[i * 3 + 1]
                b += lab[i * 3 + 2]
                n++
            }
        }
        return floatArrayOf(l / n, a / n, b / n)
    }

    private fun rgbToLab(r: Float, g: Float, b: Float, out: FloatArray) {
        val rl = srgbToLinear(r)
        val gl = srgbToLinear(g)
        val bl = srgbToLinear(b)
        val x = labF((0.4124f * rl + 0.3576f * gl + 0.1805f * bl) / 0.95047f)
        val y = labF(0.2126f * rl + 0.7152f * gl + 0.0722f * bl)
        val z = labF((0.0193f * rl + 0.1192f * gl + 0.9505f * bl) / 1.08883f)
        out[0] = 116f * y - 16f
        out[1] = 500f * (x - y)
        out[2] = 200f * (y - z)
    }

    private fun srgbToLinear(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).let { it * it * it }

    private fun labF(t: Float): Float =
        if (t > 0.008856f) Math.cbrt(t.toDouble()).toFloat() else 7.787f * t + 16f / 116f

    /**
     * Edge-aware refinement (guided filter) of a coarse weight grid, guided by cell luminance. The
     * mask ends up following the picture's real edges instead of the grid's squares, and the
     * correction is strongest where the guide has structure.
     */
    private fun guidedRefine(data: FloatArray, guide: FloatArray, w: Int, h: Int, feather: Float) {
        val r = (2 + feather * 8f).toInt()
        val eps = 0.02f + (1f - feather) * 0.10f
        val meanI = boxBlur(guide, w, h, r)
        val meanP = boxBlur(data, w, h, r)
        val corrI = boxBlur(FloatArray(w * h) { guide[it] * guide[it] }, w, h, r)
        val corrIP = boxBlur(FloatArray(w * h) { guide[it] * data[it] }, w, h, r)
        val a = FloatArray(w * h)
        val b = FloatArray(w * h)
        for (i in 0 until w * h) {
            val varI = corrI[i] - meanI[i] * meanI[i]
            val covIP = corrIP[i] - meanI[i] * meanP[i]
            val ai = covIP / (varI + eps)
            a[i] = ai
            b[i] = meanP[i] - ai * meanI[i]
        }
        val meanA = boxBlur(a, w, h, r)
        val meanB = boxBlur(b, w, h, r)
        for (i in 0 until w * h) {
            data[i] = (meanA[i] * guide[i] + meanB[i]).coerceIn(0f, 1f)
        }
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val copy = src.copyOf()
        boxBlurInPlace(copy, w, h, r)
        return copy
    }

    private fun boxBlurInPlace(data: FloatArray, w: Int, h: Int, r: Int) {
        if (r <= 0) return
        val tmp = FloatArray(w * h)
        val norm = 1f / (2 * r + 1)
        for (y in 0 until h) {
            val row = y * w
            var acc = 0f
            for (k in -r..r) acc += data[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = acc * norm
                acc += data[row + (x + r + 1).coerceAtMost(w - 1)] - data[row + (x - r).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var acc = 0f
            for (k in -r..r) acc += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                data[y * w + x] = acc * norm
                acc += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
            }
        }
    }

    private fun smoothstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}
