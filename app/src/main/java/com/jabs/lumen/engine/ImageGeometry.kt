package com.jabs.lumen.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Size
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How a file's EXIF orientation turns its stored raster upright, in the same convention the editor
 * uses for its own rotate/mirror: the horizontal mirror happens first, then [quarterTurns] quarter
 * turns clockwise. [swapsAxes] is true for an odd quarter turn, which exchanges width and height.
 *
 * `ImageDecoder` applies this on its own, but `BitmapRegionDecoder` and `BitmapFactory` do not, so
 * everything that reads the file's pixels directly has to carry this along or the 1:1 crop comes
 * back sideways.
 */
data class Orientation(val quarterTurns: Int, val flipHorizontal: Boolean) {
    val swapsAxes: Boolean get() = ((quarterTurns % 4) + 4) % 4 % 2 == 1
    val isIdentity: Boolean get() = ((quarterTurns % 4) + 4) % 4 == 0 && !flipHorizontal

    companion object {
        val NORMAL = Orientation(0, false)
    }
}

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

/**
 * Maps a rectangle from upright (EXIF-applied) space into the file's stored raster, corner by
 * corner — the inverse of [oriented]. Region decoders read stored pixels, so the visible
 * window has to be rotated and mirrored back before it can be requested.
 */
internal fun uprightRectToRaw(
    rect: Rect,
    uprightWidth: Int,
    uprightHeight: Int,
    rawWidth: Int,
    rawHeight: Int,
    orientation: Orientation,
): Rect {
    if (orientation.isIdentity) return rect
    val corners = listOf(
        rect.left.toFloat() / uprightWidth to rect.top.toFloat() / uprightHeight,
        rect.right.toFloat() / uprightWidth to rect.top.toFloat() / uprightHeight,
        rect.right.toFloat() / uprightWidth to rect.bottom.toFloat() / uprightHeight,
        rect.left.toFloat() / uprightWidth to rect.bottom.toFloat() / uprightHeight,
    )
    var minX = 1f
    var minY = 1f
    var maxX = 0f
    var maxY = 0f
    for ((u, v) in corners) {
        val (x, y) = toImageSpace(u, v, orientation.quarterTurns, orientation.flipHorizontal)
        minX = min(minX, x)
        maxX = max(maxX, x)
        minY = min(minY, y)
        maxY = max(maxY, y)
    }
    return Rect(
        (minX * rawWidth).roundToInt().coerceIn(0, rawWidth - 1),
        (minY * rawHeight).roundToInt().coerceIn(0, rawHeight - 1),
        (maxX * rawWidth).roundToInt().coerceIn(1, rawWidth),
        (maxY * rawHeight).roundToInt().coerceIn(1, rawHeight),
    )
}
