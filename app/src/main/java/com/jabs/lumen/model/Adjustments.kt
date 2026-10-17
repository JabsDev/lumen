package com.jabs.lumen.model

/** A control point on a tone curve. Both coordinates are normalised to 0..1. */
data class CurvePoint(val x: Float, val y: Float) {
    fun coerce() = CurvePoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

/** Tone curve for one channel: RGB applies to all three, R/G/B individually. */
data class ToneCurve(val points: List<CurvePoint>) {
    companion object {
        val EMPTY = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)))
    }

    fun isIdentity(): Boolean =
        points.size == 2 && points[0].x == 0f && points[0].y == 0f && points[1].x == 1f && points[1].y == 1f
}

/** All four curves Lightroom-style: the combined one plus each colour channel. */
data class CurveSet(
    val rgb: ToneCurve = ToneCurve.EMPTY,
    val red: ToneCurve = ToneCurve.EMPTY,
    val green: ToneCurve = ToneCurve.EMPTY,
    val blue: ToneCurve = ToneCurve.EMPTY,
) {
    fun isIdentity(): Boolean = rgb.isIdentity() && red.isIdentity() && green.isIdentity() && blue.isIdentity()
    fun forChannel(channel: Int): ToneCurve = when (channel) {
        1 -> red
        2 -> green
        3 -> blue
        else -> rgb
    }
    fun withChannel(channel: Int, curve: ToneCurve): CurveSet = when (channel) {
        1 -> copy(red = curve)
        2 -> copy(green = curve)
        3 -> copy(blue = curve)
        else -> copy(rgb = curve)
    }

    companion object {
        fun fromJson(o: org.json.JSONObject?): CurveSet = run {
            if (o == null) CurveSet()
            else CurveSet(
                rgb = o.optJSONObject("rgb")?.toToneCurve() ?: ToneCurve.EMPTY,
                red = o.optJSONObject("red")?.toToneCurve() ?: ToneCurve.EMPTY,
                green = o.optJSONObject("green")?.toToneCurve() ?: ToneCurve.EMPTY,
                blue = o.optJSONObject("blue")?.toToneCurve() ?: ToneCurve.EMPTY,
            )
        }

        private fun org.json.JSONObject.toToneCurve(): ToneCurve {
            val arr = optJSONArray("p") ?: return ToneCurve.EMPTY
            val points = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    CurvePoint(it.optDouble("x", 0.0).toFloat(), it.optDouble("y", 0.0).toFloat())
                }
            }
            return if (points.size >= 2) ToneCurve(points) else ToneCurve.EMPTY
        }

        fun toJson(set: CurveSet): org.json.JSONObject = org.json.JSONObject()
            .put("rgb", curveToJson(set.rgb))
            .put("red", curveToJson(set.red))
            .put("green", curveToJson(set.green))
            .put("blue", curveToJson(set.blue))

        private fun curveToJson(curve: ToneCurve): org.json.JSONObject {
            val arr = org.json.JSONArray()
            curve.points.forEach { arr.put(org.json.JSONObject().put("x", it.x.toDouble()).put("y", it.y.toDouble())) }
            return org.json.JSONObject().put("p", arr)
        }
    }
}

/** One colour grading wheel: a hue to push the range towards and how strongly. */
data class GradeWheel(val hue: Float = 0f, val amount: Float = 0f) {
    fun isIdentity() = amount == 0f
}

data class ColorGrading(
    val shadows: GradeWheel = GradeWheel(),
    val midtones: GradeWheel = GradeWheel(),
    val highlights: GradeWheel = GradeWheel(),
) {
    fun isIdentity() = shadows.isIdentity() && midtones.isIdentity() && highlights.isIdentity()

    companion object {
        fun toJson(grading: ColorGrading): org.json.JSONObject = org.json.JSONObject()
            .put("shadows", wheelToJson(grading.shadows))
            .put("midtones", wheelToJson(grading.midtones))
            .put("highlights", wheelToJson(grading.highlights))

        fun fromJson(o: org.json.JSONObject?): ColorGrading {
            if (o == null) return ColorGrading()
            fun wheel(key: String): GradeWheel {
                val item = o.optJSONObject(key) ?: return GradeWheel()
                return GradeWheel(
                    item.optDouble("hue", 0.0).toFloat(),
                    item.optDouble("amount", 0.0).toFloat(),
                )
            }
            return ColorGrading(wheel("shadows"), wheel("midtones"), wheel("highlights"))
        }

        private fun wheelToJson(wheel: GradeWheel): org.json.JSONObject = org.json.JSONObject()
            .put("hue", wheel.hue.toDouble())
            .put("amount", wheel.amount.toDouble())
    }
}

/** The six/eight colour bands of the mixer; only three values each, as in Lightroom. */
enum class MixerBand(val label: String, val hue: Float) {
    RED("Red", 0f),
    ORANGE("Orange", 30f),
    YELLOW("Yellow", 55f),
    GREEN("Green", 110f),
    AQUA("Aqua", 180f),
    BLUE("Blue", 220f),
    PURPLE("Purple", 275f),
    MAGENTA("Magenta", 320f),
}

data class BandAdjust(val hue: Float = 0f, val saturation: Float = 0f, val luminance: Float = 0f) {
    fun isIdentity() = hue == 0f && saturation == 0f && luminance == 0f
}

data class ColorMixer(val bands: Map<MixerBand, BandAdjust> = emptyMap()) {
    fun isIdentity() = bands.values.all { it.isIdentity() }
    operator fun get(band: MixerBand): BandAdjust = bands[band] ?: BandAdjust()
    fun with(band: MixerBand, adjust: BandAdjust): ColorMixer = copy(bands = bands + (band to adjust))

    companion object {
        fun toJson(mixer: ColorMixer): org.json.JSONObject {
            val obj = org.json.JSONObject()
            mixer.bands.forEach { (band, adjust) ->
                obj.put(
                    band.name,
                    org.json.JSONObject()
                        .put("h", adjust.hue.toDouble())
                        .put("s", adjust.saturation.toDouble())
                        .put("l", adjust.luminance.toDouble()),
                )
            }
            return obj
        }

        fun fromJson(o: org.json.JSONObject?): ColorMixer {
            if (o == null) return ColorMixer()
            val map = mutableMapOf<MixerBand, BandAdjust>()
            MixerBand.entries.forEach { band ->
                val item = o.optJSONObject(band.name) ?: return@forEach
                map[band] = BandAdjust(
                    item.optDouble("h", 0.0).toFloat(),
                    item.optDouble("s", 0.0).toFloat(),
                    item.optDouble("l", 0.0).toFloat(),
                )
            }
            return ColorMixer(map)
        }
    }
}