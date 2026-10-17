package com.jabs.lumen.engine

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File
import java.nio.ByteBuffer

object RawDecoder {

    private val EXTENSIONS = setOf(
        "dng", "cr2", "cr3", "nef", "nrw", "arw", "srf", "sr2", "raf", "orf",
        "rw2", "pef", "srw", "raw", "3fr", "iiq", "erf", "kdc", "dcr", "mrw", "rwl",
    )

    init {
        System.loadLibrary("lumenraw")
    }

    fun isRaw(file: File): Boolean = file.extension.lowercase() in EXTENSIONS

    /** Decodes the RAW to ARGB. With [halfSize], demosaics at half resolution (fast preview). */
    fun decode(file: File, halfSize: Boolean): Bitmap? {
        val data = nativeDecode(file.absolutePath, halfSize) ?: return null
        val width = data[0]
        val height = data[1]
        return Bitmap.createBitmap(data.copyOfRange(2, data.size), width, height, Bitmap.Config.ARGB_8888)
    }

    /** Returns the camera's embedded JPEG preview, honouring its EXIF orientation. */
    fun embeddedThumbnail(file: File): Bitmap? {
        val bytes = nativeExtractThumb(file.absolutePath) ?: return null
        return runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
    }

    private external fun nativeDecode(path: String, halfSize: Boolean): IntArray?

    private external fun nativeExtractThumb(path: String): ByteArray?
}
