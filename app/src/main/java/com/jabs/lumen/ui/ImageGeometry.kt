package com.jabs.lumen.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.Size

/**
 * Turns a point from the displayed (turned and mirrored) preview back into the normalised image
 * space where masks and the 1:1 viewport are stored, so they stay on the part of the photo they
 * were drawn over. Shared by the editor and the view-model, which must agree exactly.
 */
internal fun toImageSpace(x: Float, y: Float, rotation: Int, flip: Boolean): Pair<Float, Float> {
    val quarter = ((rotation % 4) + 4) % 4
    var nx = x
    var ny = y
    when (quarter) {
        1 -> { nx = y; ny = 1f - x }
        2 -> { nx = 1f - x; ny = 1f - y }
        3 -> { nx = 1f - y; ny = x }
    }
    if (flip) nx = 1f - nx
    return nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
}

/** The size of the image after the quarter turn (a mirror never changes it). */
internal fun orientedSize(w: Int, h: Int, rotation: Int): Size {
    val quarter = ((rotation % 4) + 4) % 4
    return if (quarter % 2 == 1) Size(h, w) else Size(w, h)
}

/** Gives a bitmap built in image space the same turn and mirror the preview is shown with. */
internal fun oriented(src: Bitmap, rotation: Int, flip: Boolean): Bitmap {
    val quarter = ((rotation % 4) + 4) % 4
    if (quarter == 0 && !flip) return src
    val w = src.width
    val h = src.height
    val outW = if (quarter % 2 == 1) h else w
    val outH = if (quarter % 2 == 1) w else h
    val m = Matrix()
    if (flip) m.postScale(-1f, 1f, w / 2f, h / 2f)
    m.postRotate(90f * quarter, w / 2f, h / 2f)
    m.postTranslate((outW - w) / 2f, (outH - h) / 2f)
    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(src, m, null)
    return out
}
