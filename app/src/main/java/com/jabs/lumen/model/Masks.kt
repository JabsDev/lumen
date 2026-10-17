package com.jabs.lumen.model

import org.json.JSONArray
import org.json.JSONObject

/** The mask families. SELECT covers both object and background: they share the tap-to-grow
 * selection and only differ in the initial invert flag. */
enum class MaskType { LINEAR, RADIAL, BRUSH, SKY, SELECT, SUBJECT }

/** One tapped reference point for a content mask. Subtract points carve away from what the
 * positive ones grew, the way Lightroom's add/subtract pins refine a selection. */
data class MaskPoint(val x: Float, val y: Float, val subtract: Boolean = false) {
    fun toJson(): JSONObject = JSONObject()
        .put("x", x.toDouble())
        .put("y", y.toDouble())
        .put("subtract", subtract)

    companion object {
        fun fromJson(o: JSONObject): MaskPoint = MaskPoint(
            x = o.optDouble("x", 0.5).toFloat(),
            y = o.optDouble("y", 0.5).toFloat(),
            subtract = o.optBoolean("subtract", false),
        )
    }
}


data class Pt(val x: Float, val y: Float) {
    fun toJson(): JSONObject = JSONObject().put("x", x.toDouble()).put("y", y.toDouble())

    companion object {
        fun fromJson(o: JSONObject): Pt =
            Pt(o.optDouble("x", 0.5).toFloat(), o.optDouble("y", 0.5).toFloat())
    }
}

data class BrushStroke(
    val erase: Boolean = false,
    val size: Float = 0.06f,
    val points: List<Pt> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("erase", erase)
        .put("size", size.toDouble())
        .put("points", JSONArray(points.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject): BrushStroke {
            val pts = mutableListOf<Pt>()
            val arr = o.optJSONArray("points")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { pts.add(Pt.fromJson(it)) }
                }
            }
            return BrushStroke(
                erase = o.optBoolean("erase", false),
                size = o.optDouble("size", 0.06).toFloat(),
                points = pts,
            )
        }
    }
}

/** The local adjustments one mask carries. Clarity/texture stay global: they need a neighbourhood
 * that a masked pass cannot provide cheaply. */
data class MaskAdjust(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 0f,
) {
    fun isIdentity(): Boolean =
        exposure == 0f && contrast == 0f && highlights == 0f && shadows == 0f &&
            whites == 0f && blacks == 0f && temperature == 0f && tint == 0f && saturation == 0f

    fun toJson(): JSONObject = JSONObject()
        .put("exposure", exposure.toDouble())
        .put("contrast", contrast.toDouble())
        .put("highlights", highlights.toDouble())
        .put("shadows", shadows.toDouble())
        .put("whites", whites.toDouble())
        .put("blacks", blacks.toDouble())
        .put("temperature", temperature.toDouble())
        .put("tint", tint.toDouble())
        .put("saturation", saturation.toDouble())

    companion object {
        fun fromJson(o: JSONObject?): MaskAdjust {
            if (o == null) return MaskAdjust()
            return MaskAdjust(
                exposure = o.optDouble("exposure", 0.0).toFloat(),
                contrast = o.optDouble("contrast", 0.0).toFloat(),
                highlights = o.optDouble("highlights", 0.0).toFloat(),
                shadows = o.optDouble("shadows", 0.0).toFloat(),
                whites = o.optDouble("whites", 0.0).toFloat(),
                blacks = o.optDouble("blacks", 0.0).toFloat(),
                temperature = o.optDouble("temperature", 0.0).toFloat(),
                tint = o.optDouble("tint", 0.0).toFloat(),
                saturation = o.optDouble("saturation", 0.0).toFloat(),
            )
        }
    }
}

/**
 * One mask. Every coordinate is normalised 0..1 in image space (before rotation/flip), so masks
 * survive resize and travel with the photo the way global edits do.
 */
data class Mask(
    val id: Long = 0L,
    val type: MaskType = MaskType.LINEAR,
    val name: String = "",
    val enabled: Boolean = true,
    val invert: Boolean = false,
    val opacity: Float = 1f,
    val feather: Float = 0.5f,
    val angle: Float = 0f,
    val position: Float = 0.5f,
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    val rx: Float = 0.35f,
    val ry: Float = 0.25f,
    val rotation: Float = 0f,
    val brushSize: Float = 0.06f,
    val strokes: List<BrushStroke> = emptyList(),
    val seedX: Float = 0.5f,
    val seedY: Float = 0.5f,
    val tolerance: Float = 0.18f,
    /** Tapped points for a SELECT mask; when empty, the single seed above is used. */
    val points: List<MaskPoint> = emptyList(),
    val adjust: MaskAdjust = MaskAdjust(),
) {
    fun displayName(): String = name.ifEmpty {
        when (type) {
            MaskType.LINEAR -> "Linear"
            MaskType.RADIAL -> "Radial"
            MaskType.BRUSH -> "Pincel"
            MaskType.SKY -> "Céu"
            MaskType.SELECT -> if (invert) "Fundo" else "Objeto"
            MaskType.SUBJECT -> if (invert) "Fundo (pessoa)" else "Pessoa"
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type.name)
        .put("name", name)
        .put("enabled", enabled)
        .put("invert", invert)
        .put("opacity", opacity.toDouble())
        .put("feather", feather.toDouble())
        .put("angle", angle.toDouble())
        .put("position", position.toDouble())
        .put("cx", cx.toDouble())
        .put("cy", cy.toDouble())
        .put("rx", rx.toDouble())
        .put("ry", ry.toDouble())
        .put("rotation", rotation.toDouble())
        .put("brushSize", brushSize.toDouble())
        .put("strokes", JSONArray(strokes.map { it.toJson() }))
        .put("seedX", seedX.toDouble())
        .put("seedY", seedY.toDouble())
        .put("tolerance", tolerance.toDouble())
        .put("points", JSONArray(points.map { it.toJson() }))
        .put("adjust", adjust.toJson())

    companion object {
        fun fromJson(o: JSONObject): Mask {
            val strokes = mutableListOf<BrushStroke>()
            val arr = o.optJSONArray("strokes")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { strokes.add(BrushStroke.fromJson(it)) }
                }
            }
            val points = mutableListOf<MaskPoint>()
            val pointArr = o.optJSONArray("points")
            if (pointArr != null) {
                for (i in 0 until pointArr.length()) {
                    pointArr.optJSONObject(i)?.let { points.add(MaskPoint.fromJson(it)) }
                }
            }
            return Mask(
                id = o.optLong("id", 0L),
                type = runCatching { MaskType.valueOf(o.optString("type", "LINEAR")) }.getOrDefault(MaskType.LINEAR),
                name = o.optString("name", ""),
                enabled = o.optBoolean("enabled", true),
                invert = o.optBoolean("invert", false),
                opacity = o.optDouble("opacity", 1.0).toFloat(),
                feather = o.optDouble("feather", 0.5).toFloat(),
                angle = o.optDouble("angle", 0.0).toFloat(),
                position = o.optDouble("position", 0.5).toFloat(),
                cx = o.optDouble("cx", 0.5).toFloat(),
                cy = o.optDouble("cy", 0.5).toFloat(),
                rx = o.optDouble("rx", 0.35).toFloat(),
                ry = o.optDouble("ry", 0.25).toFloat(),
                rotation = o.optDouble("rotation", 0.0).toFloat(),
                brushSize = o.optDouble("brushSize", 0.06).toFloat(),
                strokes = strokes,
                seedX = o.optDouble("seedX", 0.5).toFloat(),
                seedY = o.optDouble("seedY", 0.5).toFloat(),
                tolerance = o.optDouble("tolerance", 0.18).toFloat(),
                points = points,
                adjust = MaskAdjust.fromJson(o.optJSONObject("adjust")),
            )
        }

        fun toJsonArray(masks: List<Mask>): JSONArray = JSONArray(masks.map { it.toJson() })

        fun fromJsonArray(arr: JSONArray?): List<Mask> {
            if (arr == null) return emptyList()
            val out = mutableListOf<Mask>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(fromJson(it)) }
            }
            return out
        }
    }
}
