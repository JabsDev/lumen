package com.jabs.lumen

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jabs.lumen.engine.ObjectSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Roda o modelo de verdade no aparelho e confere contra os números que o InteractiveSegmenter
 * oficial do MediaPipe produz para o mesmo toque (ver mp_reference.py). A tolerância cobre só a
 * posição sub-pixel do toque; o resto tem que bater.
 */
@RunWith(AndroidJUnit4::class)
class ObjectSegmenterTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** O lenna.jpg viaja no APK de teste, não no do app. */
    private val testContext get() = InstrumentationRegistry.getInstrumentation().context

    /** lenna.jpg em 512x512: a imagem em que os números de referência foram medidos. */
    private fun lennaPixels(): IntArray {
        val bitmap = testContext.assets.open("lenna.jpg").use { BitmapFactory.decodeStream(it) }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels
    }

    private fun area(mask: FloatArray) = mask.count { it > 0.5f } / mask.size.toFloat()

    @Test
    fun loadsTheBundledModel() {
        ObjectSegmenter.init(context)
        assertTrue("o modelo de objeto não carregou", ObjectSegmenter.available)
    }

    @Test
    fun selectsTheObjectUnderTheTap() {
        ObjectSegmenter.init(context)
        val mask = ObjectSegmenter.segment(
            lennaPixels(), 512, 512, 384, 384, listOf(0.47f to 0.16f), emptyList(),
        )
        requireNotNull(mask) { "o modelo devolveu null" }
        // MediaPipe oficial: 49,0% da grade para este toque (o chapéu).
        assertEquals(0.49f, area(mask), 0.06f)
    }

    @Test
    fun aSecondTapAddsTheOtherObject() {
        ObjectSegmenter.init(context)
        val pixels = lennaPixels()
        val hat = ObjectSegmenter.segment(pixels, 512, 512, 384, 384, listOf(0.47f to 0.16f), emptyList())!!
        val both = ObjectSegmenter.segment(
            pixels, 512, 512, 384, 384,
            listOf(0.47f to 0.16f, 0.88f to 0.06f), emptyList(),
        )!!
        assertTrue("o segundo toque não somou nada", area(both) > area(hat) + 0.02f)
    }

    @Test
    fun subtractingRemovesTheTappedArea() {
        ObjectSegmenter.init(context)
        val pixels = lennaPixels()
        val added = ObjectSegmenter.segment(pixels, 512, 512, 384, 384, listOf(0.47f to 0.16f), emptyList())!!
        val carved = ObjectSegmenter.segment(
            pixels, 512, 512, 384, 384,
            listOf(0.47f to 0.16f), listOf(0.47f to 0.16f),
        )!!
        assertTrue("subtrair o mesmo ponto não reduziu a máscara", area(carved) < area(added) * 0.5f)
    }
}
