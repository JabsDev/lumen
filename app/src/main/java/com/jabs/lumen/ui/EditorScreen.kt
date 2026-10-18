package com.jabs.lumen.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.jabs.lumen.engine.MaskEngine
import com.jabs.lumen.engine.oriented
import com.jabs.lumen.engine.toImageSpace
import com.jabs.lumen.model.BrushStroke
import com.jabs.lumen.model.MaskType
import com.jabs.lumen.model.MaskPoint
import com.jabs.lumen.model.Pt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.jabs.lumen.data.Photo
import com.jabs.lumen.model.ColorGrading
import com.jabs.lumen.model.ColorMixer
import com.jabs.lumen.model.EditParams
import com.jabs.lumen.model.MixerBand
import com.jabs.lumen.model.Preset
import com.jabs.lumen.model.ToneCurve
import kotlin.math.max
import kotlin.math.roundToInt

private val CATEGORIES = listOf("Luz", "Curvas", "Cor", "Efeitos", "Detalhe", "Lente", "Geometria", "Máscara")

/** Index of the "Máscara" tab, where one finger paints instead of panning or zooming. */
private const val MASK_TAB = 7

/** Tab that holds sharpening and noise reduction. */
private const val DETAIL_TAB = 4

/** Zoom past which the native 1:1 detail is worth building. */
private const val DETAIL_ZOOM_THRESHOLD = 1.5f

/** How far the picture can be pinched in, and how far a double tap jumps in one step. */
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 8f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** Size of the little histogram that sits on the corner of the photo. */
private val HISTOGRAM_WIDTH = 144.dp
private val HISTOGRAM_HEIGHT = 46.dp
private val HISTOGRAM_MARGIN = 8.dp

@Composable
fun EditorScreen(vm: LumenViewModel, photo: Photo) {
    val params by vm.params.collectAsState()
    val preview by vm.preview.collectAsState()
    val original by vm.original.collectAsState()
    val showOriginal by vm.showOriginal.collectAsState()
    val histogram by vm.histogram.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val canRedo by vm.canRedo.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Tapping the active tab again folds the panel away and hands the picture the whole screen;
    // the tabs themselves stay put at the bottom, the way they do in Lightroom.
    var panelOpen by rememberSaveable { mutableStateOf(true) }
    var maskUi by remember { mutableStateOf(MaskUiState()) }
    val shown = if (showOriginal) original else preview

    // The 1:1 detail: the native crop of the visible window, plus its unedited twin for compare.
    val detail by vm.detail.collectAsState()
    val detailOriginal by vm.detailOriginal.collectAsState()
    val detailRect by vm.detailRect.collectAsState()
    val detailReady by vm.detailReady.collectAsState()
    val detailVersion by vm.detailVersion.collectAsState()
    val nativeSize by vm.nativeSize.collectAsState()

    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // Zoom and pan of the preview. One finger paints while the mask panel is open, so zooming is
    // offered everywhere else and cleared on the way in, which keeps mask taps on the right pixel.
    var scale by remember(photo.id) { mutableFloatStateOf(MIN_ZOOM) }
    var panOffset by remember(photo.id) { mutableStateOf(Offset.Zero) }
    // Before/after split: the edited picture with the original revealed left of a draggable handle.
    var splitMode by remember(photo.id) { mutableStateOf(false) }
    var splitFraction by remember(photo.id) { mutableFloatStateOf(0.5f) }
    val maskEditing = panelOpen && tab == MASK_TAB
    val canZoom = !maskEditing
    LaunchedEffect(maskEditing) {
        if (maskEditing) {
            splitMode = false
            scale = MIN_ZOOM
            panOffset = Offset.Zero
        }
    }

    // Entering the split jumps to real pixels (1 native px = 1 screen px) so the compare is honest,
    // then re-centres. It cannot zoom, so the handle drag never fights a pan.
    val baseForFit = preview ?: original
    LaunchedEffect(splitMode) {
        if (splitMode && baseForFit != null && nativeSize != null && boxSize.width > 0) {
            val frame = fitFrame(boxSize, baseForFit.width, baseForFit.height)
            val nativeLong = max(nativeSize!!.width, nativeSize!!.height).toFloat()
            scale = (nativeLong / max(frame.width, frame.height)).coerceIn(MIN_ZOOM, MAX_ZOOM)
            panOffset = Offset.Zero
            splitFraction = 0.5f
        }
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val next = (scale * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        scale = next
        panOffset = clampOffset(panOffset + panChange, next, boxSize, preview ?: original)
    }

    // The visible window, in normalised image coordinates, is what the 1:1 detail is rendered from.
    val wantDetail = canZoom && (splitMode || scale > DETAIL_ZOOM_THRESHOLD)
    LaunchedEffect(wantDetail, scale, panOffset, boxSize, detailVersion, baseForFit) {
        if (!wantDetail || baseForFit == null || boxSize.width == 0 || boxSize.height == 0) return@LaunchedEffect
        val frame = fitFrame(boxSize, baseForFit.width, baseForFit.height)
        vm.requestDetail(visibleViewport(boxSize, frame, scale, panOffset))
    }


    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::close) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
            }
            Text(
                photo.displayName,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = vm::undo, enabled = canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Desfazer")
            }
            IconButton(onClick = vm::redo, enabled = canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Refazer")
            }
            IconButton(
                onClick = { splitMode = !splitMode },
            ) {
                Icon(
                    Icons.Filled.Compare,
                    contentDescription = if (splitMode) "Sair da divisão antes/depois" else "Comparar antes/depois",
                )
            }
            IconButton(onClick = vm::exportCurrent) {
                Icon(Icons.Filled.Download, contentDescription = "Exportar")
            }
        }

        val masksLive by rememberUpdatedState(params.masks)
        var overlayBmp by remember { mutableStateOf<Bitmap?>(null) }
        val maskSel = params.masks.firstOrNull { it.id == maskUi.selectedId }
        // The overlay shows the mask's shape, which never changes while an adjustment slider is
        // dragged — keying on the geometry alone keeps that drag from rebuilding it every frame.
        val maskGeom = maskSel?.copy(adjust = com.jabs.lumen.model.MaskAdjust())
        LaunchedEffect(maskEditing, maskUi.selectedId, maskUi.overlay, showOriginal, maskGeom, original, params.rotation, params.flipHorizontal) {
            val bmp = original
            overlayBmp = if (maskEditing && maskUi.overlay && !showOriginal && maskSel != null && bmp != null) {
                withContext(Dispatchers.IO) {
                    // Masks are painted before geometry, so the overlay is built the same way and
                    // only then turned to match what is on screen.
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    val grid = MaskEngine.rasterize(maskSel, px, bmp.width, bmp.height)
                    oriented(MaskEngine.overlayBitmap(grid), params.rotation, params.flipHorizontal)
                }
            } else null
        }

        fun handleMaskPoint(p: Offset, isStart: Boolean) {
            val id = maskUi.selectedId ?: return
            val m = masksLive.firstOrNull { it.id == id } ?: return
            val src = original ?: return
            // Fingers land on the displayed preview, which may be turned; masks live in image space.
            val (ptx, pty) = toImageSpace(p.x, p.y, params.rotation, params.flipHorizontal)
            val pt = Offset(ptx, pty)
            val aspect = src.width.toFloat() / src.height
            when (m.type) {
                MaskType.BRUSH -> {
                    vm.updateLive { ps ->
                        ps.copy(masks = ps.masks.map {
                            if (it.id != id) it
                            else if (isStart || it.strokes.isEmpty() || it.strokes.last().erase != maskUi.erasing) {
                                it.copy(
                                    strokes = (it.strokes + BrushStroke(
                                        erase = maskUi.erasing,
                                        size = it.brushSize,
                                        points = listOf(Pt(pt.x, pt.y)),
                                    )).takeLast(30),
                                )
                            } else {
                                val last = it.strokes.last()
                                it.copy(strokes = it.strokes.dropLast(1) + last.copy(points = (last.points + Pt(pt.x, pt.y)).takeLast(400)))
                            }
                        })
                    }
                }
                MaskType.LINEAR -> {
                    val a = Math.toRadians(m.angle.toDouble())
                    val nx = cos(a).toFloat()
                    val ny = sin(a).toFloat()
                    val halfDiag = sqrt(0.25f + 0.25f * aspect * aspect)
                    val t = ((pt.x - 0.5f) * aspect * nx + (pt.y - 0.5f) * ny) / halfDiag + 0.5f
                    val pos = t.coerceIn(0f, 1f)
                    vm.updateLive { ps ->
                        ps.copy(masks = ps.masks.map { if (it.id == id) it.copy(position = pos) else it })
                    }
                }
                MaskType.RADIAL -> {
                    vm.updateLive { ps ->
                        ps.copy(masks = ps.masks.map { if (it.id == id) it.copy(cx = pt.x, cy = pt.y) else it })
                    }
                }
                else -> {}
            }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 0.dp, vertical = 4.dp)
                .onSizeChanged { boxSize = it }
                .then(if (canZoom && !splitMode) Modifier.transformable(transformState) else Modifier)
                .pointerInput(maskEditing, maskUi.selectedId, maskUi.erasing) {
                    if (!maskEditing) return@pointerInput
                    detectTapGestures(onTap = { offset ->
                        val id = maskUi.selectedId
                        val m = id?.let { sid -> masksLive.firstOrNull { it.id == sid } }
                        val bmp = preview
                        if (id != null && m != null &&
                            (m.type == MaskType.SELECT || m.type == MaskType.SUBJECT) && bmp != null
                        ) {
                            toImageCoords(offset, boxSize, bmp.width, bmp.height)?.let { p ->
                                val (ix, iy) = toImageSpace(p.x, p.y, params.rotation, params.flipHorizontal)
                                val img = Offset(ix, iy)
                                // Each tap drops a pin: adds to the selection, or carves it away.
                                val pin = MaskPoint(img.x, img.y, maskUi.erasing)
                                vm.updateLive { ps ->
                                    ps.copy(masks = ps.masks.map {
                                        if (it.id == id) it.copy(points = it.points + pin) else it
                                    })
                                }
                                vm.commit()
                            }
                        }
                    })
                }
                .pointerInput(maskEditing, maskUi.selectedId, maskUi.erasing) {
                    if (!maskEditing) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            val bmp = preview
                            if (bmp != null) {
                                toImageCoords(offset, boxSize, bmp.width, bmp.height)?.let { handleMaskPoint(it, true) }
                            }
                        },
                        onDragEnd = { vm.commit() },
                        onDragCancel = {},
                        onDrag = { change, _ ->
                            change.consume()
                            val bmp = preview
                            if (bmp != null) {
                                toImageCoords(change.position, boxSize, bmp.width, bmp.height)?.let { handleMaskPoint(it, false) }
                            }
                        },
                    )
                }
                .pointerInput(maskEditing || splitMode) {
                    // On the picture: hold to peek at the original, double tap to zoom in and back out.
                    if (maskEditing || splitMode) return@pointerInput
                    detectTapGestures(
                        onDoubleTap = { pos ->
                            if (scale > MIN_ZOOM) {
                                scale = MIN_ZOOM
                                panOffset = Offset.Zero
                            } else {
                                val centre = Offset(boxSize.width / 2f, boxSize.height / 2f)
                                panOffset = clampOffset(
                                    (pos - centre) * (1f - DOUBLE_TAP_ZOOM),
                                    DOUBLE_TAP_ZOOM,
                                    boxSize,
                                    preview ?: original,
                                )
                                scale = DOUBLE_TAP_ZOOM
                            }
                        },
                        onLongPress = { vm.setShowOriginal(true) },
                        onPress = {
                            tryAwaitRelease()
                            vm.setShowOriginal(false)
                        },
                    )
                }
                .pointerInput(splitMode, boxSize, scale, panOffset, baseForFit) {
                    if (!splitMode) return@pointerInput
                    detectDragGestures { change, _ ->
                        change.consume()
                        val bmp = baseForFit
                        if (bmp != null && boxSize.width > 0) {
                            val frame = fitFrame(boxSize, bmp.width, bmp.height)
                            val cx = boxSize.width / 2f
                            // Undo the preview transform to land the handle where the finger is.
                            val innerX = (change.position.x - cx - panOffset.x) / scale + cx
                            splitFraction = ((innerX - frame.left) / frame.width).coerceIn(0.02f, 0.98f)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = panOffset.x
                    translationY = panOffset.y
                }
            ) {
                val base = shown
                base?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                val frame = base?.let { fitFrame(boxSize, it.width, it.height) }
                val rect = detailRect
                val patch = if (showOriginal) (detailOriginal ?: detail) else detail
                if (frame != null && rect != null && patch != null) {
                    // The sharp 1:1 patch, placed by its own rect so it stays aligned at any zoom.
                    DetailLayer(patch, frame, rect)
                    if (splitMode) {
                        detailOriginal?.let { originalPatch ->
                            Box(
                                Modifier.fillMaxSize().drawWithContent {
                                    clipRect(right = frame.left + splitFraction * frame.width) {
                                        this@drawWithContent.drawContent()
                                    }
                                }
                            ) { DetailLayer(originalPatch, frame, rect) }
                        }
                    }
                } else if (splitMode && base != null && frame != null) {
                    // No 1:1 yet: reveal the settled original on the left of the handle.
                    val before = if (showOriginal) null else original
                    if (before != null) {
                        Box(
                            Modifier.fillMaxSize().drawWithContent {
                                clipRect(right = frame.left + splitFraction * frame.width) {
                                    this@drawWithContent.drawContent()
                                }
                            }
                        ) {
                            Image(
                                bitmap = before.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                overlayBmp?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            val fitBitmap = baseForFit
            if (canZoom && (scale > MIN_ZOOM * 1.02f || !splitMode)) {
                Row(
                    Modifier.align(Alignment.BottomStart).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "%.1f×".format(scale),
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                            .clickable {
                                scale = MIN_ZOOM
                                panOffset = Offset.Zero
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (nativeSize != null && fitBitmap != null && boxSize.width > 0) {
                        Text(
                            "1:1",
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                                .clickable {
                                    val frame = fitFrame(boxSize, fitBitmap.width, fitBitmap.height)
                                    val nativeLong = max(nativeSize!!.width, nativeSize!!.height).toFloat()
                                    scale = (nativeLong / max(frame.width, frame.height))
                                        .coerceIn(MIN_ZOOM, MAX_ZOOM)
                                    panOffset = Offset.Zero
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            if (showOriginal) {
                Text(
                    "ORIGINAL",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (wantDetail) {
                // Tells the user the patch on screen is the real pixel, not an upscaled preview.
                Text(
                    if (detailReady) "detalhe 1:1" else "nítido…",
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (splitMode) {
                val handleX = if (fitBitmap == null || boxSize.width == 0) {
                    boxSize.width / 2f
                } else {
                    val frame = fitFrame(boxSize, fitBitmap.width, fitBitmap.height)
                    val cx = boxSize.width / 2f
                    (frame.left + splitFraction * frame.width - cx) * scale + cx + panOffset.x
                }
                Canvas(Modifier.fillMaxSize()) {
                    drawLine(
                        color = Color.White,
                        start = Offset(handleX, 0f),
                        end = Offset(handleX, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                    drawCircle(Color.White, 7.dp.toPx(), Offset(handleX, size.height / 2f))
                }
                Text(
                    "ANTES",
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "DEPOIS",
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            // The histogram rides on the photo itself, not on the box around it. With the panel
            // folded away the photo sits centred in a taller frame, and a plot pinned to the box
            // would drift off into the empty space around it. ContentScale.Fit draws the photo as
            // the box shrunk by the tighter of the two ratios and centred, so its frame is easy to
            // work out and the plot always lands on the picture.
            val photo = shown
            val frameRight: Float
            val frameTop: Float
            if (photo == null || boxSize.width == 0 || boxSize.height == 0) {
                frameRight = boxSize.width.toFloat()
                frameTop = 0f
            } else {
                val fit = (boxSize.width / photo.width.toFloat())
                    .coerceAtMost(boxSize.height / photo.height.toFloat())
                frameRight = (boxSize.width + photo.width * fit) / 2f
                frameTop = (boxSize.height - photo.height * fit) / 2f
            }
            val density = LocalDensity.current
            val histWidth = with(density) { HISTOGRAM_WIDTH.toPx() }
            val margin = with(density) { HISTOGRAM_MARGIN.toPx() }
            Histogram(
                histogram,
                Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        IntOffset(
                            (frameRight - histWidth - margin).roundToInt().coerceAtLeast(margin.roundToInt()),
                            (frameTop + margin).roundToInt().coerceAtLeast(margin.roundToInt()),
                        )
                    }
                    .size(HISTOGRAM_WIDTH, HISTOGRAM_HEIGHT),
            )
        }

        // The panel only exists while a tab is open: tapping the active tab again folds it away
        // and lets the picture grow into the space.
        if (panelOpen) {
            // The curve editor needs room and its own gestures: inside the scrolling panel it would
            // fight the scroll for every drag, so that tab gets a taller panel that does not scroll.
            val curvesTab = tab == 1
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(if (curvesTab) 304.dp else 228.dp)
                    .then(if (curvesTab) Modifier else Modifier.verticalScroll(rememberScrollState()))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                when (tab) {
                    0 -> {
                        Text(
                            "Predefinições",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(Preset.entries) { preset ->
                                AssistChip(onClick = { vm.applyPreset(preset) }, label = { Text(preset.label) })
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Adjust(vm, "Exposição", params.exposure, -2f..2f, { "%+.2f EV".format(it) }) { p, v -> p.copy(exposure = v) }
                        Adjust(vm, "Contraste", params.contrast, -1f..1f, ::percent) { p, v -> p.copy(contrast = v) }
                        Adjust(vm, "Realces", params.highlights, -1f..1f, ::percent) { p, v -> p.copy(highlights = v) }
                        Adjust(vm, "Sombras", params.shadows, -1f..1f, ::percent) { p, v -> p.copy(shadows = v) }
                        Adjust(vm, "Brancos", params.whites, -1f..1f, ::percent) { p, v -> p.copy(whites = v) }
                        Adjust(vm, "Pretos", params.blacks, -1f..1f, ::percent) { p, v -> p.copy(blacks = v) }
                        Adjust(vm, "Clareza", params.clarity, -1f..1f, ::percent) { p, v -> p.copy(clarity = v) }
                        Adjust(vm, "Textura", params.texture, -1f..1f, ::percent) { p, v -> p.copy(texture = v) }
                    }
                    1 -> CurveTab(vm, params, Modifier.weight(1f).fillMaxWidth())
                    2 -> ColorTab(vm, params)
                    3 -> {
                        LutSection(vm, params)
                        Adjust(vm, "Vinheta", params.vignette, -1f..1f, ::percent) { p, v -> p.copy(vignette = v) }
                    }
                    DETAIL_TAB -> DetailTab(vm, params)
                    5 -> {
                        Adjust(vm, "Distorção", params.distortion, -1f..1f, ::percent) { p, v -> p.copy(distortion = v) }
                        // Both fringe sliders are bidirectional: "−" corrects, "+" adds the fringe.
                        Adjust(vm, "Franja R/C (− reduz · + adiciona)", params.chromaRed, -1f..1f, ::percent) { p, v -> p.copy(chromaRed = v) }
                        Adjust(vm, "Franja A/A (− reduz · + adiciona)", params.chromaBlue, -1f..1f, ::percent) { p, v -> p.copy(chromaBlue = v) }
                        Adjust(vm, "Deslocar R/C", params.chromaShiftRed, -0.02f..0.02f, ::shiftPercent) { p, v -> p.copy(chromaShiftRed = v) }
                        Adjust(vm, "Deslocar A/A", params.chromaShiftBlue, -0.02f..0.02f, ::shiftPercent) { p, v -> p.copy(chromaShiftBlue = v) }
                    }
                    6 -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                vm.updateLive { it.copy(rotation = (it.rotation + 3) % 4) }
                                vm.commit()
                            }) { Text("Girar ↺") }
                            OutlinedButton(onClick = {
                                vm.updateLive { it.copy(rotation = (it.rotation + 1) % 4) }
                                vm.commit()
                            }) { Text("Girar ↻") }
                            OutlinedButton(onClick = {
                                vm.updateLive { it.copy(flipHorizontal = !it.flipHorizontal) }
                                vm.commit()
                            }) { Text("Espelhar") }
                        }
                    }
                    MASK_TAB -> MaskTab(vm, params, maskUi) { maskUi = it }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = vm::resetAll,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) { Text("Redefinir tudo") }
                }
            }
        }

        // The tab strip lives at the bottom and never moves; it doubles as the fold/unfold.
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(CATEGORIES) { i, title ->
                CategoryTab(
                    title = title,
                    selected = panelOpen && tab == i,
                    onClick = {
                        if (panelOpen && tab == i) panelOpen = false
                        else {
                            tab = i
                            panelOpen = true
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun CategoryTab(title: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

@Composable
private fun CurveTab(vm: LumenViewModel, params: EditParams, modifier: Modifier = Modifier) {
    var channel by rememberSaveable { mutableIntStateOf(0) }
    val curve = params.curves.forChannel(channel)
    Column(modifier) {
        CurveChannelRow(
            selected = channel,
            onSelect = { channel = it },
            onReset = {
                vm.updateLive { it.copy(curves = it.curves.withChannel(channel, ToneCurve.EMPTY)) }
                vm.commit()
            },
        )
        Spacer(Modifier.height(8.dp))
        CurveEditor(
            curve = curve,
            onChange = { updated ->
                vm.updateLive { it.copy(curves = it.curves.withChannel(channel, updated)) }
            },
            // The change is applied live while dragging and pushed to history on release.
            onCommit = vm::commit,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

@Composable
private fun ColorTab(vm: LumenViewModel, params: EditParams) {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    var band by remember { mutableStateOf(MixerBand.RED) }
    Column(Modifier.fillMaxWidth()) {
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            listOf("Básico", "Gradação", "Mistura").forEachIndexed { i, label ->
                SegmentedButton(
                    selected = sub == i,
                    onClick = { sub = i },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = 3),
                ) { Text(label) }
            }
        }
        when (sub) {
            0 -> {
                Adjust(vm, "Temperatura", params.temperature, -1f..1f, ::percent) { p, v -> p.copy(temperature = v) }
                Adjust(vm, "Matiz", params.tint, -1f..1f, ::percent) { p, v -> p.copy(tint = v) }
                Adjust(vm, "Saturação", params.saturation, -1f..1f, ::percent) { p, v -> p.copy(saturation = v) }
                Adjust(vm, "Vibração", params.vibrance, -1f..1f, ::percent) { p, v -> p.copy(vibrance = v) }
            }
            1 -> GradingPanel(
                shadows = params.grading.shadows,
                midtones = params.grading.midtones,
                highlights = params.grading.highlights,
                onChange = { index, wheel ->
                    vm.updateLive { p ->
                        p.copy(grading = when (index) {
                            0 -> p.grading.copy(shadows = wheel)
                            1 -> p.grading.copy(midtones = wheel)
                            else -> p.grading.copy(highlights = wheel)
                        })
                    }
                },
                onReset = {
                    vm.updateLive { it.copy(grading = ColorGrading()) }
                    vm.commit()
                },
                onCommit = vm::commit,
            )
            else -> ColorMixerPanel(
                mixer = params.mixer,
                selected = band,
                onSelectBand = { band = it },
                onChange = { target, adjust ->
                    vm.updateLive { it.copy(mixer = it.mixer.with(target, adjust)) }
                },
                onReset = {
                    vm.updateLive { it.copy(mixer = ColorMixer()) }
                    vm.commit()
                },
                onCommit = vm::commit,
            )
        }
        TextButton(
            onClick = vm::commit,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) { Text("Confirmar ajustes de cor") }
    }
}

/** The available .cube looks as a row of chips, with how much of the chosen one to keep. */
@Composable
private fun LutSection(vm: LumenViewModel, params: EditParams) {
    val options by vm.luts.collectAsState()
    val chosen = options.find { it.id == params.lutId }
    val pickLut = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importLut(uri)
    }
    Text(
        "LUT",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "none") {
            FilterChip(
                selected = params.lutId.isEmpty(),
                onClick = { if (params.lutId.isNotEmpty()) vm.selectLut("") },
                label = { Text("Sem LUT") },
            )
        }
        items(options, key = { it.id }) { entry ->
            FilterChip(
                selected = params.lutId == entry.id,
                onClick = { vm.selectLut(entry.id) },
                label = { Text(entry.name) },
            )
        }
        item(key = "import") {
            FilterChip(
                selected = false,
                onClick = { pickLut.launch(arrayOf("*/*")) },
                label = { Text("Importar…") },
                leadingIcon = {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
        }
    }
    if (params.lutId.isNotEmpty()) {
        Adjust(vm, "Força", params.lutAmount, 0f..1f, { "%d%%".format((it * 100f).roundToInt()) }, default = 1f) { p, v ->
            p.copy(lutAmount = v)
        }
        // Only looks the user brought in can go away; the bundled ones are part of the app.
        if (chosen?.user == true) {
            var confirming by remember { mutableStateOf(false) }
            TextButton(onClick = { confirming = true }) { Text("Remover LUT") }
            if (confirming) {
                AlertDialog(
                    onDismissRequest = { confirming = false },
                    title = { Text("Remover LUT") },
                    text = { Text("Remover \"${chosen.name}\" deste aparelho?") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirming = false
                            vm.removeLut(params.lutId)
                        }) { Text("Remover") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirming = false }) { Text("Cancelar") }
                    },
                )
            }
        }
    }
}

@Composable
private fun Adjust(
    vm: LumenViewModel,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    default: Float = 0f,
    set: (EditParams, Float) -> EditParams,
) {
    AdjustSlider(
        label = label,
        value = value,
        range = range,
        display = display,
        onValueChange = { v -> vm.updateLive { set(it, v) } },
        onValueChangeFinished = vm::commit,
        onReset = {
            vm.updateLive { set(it, default) }
            vm.commit()
        },
    )
}

private fun percent(v: Float): String = "%+d".format((v * 100f).roundToInt())

private fun zeroPercent(v: Float): String = "%d%%".format((v * 100f).roundToInt())

/** The uniform chroma shift reads best as a small percentage of the width. */
private fun shiftPercent(v: Float): String = "%+.2f%%".format(v * 100f)

/** Sharpening, its radius and the two noise-reduction controls, with a nudge to look at 1:1. */
@Composable
private fun DetailTab(vm: LumenViewModel, params: EditParams) {
    Adjust(vm, "Nitidez", params.sharpness, -1f..1f, ::percent) { p, v -> p.copy(sharpness = v) }
    Adjust(vm, "Raio", params.sharpenRadius, 0f..1f, { "%.1f".format(it) }) { p, v -> p.copy(sharpenRadius = v) }
    Adjust(vm, "Redução de ruído — Luminância", params.noiseLuminance, 0f..1f, ::zeroPercent) { p, v -> p.copy(noiseLuminance = v) }
    Adjust(vm, "Redução de ruído — Cor", params.noiseColor, 0f..1f, ::zeroPercent) { p, v -> p.copy(noiseColor = v) }
    Adjust(vm, "Preservar detalhe", params.noiseDetail, 0f..1f, ::zeroPercent, default = 0.5f) { p, v -> p.copy(noiseDetail = v) }
    Text(
        "Amplie até 1:1 para julgar nitidez e ruído.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** Maps a touch inside a ContentScale.Fit box to normalised image coordinates, or null outside. */
private fun toImageCoords(offset: Offset, box: IntSize, imgW: Int, imgH: Int): Offset? {
    if (box.width <= 0 || box.height <= 0 || imgW <= 0 || imgH <= 0) return null
    val aspect = imgW.toFloat() / imgH
    val boxAspect = box.width.toFloat() / box.height
    val (drawW, drawH, offX, offY) = if (boxAspect > aspect) {
        val h = box.height.toFloat()
        val w = h * aspect
        arrayOf(w, h, (box.width - w) / 2f, 0f)
    } else {
        val w = box.width.toFloat()
        val h = w / aspect
        arrayOf(w, h, 0f, (box.height - h) / 2f)
    }
    val nx = (offset.x - offX) / drawW
    val ny = (offset.y - offY) / drawH
    if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null
    return Offset(nx, ny)
}


/**
 * Stops a zoomed preview from being dragged off the frame. The picture is drawn to fit the box, so
 * at a given zoom it can only slide by the overhang the scaling adds on each axis.
 */
private fun clampOffset(pan: Offset, scale: Float, box: IntSize, bmp: Bitmap?): Offset {
    if (bmp == null || box.width <= 0 || box.height <= 0 || scale <= MIN_ZOOM) return Offset.Zero
    val imageAspect = bmp.width.toFloat() / bmp.height
    val boxAspect = box.width.toFloat() / box.height
    val fitW: Float
    val fitH: Float
    if (boxAspect > imageAspect) {
        fitH = box.height.toFloat()
        fitW = fitH * imageAspect
    } else {
        fitW = box.width.toFloat()
        fitH = fitW / imageAspect
    }
    val maxX = ((fitW * scale) - box.width) / 2f
    val maxY = ((fitH * scale) - box.height) / 2f
    return Offset(
        pan.x.coerceIn(-maxX.coerceAtLeast(0f), maxX.coerceAtLeast(0f)),
        pan.y.coerceIn(-maxY.coerceAtLeast(0f), maxY.coerceAtLeast(0f)),
    )
}

/** Where ContentScale.Fit draws the picture inside the box, in box pixels. */
private data class FitFrame(val left: Float, val top: Float, val width: Float, val height: Float)

private fun fitFrame(box: IntSize, imgW: Int, imgH: Int): FitFrame {
    if (imgW <= 0 || imgH <= 0 || box.width <= 0 || box.height <= 0) {
        return FitFrame(0f, 0f, box.width.toFloat(), box.height.toFloat())
    }
    val imageAspect = imgW.toFloat() / imgH
    val boxAspect = box.width.toFloat() / box.height
    return if (boxAspect > imageAspect) {
        val h = box.height.toFloat()
        val w = h * imageAspect
        FitFrame((box.width - w) / 2f, 0f, w, h)
    } else {
        val w = box.width.toFloat()
        val h = w / imageAspect
        FitFrame(0f, (box.height - h) / 2f, w, h)
    }
}

/** The window of the image currently visible, in normalised coordinates of the displayed image. */
private fun visibleViewport(box: IntSize, frame: FitFrame, scale: Float, pan: Offset): DetailRect {
    val cx = box.width / 2f
    val cy = box.height / 2f
    val left = (0f - cx - pan.x) / scale + cx
    val top = (0f - cy - pan.y) / scale + cy
    val right = (box.width - cx - pan.x) / scale + cx
    val bottom = (box.height - cy - pan.y) / scale + cy
    return DetailRect(
        ((left - frame.left) / frame.width).coerceIn(0f, 1f),
        ((top - frame.top) / frame.height).coerceIn(0f, 1f),
        ((right - frame.left) / frame.width).coerceIn(0f, 1f),
        ((bottom - frame.top) / frame.height).coerceIn(0f, 1f),
    )
}

/** Draws one 1:1 crop over the preview, stretched into the place its rect occupies. */
@Composable
private fun DetailLayer(bitmap: Bitmap, frame: FitFrame, rect: DetailRect) {
    val density = LocalDensity.current
    val ox = frame.left + rect.u0 * frame.width
    val oy = frame.top + rect.v0 * frame.height
    val dw = (rect.u1 - rect.u0) * frame.width
    val dh = (rect.v1 - rect.v0) * frame.height
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier
            .offset { IntOffset(ox.roundToInt(), oy.roundToInt()) }
            .size(with(density) { dw.toDp() }, with(density) { dh.toDp() }),
    )
}
