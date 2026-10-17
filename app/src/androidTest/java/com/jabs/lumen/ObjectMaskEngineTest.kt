package com.jabs.lumen

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jabs.lumen.engine.MaskEngine
import com.jabs.lumen.engine.ObjectSegmenter
import com.jabs.lumen.model.Mask
import com.jabs.lumen.model.MaskPoint
import com.jabs.lumen.model.MaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cobre a cola: o chip "Objeto" entra pelo MaskEngine.rasterize e sai do modelo. O valor esperado
 * (49% da grade para um toque no chapéu) é o mesmo que o MediaPipe oficial devolve.
 */
@RunWith(AndroidJUnit4::class)
class ObjectMaskEngineTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx get() = InstrumentationRegistry.getInstrumentation().context

    private fun lennaPixels(): IntArray {
        val bitmap = testCtx.assets.open("lenna.jpg").use { BitmapFactory.decodeStream(it) }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels
    }

    private fun hat(invert: Boolean = false) =
        Mask(type = MaskType.SELECT, invert = invert, points = listOf(MaskPoint(0.47f, 0.16f)))

    @Test
    fun theObjectChipFillsTheTappedObject() {
        ObjectSegmenter.init(ctx)
        val grid = MaskEngine.rasterize(hat(), lennaPixels(), 512, 512)
        val area = grid.data.count { it > 0.5f } / grid.data.size.toFloat()
        assertTrue("a máscara ficou quase vazia ($area)", area > 0.2f)
        assertEquals(0.49f, area, 0.10f)
    }

    @Test
    fun theBackgroundChipIsExactlyTheInverse() {
        val pixels = lennaPixels()
        val front = MaskEngine.rasterize(hat(), pixels, 512, 512)
        val back = MaskEngine.rasterize(hat(invert = true), pixels, 512, 512)
        for (i in front.data.indices) {
            assertEquals("célula $i", 1f, front.data[i] + back.data[i], 1e-3f)
        }
    }
}
