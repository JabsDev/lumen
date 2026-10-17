package com.jabs.lumen.model

import org.json.JSONObject

data class EditParams(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val clarity: Float = 0f,
    val texture: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val vignette: Float = 0f,
    val distortion: Float = 0f,
    val chromaRed: Float = 0f,
    val chromaBlue: Float = 0f,
    val curves: CurveSet = CurveSet(),
    val grading: ColorGrading = ColorGrading(),
    val mixer: ColorMixer = ColorMixer(),
    val rotation: Int = 0,
    val flipHorizontal: Boolean = false,
    val masks: List<Mask> = emptyList(),
    /** Bundled .cube look applied over everything else, or "" for none. */
    val lutId: String = "",
    /** How much of the LUT to keep, 0..1. */
    val lutAmount: Float = 1f,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("exposure", exposure.toDouble())
        .put("contrast", contrast.toDouble())
        .put("highlights", highlights.toDouble())
        .put("shadows", shadows.toDouble())
        .put("whites", whites.toDouble())
        .put("blacks", blacks.toDouble())
        .put("clarity", clarity.toDouble())
        .put("texture", texture.toDouble())
        .put("temperature", temperature.toDouble())
        .put("tint", tint.toDouble())
        .put("saturation", saturation.toDouble())
        .put("vibrance", vibrance.toDouble())
        .put("vignette", vignette.toDouble())
        .put("distortion", distortion.toDouble())
        .put("chromaRed", chromaRed.toDouble())
        .put("chromaBlue", chromaBlue.toDouble())
        .put("rotation", rotation)
        .put("flipHorizontal", flipHorizontal)
        .put("lutId", lutId)
        .put("lutAmount", lutAmount.toDouble())
        .put("masks", Mask.toJsonArray(masks))
        .put("curves", CurveSet.toJson(curves))
        .put("grading", ColorGrading.toJson(grading))
        .put("mixer", ColorMixer.toJson(mixer))

    companion object {
        fun fromJson(o: JSONObject?): EditParams {
            if (o == null) return EditParams()
            return EditParams(
                exposure = o.optDouble("exposure", 0.0).toFloat(),
                contrast = o.optDouble("contrast", 0.0).toFloat(),
                highlights = o.optDouble("highlights", 0.0).toFloat(),
                shadows = o.optDouble("shadows", 0.0).toFloat(),
                whites = o.optDouble("whites", 0.0).toFloat(),
                blacks = o.optDouble("blacks", 0.0).toFloat(),
                clarity = o.optDouble("clarity", 0.0).toFloat(),
                texture = o.optDouble("texture", 0.0).toFloat(),
                temperature = o.optDouble("temperature", 0.0).toFloat(),
                tint = o.optDouble("tint", 0.0).toFloat(),
                saturation = o.optDouble("saturation", 0.0).toFloat(),
                vibrance = o.optDouble("vibrance", 0.0).toFloat(),
                vignette = o.optDouble("vignette", 0.0).toFloat(),
                distortion = o.optDouble("distortion", 0.0).toFloat(),
                chromaRed = o.optDouble("chromaRed", 0.0).toFloat(),
                chromaBlue = o.optDouble("chromaBlue", 0.0).toFloat(),
                rotation = o.optInt("rotation", 0),
                flipHorizontal = o.optBoolean("flipHorizontal", false),
                lutId = o.optString("lutId", ""),
                lutAmount = o.optDouble("lutAmount", 1.0).toFloat(),
                masks = Mask.fromJsonArray(o.optJSONArray("masks")),
                curves = CurveSet.fromJson(o.optJSONObject("curves")),
                grading = ColorGrading.fromJson(o.optJSONObject("grading")),
                mixer = ColorMixer.fromJson(o.optJSONObject("mixer")),
            )
        }
    }
}

enum class Preset(val label: String, val params: EditParams) {
    NATURAL("Natural", EditParams(contrast = 0.1f, vibrance = 0.15f)),
    VIVID("Vívido", EditParams(contrast = 0.2f, saturation = 0.2f, vibrance = 0.3f, shadows = 0.2f)),
    WARM("Quente", EditParams(temperature = 0.35f, tint = 0.05f, vibrance = 0.1f)),
    COOL("Frio", EditParams(temperature = -0.3f, saturation = -0.05f)),
    FILM("Filme", EditParams(contrast = -0.15f, highlights = -0.25f, shadows = 0.2f, temperature = 0.1f, saturation = -0.15f, vignette = 0.3f)),
    MONO("P&B", EditParams(saturation = -1f, contrast = 0.25f)),
}
