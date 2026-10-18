package com.jabs.lumen.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import com.jabs.lumen.model.Mask
import com.jabs.lumen.model.MaskAdjust
import com.jabs.lumen.model.MixerBand
import com.jabs.lumen.model.CurveSet
import com.jabs.lumen.model.EditParams
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

object ImageEngine {

    private const val EMBEDDED_THUMB_MAX = 512
    private const val LUMA_R = 0.2126f
    private const val LUMA_G = 0.7152f
    private const val LUMA_B = 0.0722f

    /** Resolution of the per-mask exposure lookup table; 256 steps keeps the error below 1/255. */
    private const val GAIN_STEPS = 256

    /** Below these sizes the chunking costs more than the parallel pass saves. */
    private const val MIN_SPLIT_PIXELS = 120_000
    private const val MIN_SPLIT_ROWS = 128

    private class MaskRun(
        val grid: MaskEngine.Grid,
        val adjust: com.jabs.lumen.model.MaskAdjust,
        val gainLut: FloatArray?,
        val hasTone: Boolean,
        val hasSat: Boolean,
    )

    /** One core is always left free for the UI and render threads. */
    private val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)

    private val renderPool: ExecutorService by lazy {
        Executors.newFixedThreadPool(threads) { r ->
            Thread(r, "LumenRender").apply { isDaemon = true }
        }
    }

    /**
     * Runs one render pass across the available cores. Every pixel depends only on itself, so the
     * range is simply split; each chunk gets its own scratch so two threads never write the same
     * place. The caller is a coroutine worker, never a pool thread, so waiting here cannot stall
     * the very work being waited on.
     */
    private fun runPixels(size: Int, minSize: Int, body: (Int, Int, FloatArray) -> Unit) {
        if (threads < 2 || size < minSize) {
            body(0, size, FloatArray(3))
            return
        }
        val latch = CountDownLatch(threads)
        val failure = AtomicReference<Throwable?>()
        val chunk = size / threads
        for (t in 0 until threads) {
            val from = t * chunk
            val to = if (t == threads - 1) size else from + chunk
            try {
                renderPool.execute {
                    try {
                        body(from, to, FloatArray(3))
                    } catch (e: Throwable) {
                        failure.compareAndSet(null, e)
                    } finally {
                        latch.countDown()
                    }
                }
            } catch (e: RejectedExecutionException) {
                // Pool unavailable: run this chunk inline so the render still completes.
                try {
                    body(from, to, FloatArray(3))
                } catch (t2: Throwable) {
                    failure.compareAndSet(null, t2)
                }
                latch.countDown()
            }
        }
        latch.await()
        failure.get()?.let { throw it }
    }

    fun decode(file: File, maxSize: Int? = null): Bitmap {
        if (RawDecoder.isRaw(file)) return decodeRaw(file, maxSize)
        val source = ImageDecoder.createSource(file)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            if (maxSize != null) {
                val scale = min(1f, maxSize / max(info.size.width, info.size.height).toFloat())
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
    }

    private fun decodeRaw(file: File, maxSize: Int?): Bitmap {
        if (maxSize != null && maxSize <= EMBEDDED_THUMB_MAX) {
            RawDecoder.embeddedThumbnail(file)?.let { return it }
        }
        val full = RawDecoder.decode(file, halfSize = maxSize != null)
            ?: throw IllegalStateException("Não foi possível ler o RAW ${file.name}")
        if (maxSize == null) return full
        val scale = min(1f, maxSize / max(full.width, full.height).toFloat())
        if (scale >= 1f) return full
        return Bitmap.createScaledBitmap(
            full,
            (full.width * scale).toInt().coerceAtLeast(1),
            (full.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * The size the photo is shown at — the header's pixel size with its EXIF orientation applied,
     * so width/height match the bytes [decode] hands back. Used to place the 1:1 viewport rect.
     * Returns null for RAW (its header is not readable through BitmapFactory).
     */
    fun dimensions(file: File): Pair<Int, Int>? {
        if (RawDecoder.isRaw(file)) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return if (orientation(file).swapsAxes) opts.outHeight to opts.outWidth
        else opts.outWidth to opts.outHeight
    }

    /**
     * The EXIF orientation of the file, expressed as the quarter turn and mirror needed to show its
     * stored raster upright. `ImageDecoder` applies this by itself, but `BitmapRegionDecoder` and
     * `BitmapFactory` read the raster as stored, so callers that use those must apply it here.
     * Any unreadable or unsupported header (including RAW) is treated as already upright.
     */
    fun orientation(file: File): Orientation {
        if (RawDecoder.isRaw(file)) return Orientation.NORMAL
        return runCatching {
            val exif = ExifInterface(file.absolutePath)
            fromExif(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        }.getOrDefault(Orientation.NORMAL)
    }

    private fun fromExif(value: Int): Orientation = when (value) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Orientation(0, true)
        ExifInterface.ORIENTATION_ROTATE_180 -> Orientation(2, false)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Orientation(2, true)
        ExifInterface.ORIENTATION_TRANSPOSE -> Orientation(3, true)
        ExifInterface.ORIENTATION_ROTATE_90 -> Orientation(1, false)
        ExifInterface.ORIENTATION_TRANSVERSE -> Orientation(1, true)
        ExifInterface.ORIENTATION_ROTATE_270 -> Orientation(3, false)
        else -> Orientation.NORMAL
    }

    /**
     * Decodes a single rectangle of the file at native scale — the heart of the 1:1 detail. [rect]
     * and the source size are in **upright** (EXIF-applied) space, the same space the preview is
     * shown in and the one [dimensions] reports.
     *
     * JPEG and PNG go through [BitmapRegionDecoder], which reads stored rows and therefore gets the
     * rectangle turned back into the file's raster first and the result turned upright again; the
     * EXIF is what used to be dropped here, which is why a turned photo opened its 1:1 view on its
     * side. Anything the region decoder cannot open falls back to a capped [decode] (which is
     * already upright, so it is cropped directly). Returns null when nothing could be read.
     */
    fun decodeRegion(
        file: File,
        rect: Rect,
        sourceWidth: Int,
        sourceHeight: Int,
        maxSize: Int? = null,
        orientation: Orientation = Orientation.NORMAL,
    ): Bitmap? {
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val clamped = Rect(rect).apply {
            left = left.coerceIn(0, sourceWidth - 1)
            top = top.coerceIn(0, sourceHeight - 1)
            right = right.coerceIn(left + 1, sourceWidth)
            bottom = bottom.coerceIn(top + 1, sourceHeight)
        }
        val rawWidth = if (orientation.swapsAxes) sourceHeight else sourceWidth
        val rawHeight = if (orientation.swapsAxes) sourceWidth else sourceHeight
        val rawRect = uprightRectToRaw(clamped, sourceWidth, sourceHeight, rawWidth, rawHeight, orientation)

        if (RawDecoder.isRaw(file)) {
            val full = runCatching { decodeRaw(file, null) }.getOrNull() ?: return null
            return cropAndScale(full, rawRect, rawWidth, rawHeight, maxSize)
        }

        val region = runCatching {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(file.absolutePath, false)?.let { decoder ->
                try {
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    decoder.decodeRegion(rawRect, opts)
                } finally {
                    decoder.recycle()
                }
            }
        }.getOrNull()
        if (region != null) {
            return oriented(scaleTo(region, maxSize), orientation.quarterTurns, orientation.flipHorizontal)
        }

        // HEIC/WebP and the like: one capped full decode, already upright, cropped with the mapping.
        val full = runCatching { decode(file, null) }
            .getOrElse { runCatching { decode(file, DeviceProfile.detailCeiling(heapMb())) }.getOrNull() }
            ?: return null
        return cropAndScale(full, clamped, sourceWidth, sourceHeight, maxSize)
    }

    private fun cropAndScale(src: Bitmap, rect: Rect, sw: Int, sh: Int, maxSize: Int?): Bitmap? {
        val sx = src.width.toFloat() / sw
        val sy = src.height.toFloat() / sh
        val l = (rect.left * sx).roundToInt().coerceIn(0, src.width - 1)
        val t = (rect.top * sy).roundToInt().coerceIn(0, src.height - 1)
        val r = (rect.right * sx).roundToInt().coerceIn(l + 1, src.width)
        val b = (rect.bottom * sy).roundToInt().coerceIn(t + 1, src.height)
        return scaleTo(Bitmap.createBitmap(src, l, t, r - l, b - t), maxSize)
    }

    private fun scaleTo(bmp: Bitmap, maxSize: Int?): Bitmap {
        if (maxSize == null) return bmp
        val longest = max(bmp.width, bmp.height)
        if (longest <= maxSize) return bmp
        val scale = maxSize.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * scale).roundToInt().coerceAtLeast(1),
            (bmp.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun heapMb(): Int = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()

    /**
     * The single render entry point. [referenceLongSide] is the long side of the full-resolution
     * source; the sharpness radius is derived from it so the same slider reads the same on the
     * drag copy, the settled preview and a native 1:1 crop. Pass 0 to use [src]'s own size.
     */
    fun render(src: Bitmap, p: EditParams, referenceLongSide: Int = 0): Bitmap {
        val corrected = if (isLensIdentity(p)) src else applyLensCorrection(src, p)
        val processed = if (isPixelIdentity(p)) corrected else applyAll(corrected, p, referenceLongSide)
        return applyGeometry(processed, p)
    }

    /** Kept as an alias: the fast and full renders share the pipeline, only the source differs. */
    fun renderFast(src: Bitmap, p: EditParams, referenceLongSide: Int = 0): Bitmap =
        render(src, p, referenceLongSide)

    private fun isPixelIdentity(p: EditParams): Boolean =
        p.curves.isIdentity() && isToneIdentity(p) &&
            p.grading.isIdentity() && p.mixer.isIdentity() && !hasMasks(p) && p.lutId.isEmpty()

    private fun hasMasks(p: EditParams): Boolean =
        p.masks.any { it.enabled && !it.adjust.isIdentity() }

    /**
     * A mask's shape does not change while a tone slider is dragged — only the numbers inside
     * adjust do — so the weight grid is rasterised once and reused for every frame of that drag.
     * Content masks additionally depend on the pixels, hence the sampled fingerprint.
     */
    private data class GridKey(val mask: Mask, val w: Int, val h: Int, val source: Long)

    private val gridCache = object : LinkedHashMap<GridKey, MaskEngine.Grid>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GridKey, MaskEngine.Grid>): Boolean = size > 6
    }

    private fun gridFor(mask: Mask, pixels: IntArray, w: Int, h: Int): MaskEngine.Grid {
        val key = GridKey(mask.copy(adjust = MaskAdjust()), w, h, fingerprint(pixels))
        synchronized(gridCache) { gridCache[key]?.let { return it } }
        val grid = MaskEngine.rasterize(mask, pixels, w, h)
        synchronized(gridCache) { gridCache[key] = grid }
        return grid
    }

    /** Cheap content signature so a grid built over different pixels is never reused. */
    private fun fingerprint(pixels: IntArray): Long {
        var hash = 1125899906842597L
        val step = max(1, pixels.size / 16)
        var i = 0
        while (i < pixels.size) {
            hash = 31 * hash + pixels[i]
            i += step
        }
        return hash
    }

    /**
     * Curves, tone, grading and the mixer all run in one pass over the pixels. Doing them
     * separately allocated a full bitmap per stage, which made dragging a slider crawl.
     */
    private fun applyAll(src: Bitmap, p: EditParams, referenceLongSide: Int = 0): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val useCurves = !p.curves.isIdentity()
        val rgbLut = if (useCurves) p.curves.rgb.toLut() else null
        val rLut = if (useCurves) p.curves.red.toLut() else null
        val gLut = if (useCurves) p.curves.green.toLut() else null
        val bLut = if (useCurves) p.curves.blue.toLut() else null

        val useTone = !isToneIdentity(p)
        val gain = 2f.pow(p.exposure)
        val warm = p.temperature * 0.12f
        val greenShift = -p.tint * 0.10f
        val contrast = 1f + p.contrast
        val sat = 1f + p.saturation
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val maxDist = max(sqrt(cx * cx + cy * cy), 1f)

        val lumaSrc = if (useTone && (p.clarity != 0f || p.texture != 0f || p.sharpness != 0f || p.noiseLuminance > 0f || p.noiseColor > 0f)) {
            FloatArray(w * h) { i ->
                val c = pixels[i]
                LUMA_R * ((c shr 16) and 0xFF) / 255f +
                    LUMA_G * ((c shr 8) and 0xFF) / 255f +
                    LUMA_B * (c and 0xFF) / 255f
            }
        } else null

        // Detail runs on the source luminance, before the tone curves: noise reduction first (so
        // clarity/texture/sharpness never amplify grain), sharpening last. The radii are scaled by
        // the render-to-source ratio so a slider reads the same on the drag copy, the settled
        // preview and a native 1:1 crop. When nothing is set, every buffer below stays null and the
        // pipeline is byte-for-byte the one it has always been.
        val detailFactor = if (referenceLongSide > 0) max(w, h).toFloat() / referenceLongSide else 1f
        val sharpenRadius = max(1, ((0.7f + p.sharpenRadius) * detailFactor).roundToInt())
        val lumNrRadius = max(1, ((0.6f + 1.4f * p.noiseLuminance) * detailFactor).roundToInt())

        val lumaNr = if (lumaSrc != null && p.noiseLuminance > 0f) {
            boxBlur(lumaSrc, w, h, lumNrRadius)
        } else null
        val lumaWork = if (lumaSrc != null && lumaNr != null) {
            // Edges (a big local difference) are left alone; flat grain is pulled towards the blur.
            val edge = 0.015f + 0.085f * p.noiseLuminance
            FloatArray(w * h) { i ->
                val diff = lumaSrc[i] - lumaNr[i]
                val border = 1f - clamp01(abs(diff) / edge)
                val weight = p.noiseLuminance * border * (0.35f + 0.65f * p.noiseDetail)
                clamp01(lumaSrc[i] - weight * diff)
            }
        } else null

        // The detail bases are built on the denoised luminance when there is one, so clarity and
        // sharpness never reopen the grain the NR just removed.
        val baseLuma = lumaWork ?: lumaSrc
        val clarityBase = if (baseLuma != null && p.clarity != 0f) {
            boxBlur(baseLuma, w, h, max(4, max(w, h) / 60))
        } else null
        val textureBase = if (baseLuma != null && p.texture != 0f) boxBlur(baseLuma, w, h, 2) else null
        val sharpBase = if (baseLuma != null && p.sharpness != 0f) {
            boxBlur(baseLuma, w, h, sharpenRadius)
        } else null

        // Colour noise is broad, so it is blurred at half resolution — the buffers stay tiny even
        // on a big settled preview, and the chroma is put back over the untouched luminance.
        val chroma = if (lumaSrc != null && p.noiseColor > 0f) {
            halfChromaBlur(pixels, w, h, max(1, (lumNrRadius * 1.5f).roundToInt()))
        } else null
        val chromaWeight = p.noiseColor * (0.4f + 0.6f * p.noiseDetail)

        val colorTools = if (!p.grading.isIdentity() || !p.mixer.isIdentity()) ColorTools(p.grading, p.mixer) else null
        // The look goes on last, so it grades the local adjustments and the colour work under it.
        val cube = LutLibrary.get(p.lutId)
        val cubeAmount = p.lutAmount
        val maskRuns = if (hasMasks(p)) {
            p.masks.filter { it.enabled && !it.adjust.isIdentity() }.map { mask ->
                val a = mask.adjust
                MaskRun(
                    grid = gridFor(mask, pixels, w, h),
                    adjust = a,
                    // 2^(exposure·weight) sampled once per mask instead of one pow per pixel:
                    // that call was most of the cost of dragging a slider with masks on.
                    gainLut = if (a.exposure == 0f) null else FloatArray(GAIN_STEPS + 1) { k ->
                        2f.pow(a.exposure * (k / GAIN_STEPS.toFloat()))
                    },
                    hasTone = a.contrast != 0f || a.shadows != 0f || a.highlights != 0f ||
                        a.whites != 0f || a.blacks != 0f,
                    hasSat = a.saturation != 0f,
                )
            }
        } else null
        val invW = 1f / max(w - 1, 1)
        val invH = 1f / max(h - 1, 1)

        val process = { from: Int, until: Int, scratch: FloatArray ->
            for (i in from until until) {
            val c = pixels[i]
            var r = ((c shr 16) and 0xFF) / 255f
            var g = ((c shr 8) and 0xFF) / 255f
            var b = (c and 0xFF) / 255f

            if (lumaSrc != null && (lumaWork != null || chroma != null)) {
                val sourceLuma = lumaSrc[i]
                val workLuma = lumaWork?.get(i) ?: sourceLuma
                if (lumaWork != null) {
                    // Luminance NR travels as a delta so the colour of the pixel is kept.
                    val delta = workLuma - sourceLuma
                    if (delta != 0f) {
                        r = clamp01(r + delta)
                        g = clamp01(g + delta)
                        b = clamp01(b + delta)
                    }
                }
                if (chroma != null) {
                    val ci = ((i / w) shr 1) * chroma.stride + ((i % w) shr 1)
                    val coNow = r - b
                    val t = b + coNow * 0.5f
                    val cgNow = g - t
                    val coNew = coNow + (chroma.co[ci] - coNow) * chromaWeight
                    val cgNew = cgNow + (chroma.cg[ci] - cgNow) * chromaWeight
                    val t2 = workLuma - cgNew * 0.5f
                    g = cgNew + t2
                    b = t2 - coNew * 0.5f
                    r = b + coNew
                }
                r = clamp01(r)
                g = clamp01(g)
                b = clamp01(b)
            }

            if (rgbLut != null && rLut != null && gLut != null && bLut != null) {
                r = rLut[(rgbLut[(r * 255f).toInt().coerceIn(0, 255)] * 255f).toInt().coerceIn(0, 255)]
                g = gLut[(rgbLut[(g * 255f).toInt().coerceIn(0, 255)] * 255f).toInt().coerceIn(0, 255)]
                b = bLut[(rgbLut[(b * 255f).toInt().coerceIn(0, 255)] * 255f).toInt().coerceIn(0, 255)]
            }

            if (useTone) {
                r = clamp01(r * gain + warm)
                g = clamp01(g * gain + greenShift)
                b = clamp01(b * gain - warm)

                val luma = LUMA_R * r + LUMA_G * g + LUMA_B * b
                val shadowWeight = (1f - luma) * (1f - luma)
                val highlightWeight = luma * luma
                val whiteWeight = clamp01((luma - 0.5f) * 2f).let { it * it }
                val blackWeight = clamp01((0.5f - luma) * 2f).let { it * it }
                var toned = luma + p.shadows * 0.3f * shadowWeight +
                    p.highlights * 0.3f * highlightWeight +
                    p.whites * 0.25f * whiteWeight + p.blacks * 0.25f * blackWeight
                if (clarityBase != null) {
                    val midWeight = 4f * luma * (1f - luma)
                    toned += p.clarity * 0.6f * midWeight * gain * (baseLuma!![i] - clarityBase[i])
                }
                if (textureBase != null) {
                    toned += p.texture * 0.8f * gain * (baseLuma!![i] - textureBase[i])
                }
                if (sharpBase != null) {
                    // Unsharp mask on the working luminance. The weight fades the effect at the very
                    // dark and very bright ends, where a halo would be most visible, and the gain
                    // grows with the amount requested.
                    val detalhe = baseLuma!![i] - sharpBase[i]
                    val flat = clamp01(toned)
                    val haloWeight = 1f - (2f * flat - 1f).pow(2)
                    val ganho = 1f + 2f * abs(p.sharpness)
                    toned += p.sharpness * 0.5f * haloWeight * ganho * detalhe
                }
                toned = clamp01(toned)
                val target = clamp01((toned - 0.5f) * contrast + 0.5f)

                if (luma > 1e-4f) {
                    val k = target / luma
                    r *= k
                    g *= k
                    b *= k
                } else {
                    r = target
                    g = target
                    b = target
                }

                r = clamp01(r)
                g = clamp01(g)
                b = clamp01(b)
                val chroma = max(r, max(g, b)) - min(r, min(g, b))
                val s = sat * (1f + p.vibrance * (1f - chroma))
                r = clamp01(target + (r - target) * s)
                g = clamp01(target + (g - target) * s)
                b = clamp01(target + (b - target) * s)

                if (p.vignette != 0f) {
                    val dx = (i % w) - cx
                    val dy = (i / w) - cy
                    val d = sqrt(dx * dx + dy * dy) / maxDist
                    val f = 1f - p.vignette * 0.7f * d * d
                    r = clamp01(r * f)
                    g = clamp01(g * f)
                    b = clamp01(b * f)
                }
            }

            if (maskRuns != null) {
                val nx = (i % w) * invW
                val ny = (i / w) * invH
                for (run in maskRuns) {
                    val mw = MaskEngine.sample(run.grid, nx, ny)
                    if (mw > 0.004f) {
                        val lut = run.gainLut
                        val gain = if (lut == null) 1f else {
                            val x = mw * GAIN_STEPS
                            val i0 = x.toInt().coerceIn(0, GAIN_STEPS - 1)
                            val f = x - i0
                            lut[i0] + (lut[i0 + 1] - lut[i0]) * f
                        }
                        val adjust = run.adjust
                        val temp = adjust.temperature * 0.12f * mw
                        r = clamp01(r * gain + temp)
                        g = clamp01(g * gain - adjust.tint * 0.10f * mw)
                        b = clamp01(b * gain - temp)

                        // Tone and saturation are skipped when the mask does not use them; most
                        // masks are exposure-only and were paying for a whole tonal pass.
                        if (run.hasTone || run.hasSat) {
                            val ml = LUMA_R * r + LUMA_G * g + LUMA_B * b
                            var mTarget = ml
                            if (run.hasTone) {
                                val sw = (1f - ml) * (1f - ml)
                                val hw = ml * ml
                                val ww = clamp01((ml - 0.5f) * 2f).let { it * it }
                                val bw = clamp01((0.5f - ml) * 2f).let { it * it }
                                var mt = ml + (adjust.shadows * 0.3f * sw + adjust.highlights * 0.3f * hw +
                                    adjust.whites * 0.25f * ww + adjust.blacks * 0.25f * bw) * mw
                                mt = clamp01(mt)
                                mTarget = clamp01((mt - 0.5f) * (1f + adjust.contrast * mw) + 0.5f)
                                if (ml > 1e-4f) {
                                    val k = mTarget / ml
                                    r *= k
                                    g *= k
                                    b *= k
                                } else {
                                    r = mTarget
                                    g = mTarget
                                    b = mTarget
                                }
                                r = clamp01(r)
                                g = clamp01(g)
                                b = clamp01(b)
                            }
                            if (run.hasSat) {
                                val s = 1f + adjust.saturation * mw
                                r = clamp01(mTarget + (r - mTarget) * s)
                                g = clamp01(mTarget + (g - mTarget) * s)
                                b = clamp01(mTarget + (b - mTarget) * s)
                            }
                        }
                    }
                }
            }

            if (colorTools != null) {
                val luma = LUMA_R * r + LUMA_G * g + LUMA_B * b
                colorTools.apply(r, g, b, luma, scratch)
                r = scratch[0]
                g = scratch[1]
                b = scratch[2]
            }

            if (cube != null && cubeAmount > 0f) {
                applyCube(cube, cubeAmount, r, g, b, scratch)
                r = scratch[0]
                g = scratch[1]
                b = scratch[2]
            }

            pixels[i] = (0xFF shl 24) or (toChannel(r) shl 16) or (toChannel(g) shl 8) or toChannel(b)
            }
        }
        runPixels(pixels.size, MIN_SPLIT_PIXELS, process)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * One trilinear step of a 3D LUT: the eight samples around the pixel's colour are lerped
     * along red, green and blue, then blended back towards the original by [amount]. This
     * runs once per pixel, so it takes its values as arguments and allocates nothing.
     */
    private fun applyCube(lut: Lut, amount: Float, r: Float, g: Float, b: Float, out: FloatArray) {
        val n = lut.size
        val last = n - 1
        val data = lut.data

        val cr = clamp01(r)
        val cg = clamp01(g)
        val cb = clamp01(b)

        // Where the colour falls inside the cube, and how far it is from the nearest samples.
        val rf = cr * last
        val gf = cg * last
        val bf = cb * last
        val r0 = rf.toInt()
        val g0 = gf.toInt()
        val b0 = bf.toInt()
        val r1 = if (r0 < last) r0 + 1 else r0
        val g1 = if (g0 < last) g0 + 1 else g0
        val b1 = if (b0 < last) b0 + 1 else b0
        val fr = rf - r0
        val fg = gf - g0
        val fb = bf - b0

        // The eight corners: blue walks in whole planes, green in rows, red in single steps.
        val o000 = ((b0 * n + g0) * n + r0) * 3
        val o100 = ((b0 * n + g0) * n + r1) * 3
        val o010 = ((b0 * n + g1) * n + r0) * 3
        val o110 = ((b0 * n + g1) * n + r1) * 3
        val o001 = ((b1 * n + g0) * n + r0) * 3
        val o101 = ((b1 * n + g0) * n + r1) * 3
        val o011 = ((b1 * n + g1) * n + r0) * 3
        val o111 = ((b1 * n + g1) * n + r1) * 3

        val ra = corner(data, o000, o100, fr)
        val rb = corner(data, o010, o110, fr)
        val rc = corner(data, o001, o101, fr)
        val rd = corner(data, o011, o111, fr)
        val rx = ra + (rb - ra) * fg
        val ry = rc + (rd - rc) * fg
        val lr = rx + (ry - rx) * fb

        val ga = corner(data, o000, o100 + 1, fr)
        val gb = corner(data, o010 + 1, o110 + 1, fr)
        val gc = corner(data, o001 + 1, o101 + 1, fr)
        val gd = corner(data, o011 + 1, o111 + 1, fr)
        val gx = ga + (gb - ga) * fg
        val gy = gc + (gd - gc) * fg
        val lg = gx + (gy - gx) * fb

        val ba = corner(data, o000 + 2, o100 + 2, fr)
        val bb = corner(data, o010 + 2, o110 + 2, fr)
        val bc = corner(data, o001 + 2, o101 + 2, fr)
        val bd = corner(data, o011 + 2, o111 + 2, fr)
        val bx = ba + (bb - ba) * fg
        val by = bc + (bd - bc) * fg
        val lb = bx + (by - bx) * fb

        out[0] = cr + (lr - cr) * amount
        out[1] = cg + (lg - cg) * amount
        out[2] = cb + (lb - cb) * amount
    }

    /** Lerp between two neighbouring LUT samples for one channel (+0 red, +1 green, +2 blue). */
    private fun corner(data: FloatArray, from: Int, to: Int, t: Float): Float =
        data[from] + (data[to] - data[from]) * t

    /**
     * One 256-bin histogram per channel. The editor draws the luminance silhouette with the three
     * colour channels traced over it, so all four travel together.
     */
    class Histograms(
        val luma: IntArray = IntArray(256),
        val red: IntArray = IntArray(256),
        val green: IntArray = IntArray(256),
        val blue: IntArray = IntArray(256),
    )

    fun histogram(bitmap: Bitmap): Histograms {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = Histograms()
        val step = max(1, sqrt(w * h / 40000f).toInt())
        var i = 0
        while (i < pixels.size) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            out.red[r]++
            out.green[g]++
            out.blue[b]++
            out.luma[(LUMA_R * r + LUMA_G * g + LUMA_B * b).toInt().coerceIn(0, 255)]++
            i += step * step
        }
        return out
    }

    private fun isLensIdentity(p: EditParams): Boolean =
        p.distortion == 0f && p.chromaRed == 0f && p.chromaBlue == 0f &&
            p.chromaShiftRed == 0f && p.chromaShiftBlue == 0f

    /**
     * How far a colour channel is scaled from the centre. The negative side stays fine and linear
     * (a precise correction for an existing fringe); the positive side grows quadratically, so a
     * large "+" adds a fringe strong enough to read as a creative effect.
     */
    private fun chromaScale(v: Float): Float =
        if (v >= 0f) 1f + 0.012f * v * v else 1f + 0.004f * v

    private fun applyLensCorrection(src: Bitmap, p: EditParams): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)

        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val maxR2 = max(cx * cx + cy * cy, 1f)
        val k = p.distortion * 0.25f
        val redScale = chromaScale(p.chromaRed)
        val blueScale = chromaScale(p.chromaBlue)
        // A uniform sideways offset, the non-radial fringe an old lens leaves behind. Capped at
        // ±2% of the width so the clamped sampler never shows hard edge stripes.
        val redShift = (p.chromaShiftRed.coerceIn(-0.02f, 0.02f)) * w
        val blueShift = (p.chromaShiftBlue.coerceIn(-0.02f, 0.02f)) * w

        val process = { fromRow: Int, untilRow: Int, _: FloatArray ->
            for (y in fromRow until untilRow) {
                for (x in 0 until w) {
                    val dx = x - cx
                    val dy = y - cy
                    val f = 1f + k * (dx * dx + dy * dy) / maxR2
                    val r = sampleChannel(pixels, w, h, cx + dx * f * redScale + redShift, cy + dy * f * redScale, 16)
                    val g = sampleChannel(pixels, w, h, cx + dx * f, cy + dy * f, 8)
                    val b = sampleChannel(pixels, w, h, cx + dx * f * blueScale + blueShift, cy + dy * f * blueScale, 0)
                    out[y * w + x] = (0xFF shl 24) or (toChannel(r) shl 16) or (toChannel(g) shl 8) or toChannel(b)
                }
            }
        }
        runPixels(h, MIN_SPLIT_ROWS, process)

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun sampleChannel(px: IntArray, w: Int, h: Int, sx: Float, sy: Float, shift: Int): Float {
        val x = sx.coerceIn(0f, (w - 1).toFloat())
        val y = sy.coerceIn(0f, (h - 1).toFloat())
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val fx = x - x0
        val fy = y - y0
        val c00 = (px[y0 * w + x0] shr shift) and 0xFF
        val c10 = (px[y0 * w + x1] shr shift) and 0xFF
        val c01 = (px[y1 * w + x0] shr shift) and 0xFF
        val c11 = (px[y1 * w + x1] shr shift) and 0xFF
        val top = c00 + (c10 - c00) * fx
        val bottom = c01 + (c11 - c01) * fx
        return (top + (bottom - top) * fy) / 255f
    }

    private fun isToneIdentity(p: EditParams): Boolean =
        p.exposure == 0f && p.contrast == 0f && p.highlights == 0f && p.shadows == 0f &&
            p.whites == 0f && p.blacks == 0f && p.clarity == 0f && p.texture == 0f &&
            p.sharpness == 0f && p.noiseLuminance == 0f && p.noiseColor == 0f &&
            p.temperature == 0f && p.tint == 0f && p.saturation == 0f && p.vibrance == 0f &&
            p.vignette == 0f

    private fun applyGeometry(src: Bitmap, p: EditParams): Bitmap {
        val quarter = ((p.rotation % 4) + 4) % 4
        if (quarter == 0 && !p.flipHorizontal) return src

        val w = src.width
        val h = src.height
        val outW = if (quarter % 2 == 1) h else w
        val outH = if (quarter % 2 == 1) w else h

        val m = Matrix()
        if (p.flipHorizontal) m.postScale(-1f, 1f, w / 2f, h / 2f)
        m.postRotate(90f * quarter, w / 2f, h / 2f)
        m.postTranslate((outW - w) / 2f, (outH - h) / 2f)

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, m, null)
        return out
    }

    /** Chroma planes of a source, sampled at half resolution and blotted. */
    private class Chroma(val co: FloatArray, val cg: FloatArray, val stride: Int)

    /**
     * Blurs the source's chroma at half resolution in the YCoCg-like space (Co = R − B,
     * Cg = G − (R+B)/2). The luminance is not part of the plane, so it can be put back exactly,
     * which is what keeps colour noise reduction from smearing detail.
     */
    private fun halfChromaBlur(pixels: IntArray, w: Int, h: Int, radius: Int): Chroma {
        val sw = (w + 1) / 2
        val sh = (h + 1) / 2
        val co = FloatArray(sw * sh)
        val cg = FloatArray(sw * sh)
        for (sy in 0 until sh) {
            val y = (sy * 2).coerceAtMost(h - 1)
            var si = sy * sw
            val row = y * w
            for (sx in 0 until sw) {
                val x = (sx * 2).coerceAtMost(w - 1)
                val c = pixels[row + x]
                val rf = ((c shr 16) and 0xFF) / 255f
                val gf = ((c shr 8) and 0xFF) / 255f
                val bf = (c and 0xFF) / 255f
                co[si] = rf - bf
                cg[si] = gf - (rf + bf) * 0.5f
                si++
            }
        }
        return Chroma(boxBlur(co, sw, sh, radius), boxBlur(cg, sw, sh, radius), sw)
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        val norm = 1f / (2 * r + 1)

        for (y in 0 until h) {
            val row = y * w
            var acc = 0f
            for (k in -r..r) acc += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = acc * norm
                acc += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
            }
        }

        for (x in 0 until w) {
            var acc = 0f
            for (k in -r..r) acc += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = acc * norm
                acc += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
            }
        }
        return out
    }

    private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    private fun toChannel(v: Float): Int = (clamp01(v) * 255f + 0.5f).toInt()
}
