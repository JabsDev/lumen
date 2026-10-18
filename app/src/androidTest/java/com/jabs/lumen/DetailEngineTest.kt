package com.jabs.lumen

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jabs.lumen.engine.DeviceProfile
import com.jabs.lumen.engine.ImageEngine
import com.jabs.lumen.engine.Orientation
import com.jabs.lumen.model.EditParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Cobre o motor de detalhe (nitidez e redução de ruído) e a franja cromática não radial. As
 * métricas são medidas em janela sobre imagens sintéticas, para o resultado ser previsível.
 */
@RunWith(AndroidJUnit4::class)
class DetailEngineTest {

    private fun pixels(bmp: Bitmap): IntArray {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return px
    }

    private fun luma(px: IntArray): FloatArray = FloatArray(px.size) { i ->
        val c = px[i]
        (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)) / 255f
    }

    /** Mean absolute difference between horizontal neighbours: a simple detail/grain meter. */
    private fun localContrast(bmp: Bitmap): Double {
        val px = pixels(bmp)
        val l = luma(px)
        val w = bmp.width
        var sum = 0.0
        var n = 0
        for (y in 0 until bmp.height) {
            for (x in 1 until w) {
                sum += abs(l[y * w + x] - l[y * w + x - 1])
                n++
            }
        }
        return sum / n
    }

    private fun image(w: Int, h: Int, f: (Int, Int) -> Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            val px = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) px[y * w + x] = f(x, y)
            setPixels(px, 0, w, 0, 0, w, h)
        }

    private fun argb(r: Int, g: Int, b: Int) = Color.argb(255, r, g, b)

    /** A sine grating: rich detail, so sharpening and softening are easy to measure. */
    private fun grating(w: Int, h: Int): Bitmap = image(w, h) { x, _ ->
        val v = (128 + 100 * kotlin.math.sin(2.0 * Math.PI * x / 8.0)).toInt().coerceIn(0, 255)
        argb(v, v, v)
    }

    /** Grey with per-pixel, per-channel randomness: colour and luminance grain. */
    private fun noisy(w: Int, h: Int, seed: Int): Bitmap {
        var s = seed
        fun next(): Int {
            s = s * 1103515245 + 12345
            return (s ushr 16) and 0xFF
        }
        return image(w, h) { _, _ ->
            val n = next()
            argb(
                (128 + (n - 128) * 0.4f).toInt().coerceIn(0, 255),
                (128 + (next() - 128) * 0.4f).toInt().coerceIn(0, 255),
                (128 + (next() - 128) * 0.4f).toInt().coerceIn(0, 255),
            )
        }
    }

    @Test
    fun inertDetailFieldsDoNotChangeTheOutput() {
        // sharpenRadius only matters with sharpness, noiseDetail only with noise: with both amounts
        // at zero the picture must be byte-for-byte what it was before these controls existed.
        val src = grating(96, 96)
        val base = EditParams(exposure = 0.3f, contrast = 0.2f, clarity = 0.25f, texture = 0.2f, saturation = 0.3f)
        val plain = ImageEngine.render(src, base)
        val withInertFields = ImageEngine.render(src, base.copy(sharpenRadius = 1f, noiseDetail = 1f))
        assertTrue("o detalhe inerte mudou os pixels", plain.sameAs(withInertFields))
    }

    @Test
    fun sharpeningAddsAndSofteningRemovesLocalContrast() {
        val src = grating(96, 96)
        val none = localContrast(ImageEngine.render(src, EditParams()))
        val sharp = localContrast(ImageEngine.render(src, EditParams(sharpness = 1f, sharpenRadius = 0.5f)))
        val soft = localContrast(ImageEngine.render(src, EditParams(sharpness = -1f, sharpenRadius = 0.5f)))
        assertTrue("nitidez não aumentou o contraste local ($none -> $sharp)", sharp > none)
        assertTrue("suavização não reduziu o contraste local ($none -> $soft)", soft < none)
    }

    @Test
    fun luminanceNoiseReductionLowersGrain() {
        val src = noisy(96, 96, 7)
        val none = localContrast(ImageEngine.render(src, EditParams()))
        val denoised = localContrast(ImageEngine.render(src, EditParams(noiseLuminance = 1f, noiseDetail = 0.2f)))
        assertTrue("o NR de luminância não reduziu o grão ($none -> $denoised)", denoised < none)
    }

    @Test
    fun colorNoiseReductionPullsTheChannelsTogether() {
        val src = noisy(96, 96, 11)
        fun meanChannelSpread(bmp: Bitmap): Double {
            val px = pixels(bmp)
            var sum = 0.0
            for (c in px) {
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                sum += (abs(r - g) + abs(g - b) + abs(r - b)) / 3.0
            }
            return sum / px.size
        }
        val none = meanChannelSpread(ImageEngine.render(src, EditParams()))
        val denoised = meanChannelSpread(ImageEngine.render(src, EditParams(noiseColor = 1f, noiseDetail = 0.2f)))
        assertTrue("o NR de cor não aproximou os canais ($none -> $denoised)", denoised < none)
    }

    @Test
    fun theUniformChromaShiftMovesTheRedChannel() {
        val w = 64
        val src = image(w, 16) { x, _ -> if (x < w / 2) argb(255, 0, 0) else argb(0, 0, 255) }
        val boundary = w / 2

        // The red edge sits at the boundary; a positive shift drags the red channel leftwards.
        val shifted = ImageEngine.render(src, EditParams(chromaShiftRed = 0.02f))
        val shiftedPx = pixels(shifted)
        assertEquals(0, (shiftedPx[8 * w + boundary - 1] shr 16) and 0xFF)

        // A negative shift drags it rightwards, so the boundary pixel recovers the red.
        val back = ImageEngine.render(src, EditParams(chromaShiftRed = -0.02f))
        assertEquals(255, (pixels(back)[8 * w + boundary] shr 16) and 0xFF)
    }

    /** Writes [bmp] to the app cache as a JPEG and stamps it with an EXIF orientation. */
    private fun exifJpeg(bmp: Bitmap, orientation: Int, name: String): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.cacheDir, name)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file
    }

    /**
     * The 1:1 detail reads stored pixels through [android.graphics.BitmapRegionDecoder], which does
     * not apply EXIF the way `ImageDecoder` (the preview) does. Without the fix a turned photo
     * opened its 1:1 view on its side, so the crop must come back already upright.
     */
    @Test
    fun theRegionDecodeAppliesTheExifQuarterTurn() {
        // Stored landscape, red on top and blue below; EXIF 6 turns it 90° clockwise to show, so
        // the upright photo is portrait with the red moved to the right.
        val src = image(64, 32) { _, y -> if (y < 16) argb(255, 0, 0) else argb(0, 0, 255) }
        val file = exifJpeg(src, ExifInterface.ORIENTATION_ROTATE_90, "exif_rotate_90.jpg")

        val o = ImageEngine.orientation(file)
        assertEquals("a orientação EXIF não foi lida", Orientation(1, false), o)
        // The reported size is the upright, transposed one.
        assertEquals(32 to 64, ImageEngine.dimensions(file))

        val crop = ImageEngine.decodeRegion(file, Rect(0, 0, 32, 64), 32, 64, null, o)
            ?: error("o recorte 1:1 saiu nulo")
        assertEquals(32, crop.width)
        assertEquals(64, crop.height)

        val px = pixels(crop)
        val row = 32
        fun redAt(x: Int) = ((px[row * 32 + x] shr 16) and 0xFF) > 128
        assertTrue("o lado direito deveria ser vermelho (girado)", redAt(28))
        assertFalse("o lado esquerdo deveria ser azul (girado)", redAt(4))
    }

    @Test
    fun theRegionDecodeAppliesAnExifMirror() {
        val src = image(64, 32) { x, _ -> if (x < 32) argb(255, 0, 0) else argb(0, 0, 255) }
        val file = exifJpeg(src, ExifInterface.ORIENTATION_FLIP_HORIZONTAL, "exif_flip_h.jpg")

        val o = ImageEngine.orientation(file)
        assertEquals(Orientation(0, true), o)
        // A mirror never transposes.
        assertEquals(64 to 32, ImageEngine.dimensions(file))

        val crop = ImageEngine.decodeRegion(file, Rect(0, 0, 64, 32), 64, 32, null, o)
            ?: error("o recorte 1:1 saiu nulo")
        val px = pixels(crop)
        val row = 16
        fun redAt(x: Int) = ((px[row * 64 + x] shr 16) and 0xFF) > 128
        assertTrue("o lado direito deveria ser vermelho (espelhado)", redAt(60))
        assertFalse("o lado esquerdo deveria ser azul (espelhado)", redAt(4))
    }

    @Test
    fun theScreenPreviewNeverExceedsItsMemoryBudget() {
        for (heap in intArrayOf(128, 256, 512, 1024)) {
            val size = DeviceProfile.screenPreviewSize(2400, heap)
            val budget = sqrt((heap.toLong() * 1024L * 1024L / DeviceProfile.BYTES_PER_PIXEL) / 3.0).toInt()
            assertTrue("heap $heap: $size acima do teto $budget", size <= budget.coerceAtLeast(1024))
        }
        // A huge display cannot push the settled preview past the hard ceiling.
        assertTrue(DeviceProfile.screenPreviewSize(10_000, 4096) <= 3840)
    }
}
