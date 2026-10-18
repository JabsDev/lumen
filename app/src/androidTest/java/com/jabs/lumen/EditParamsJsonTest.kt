package com.jabs.lumen

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jabs.lumen.model.EditParams
import com.jabs.lumen.model.Preset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Os campos novos (nitidez, ruído e deslocamento cromático) precisam sobreviver ao JSON e as fotos
 * antigas — sem esses campos — precisam abrir sem alteração.
 */
@RunWith(AndroidJUnit4::class)
class EditParamsJsonTest {

    @Test
    fun theNewDetailFieldsRoundTripThroughJson() {
        val original = EditParams(
            sharpness = 0.4f,
            sharpenRadius = 0.6f,
            noiseLuminance = 0.3f,
            noiseColor = 0.2f,
            noiseDetail = 0.7f,
            chromaShiftRed = 0.006f,
            chromaShiftBlue = -0.004f,
        )
        val restored = EditParams.fromJson(original.toJson())
        assertEquals(original, restored)
    }

    @Test
    fun anOldJsonWithoutTheNewFieldsStillLoads() {
        // A photo saved before the detail controls existed: only the historical keys are present.
        val legacy = EditParams(exposure = 0.5f, contrast = 0.2f, chromaRed = -0.3f)
        val json = legacy.toJson()
        listOf("sharpness", "sharpenRadius", "noiseLuminance", "noiseColor", "noiseDetail", "chromaShiftRed", "chromaShiftBlue")
            .forEach { json.remove(it) }
        val restored = EditParams.fromJson(json)
        assertEquals(legacy, restored)
        assertEquals(0f, restored.sharpness, 0f)
        assertEquals(0.5f, restored.noiseDetail, 0f)
    }

    @Test
    fun theCreativePresetsCarryTheNewControls() {
        assertTrue(Preset.RETRO_FRANJA.params.chromaShiftRed != 0f)
        assertTrue(Preset.NITIDO.params.sharpness > 0f)
    }
}
