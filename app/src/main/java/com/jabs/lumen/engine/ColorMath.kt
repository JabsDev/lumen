package com.jabs.lumen.engine

import com.jabs.lumen.model.ColorGrading
import com.jabs.lumen.model.ColorMixer
import com.jabs.lumen.model.MixerBand
import com.jabs.lumen.model.ToneCurve
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Builds a 256-entry lookup table from a tone curve, using monotone cubic interpolation. */
fun ToneCurve.toLut(): FloatArray {
    val lut = FloatArray(256) { it / 255f }
    if (isIdentity() || points.size < 2) return lut

    val sorted = points.map { it.coerce() }.sortedBy { it.x }
    val n = sorted.size
    val xs = FloatArray(n) { sorted[it].x }
    val ys = FloatArray(n) { sorted[it].y }

    // Secants, then monotone tangents (Fritsch-Carlson) so the curve never overshoots.
    val slopes = FloatArray(n - 1)
    for (i in 0 until n - 1) {
        val dx = xs[i + 1] - xs[i]
        slopes[i] = if (dx > 0f) (ys[i + 1] - ys[i]) / dx else 0f
    }
    val tangents = FloatArray(n)
    tangents[0] = slopes.first()
    tangents[n - 1] = slopes.last()
    for (i in 1 until n - 1) {
        val a = slopes[i - 1]
        val b = slopes[i]
        tangents[i] = if (a * b <= 0f) 0f else (a + b) / 2f
    }
    for (i in 0 until n - 1) {
        if (slopes[i] == 0f) {
            tangents[i] = 0f
            tangents[i + 1] = 0f
        } else {
            val alpha = tangents[i] / slopes[i]
            val beta = tangents[i + 1] / slopes[i]
            val limit = alpha * alpha + beta * beta
            if (limit > 9f) {
                val tau = 3f / sqrt(limit)
                tangents[i] = tau * alpha * slopes[i]
                tangents[i + 1] = tau * beta * slopes[i]
            }
        }
    }

    var segment = 0
    for (index in 0 until 256) {
        val x = index / 255f
        if (x <= xs.first()) {
            lut[index] = ys.first()
            continue
        }
        if (x >= xs.last()) {
            lut[index] = ys.last()
            continue
        }
        while (segment < n - 2 && x > xs[segment + 1]) segment++
        val h = xs[segment + 1] - xs[segment]
        if (h <= 0f) {
            lut[index] = ys[segment]
            continue
        }
        val t = (x - xs[segment]) / h
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2f * t3 - 3f * t2 + 1f
        val h10 = t3 - 2f * t2 + t
        val h01 = -2f * t3 + 3f * t2
        val h11 = t3 - t2
        lut[index] = h00 * ys[segment] + h10 * h * tangents[segment] +
            h01 * ys[segment + 1] + h11 * h * tangents[segment + 1]
    }
    return lut
}

/** Precomputed colouring maths, built once per render so the pixel loop allocates nothing. */
class ColorTools(
    val grading: ColorGrading,
    val mixer: ColorMixer,
) {
    val enabled: Boolean = !grading.isIdentity() || !mixer.isIdentity()
    private val gradingActive = !grading.isIdentity()

    private val shadowHue = grading.shadows.hue
    private val shadowAmount = grading.shadows.amount
    private val midHue = grading.midtones.hue
    private val midAmount = grading.midtones.amount
    private val highHue = grading.highlights.hue
    private val highAmount = grading.highlights.amount

    private val shadeR = tint(shadowHue, 0f)
    private val shadeG = tint(shadowHue, -2.0944f)
    private val shadeB = tint(shadowHue, 2.0944f)
    private val midR = tint(midHue, 0f)
    private val midG = tint(midHue, -2.0944f)
    private val midB = tint(midHue, 2.0944f)
    private val highR = tint(highHue, 0f)
    private val highG = tint(highHue, -2.0944f)
    private val highB = tint(highHue, 2.0944f)

    private val mixerActive = !mixer.isIdentity()
    private val bandHue = FloatArray(MixerBand.entries.size)
    private val bandSat = FloatArray(MixerBand.entries.size)
    private val bandLum = FloatArray(MixerBand.entries.size)
    private val bandCentre = FloatArray(MixerBand.entries.size) { MixerBand.entries[it].hue }

    init {
        MixerBand.entries.forEachIndexed { index, band ->
            val adjust = mixer[band]
            bandHue[index] = adjust.hue
            bandSat[index] = adjust.saturation
            bandLum[index] = adjust.luminance
        }
    }

    private fun tint(hue: Float, offset: Float): Float {
        val radians = Math.toRadians(hue.toDouble()).toFloat() + offset
        return (0.5f + 0.5f * cos(radians)).coerceAtLeast(0f)
    }

    /** Applies grading and the mixer to one pixel. Nothing is allocated here. */
    fun apply(r: Float, g: Float, b: Float, luma: Float, out: FloatArray) {
        var cr = r
        var cg = g
        var cb = b

        if (gradingActive) {
            cr = pushRange(cr, luma, 0.5f, 1f, shadowAmount, shadeR)
            cg = pushRange(cg, luma, 0.5f, 1f, shadowAmount, shadeG)
            cb = pushRange(cb, luma, 0.5f, 1f, shadowAmount, shadeB)

            cr = pushRange(cr, luma, 0.25f, 0.75f, midAmount, midR)
            cg = pushRange(cg, luma, 0.25f, 0.75f, midAmount, midG)
            cb = pushRange(cb, luma, 0.25f, 0.75f, midAmount, midB)

            cr = pushRange(cr, luma, 0.5f, 0f, highAmount, highR)
            cg = pushRange(cg, luma, 0.5f, 0f, highAmount, highG)
            cb = pushRange(cb, luma, 0.5f, 0f, highAmount, highB)
        }

        if (mixerActive) {
            val max = maxOf(cr, cg, cb)
            val min = minOf(cr, cg, cb)
            if (max - min >= 0.06f && max > 0.0001f) {
                var hue = hueOf(cr, cg, cb, max, min)
                var satShift = 0f
                var lumShift = 0f
                for (index in 0 until BAND_COUNT) {
                    val weight = weightFor(hue, bandCentre[index])
                    if (weight <= 0.001f) continue
                    // Slider values are already normalised to -1..1; the UI shows them as percent.
                    hue += bandHue[index] * weight * HUE_RANGE
                    satShift += bandSat[index] * weight
                    lumShift += bandLum[index] * weight
                }
                if (satShift != 0f || lumShift != 0f || hue != hueOf(cr, cg, cb, max, min)) {
                    val sat = ((max - min) / max + satShift).coerceIn(0f, 1f)
                    val lightness = (max + lumShift * 0.5f).coerceIn(0f, 1f)
                    fromHsl(hue.mod(360f), sat, lightness, out)
                    return
                }
            }
        }

        out[0] = cr
        out[1] = cg
        out[2] = cb
    }

    private fun pushRange(channel: Float, luma: Float, low: Float, high: Float, amount: Float, tint: Float): Float {
        val weight = smoothRange(luma, low, high)
        if (weight <= 0.001f || amount == 0f) return channel
        val strength = (amount * weight).coerceIn(-1f, 1f)
        val pull = abs(strength)
        // Positive pulls the channel towards the chosen hue; negative pushes it away again.
        val direction = if (strength >= 0f) 1f else -1f
        return channel + (tint - channel) * pull * direction
    }

    private fun weightFor(hue: Float, centre: Float): Float {
        var distance = abs(hue - centre)
        if (distance > 180f) distance = 360f - distance
        return (1f - distance / 55f).coerceIn(0f, 1f)
    }

    companion object {
        private const val BAND_COUNT = 8
        private const val HUE_RANGE = 60f

        private fun smoothRange(value: Float, low: Float, high: Float): Float {
            if (low > high) {
                // Highlights: weight rises as luma goes above the low edge.
                if (value <= low) return 1f
                if (value >= high) return 0f
                val t = (value - low) / (high - low)
                return 1f - (t * t * (3f - 2f * t))
            }
            if (value <= low) return 0f
            if (value >= high) return 1f
            val t = (value - low) / (high - low)
            return t * t * (3f - 2f * t)
        }

        private fun hueOf(r: Float, g: Float, b: Float, max: Float, min: Float): Float {
            val delta = max - min
            if (delta <= 0.0001f) return 0f
            val hue = when (max) {
                r -> 60f * (((g - b) / delta) % 6f)
                g -> 60f * (((b - r) / delta) + 2f)
                else -> 60f * (((r - g) / delta) + 4f)
            }
            return (hue + 360f) % 360f
        }

        private fun fromHsl(hue: Float, saturation: Float, lightness: Float, out: FloatArray) {
            val c = (1f - abs(2f * lightness - 1f)) * saturation
            val h = hue / 60f
            val x = c * (1f - abs((h % 2f) - 1f))
            val m = lightness - c / 2f
            when (h.toInt()) {
                0 -> { out[0] = c; out[1] = x; out[2] = 0f }
                1 -> { out[0] = x; out[1] = c; out[2] = 0f }
                2 -> { out[0] = 0f; out[1] = c; out[2] = x }
                3 -> { out[0] = 0f; out[1] = x; out[2] = c }
                4 -> { out[0] = x; out[1] = 0f; out[2] = c }
                else -> { out[0] = c; out[1] = 0f; out[2] = x }
            }
            out[0] += m
            out[1] += m
            out[2] += m
        }
    }
}