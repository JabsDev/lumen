package com.jabs.lumen.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.jabs.lumen.engine.MaskEngine
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jabs.lumen.data.Photo
import com.jabs.lumen.model.ColorGrading
import com.jabs.lumen.model.ColorMixer
import com.jabs.lumen.model.EditParams
import com.jabs.lumen.model.MixerBand
import com.jabs.lumen.model.Preset
import com.jabs.lumen.model.ToneCurve
import kotlin.math.roundToInt

private val CATEGORIES = listOf("Luz", "Curvas", "Cor", "Efeitos", "Lente", "Geometria", "Máscara")

/** Index of the "Máscara" tab, where one finger paints instead of panning or zooming. */
private const val MASK_TAB = 6

/** How far the picture can be pinched in, and how far a double tap jumps in one step. */
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 8f
private const val DOUBLE_TAP_ZOOM = 2.5f

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
    var maskUi by remember { mutableStateOf(MaskUiState()) }
    val shown = if (showOriginal) original else preview

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
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
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = vm::undo, enabled = canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Desfazer")
            }
            IconButton(onClick = vm::redo, enabled = canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Refazer")
            }
            IconButton(
                onClick = {},
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            vm.setShowOriginal(true)
                            tryAwaitRelease()
                            vm.setShowOriginal(false)
                        }
                    )
                },
            ) {
                Icon(Icons.Filled.Compare, contentDescription = "Segure para ver o original")
            }
            IconButton(onClick = vm::exportCurrent) {
                Icon(Icons.Filled.Download, contentDescription = "Exportar")
            }
        }

        var boxSize by remember { mutableStateOf(IntSize.Zero) }
        // Zoom and pan of the preview. One finger paints on the mask tab, so zooming is offered
        // everywhere else and cleared on the way in, which keeps mask taps on the right pixel.
        var scale by remember(photo.id) { mutableFloatStateOf(MIN_ZOOM) }
        var panOffset by remember(photo.id) { mutableStateOf(Offset.Zero) }
        val canZoom = tab != MASK_TAB
        LaunchedEffect(tab) {
            if (tab == MASK_TAB) {
                scale = MIN_ZOOM
                panOffset = Offset.Zero
            }
        }
        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            val next = (scale * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
            scale = next
            panOffset = clampOffset(panOffset + panChange, next, boxSize, preview ?: original)
        }
        val masksLive by rememberUpdatedState(params.masks)
        var overlayBmp by remember { mutableStateOf<Bitmap?>(null) }
        val maskSel = params.masks.firstOrNull { it.id == maskUi.selectedId }
        // The overlay shows the mask's shape, which never changes while an adjustment slider is
        // dragged — keying on the geometry alone keeps that drag from rebuilding it every frame.
        val maskGeom = maskSel?.copy(adjust = com.jabs.lumen.model.MaskAdjust())
        LaunchedEffect(tab, maskUi.selectedId, maskUi.overlay, showOriginal, maskGeom, original, params.rotation, params.flipHorizontal) {
            val bmp = original
            overlayBmp = if (tab == MASK_TAB && maskUi.overlay && !showOriginal && maskSel != null && bmp != null) {
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
            val pt = toImageSpace(p, params.rotation, params.flipHorizontal)
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
            Modifier.weight(1f).fillMaxWidth().padding(8.dp)
                .onSizeChanged { boxSize = it }
                .then(if (canZoom) Modifier.transformable(transformState) else Modifier)
                .pointerInput(tab, maskUi.selectedId, maskUi.erasing) {
                    if (tab != MASK_TAB) return@pointerInput
                    detectTapGestures(onTap = { offset ->
                        val id = maskUi.selectedId
                        val m = id?.let { sid -> masksLive.firstOrNull { it.id == sid } }
                        val bmp = preview
                        if (id != null && m != null &&
                            (m.type == MaskType.SELECT || m.type == MaskType.SUBJECT) && bmp != null
                        ) {
                            toImageCoords(offset, boxSize, bmp.width, bmp.height)?.let { p ->
                                val img = toImageSpace(p, params.rotation, params.flipHorizontal)
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
                .pointerInput(tab, maskUi.selectedId, maskUi.erasing) {
                    if (tab != MASK_TAB) return@pointerInput
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
                .pointerInput(tab) {
                    // On the picture: hold to peek at the original, double tap to zoom in and back out.
                    if (tab == MASK_TAB) return@pointerInput
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
                shown?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
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
            if (canZoom && scale > MIN_ZOOM * 1.02f) {
                Text(
                    "%.1f×".format(scale),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color(0x99000000), RoundedCornerShape(4.dp))
                        .clickable {
                            scale = MIN_ZOOM
                            panOffset = Offset.Zero
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (showOriginal) {
                Text(
                    "ORIGINAL",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color(0x99000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Histogram(
            histogram,
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            items(Preset.entries) { preset ->
                AssistChip(onClick = { vm.applyPreset(preset) }, label = { Text(preset.label) })
            }
        }

        ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = 12.dp,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            CATEGORIES.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }

        // The curve editor needs room and its own gestures: inside the scrolling panel it would
        // fight the scroll for every drag, so that tab gets a taller panel that does not scroll.
        val curvesTab = tab == 1
        Column(
            Modifier
                .fillMaxWidth()
                .height(if (curvesTab) 300.dp else 215.dp)
                .then(if (curvesTab) Modifier else Modifier.verticalScroll(rememberScrollState()))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            when (tab) {
                0 -> {
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
                4 -> {
                    Adjust(vm, "Distorção", params.distortion, -1f..1f, ::percent) { p, v -> p.copy(distortion = v) }
                    Adjust(vm, "Aberração Vermelho/Ciano", params.chromaRed, -1f..1f, ::percent) { p, v -> p.copy(chromaRed = v) }
                    Adjust(vm, "Aberração Azul/Amarelo", params.chromaBlue, -1f..1f, ::percent) { p, v -> p.copy(chromaBlue = v) }
                }
                5 -> {
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
            TextButton(onClick = vm::resetAll) { Text("Redefinir tudo") }
        }
    }
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
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(
                onClick = { sub = 0 },
                label = { Text("Básico") },
                colors = if (sub == 0) chipActive() else chipIdle(),
            )
            AssistChip(
                onClick = { sub = 1 },
                label = { Text("Gradação") },
                colors = if (sub == 1) chipActive() else chipIdle(),
            )
            AssistChip(
                onClick = { sub = 2 },
                label = { Text("Mistura") },
                colors = if (sub == 2) chipActive() else chipIdle(),
            )
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
            )
        }
        TextButton(
            onClick = vm::commit,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) { Text("Confirmar ajustes de cor") }
    }
}

@Composable
private fun chipActive() = AssistChipDefaults.assistChipColors(
    labelColor = MaterialTheme.colorScheme.onPrimary,
    containerColor = MaterialTheme.colorScheme.primary,
)

@Composable
private fun chipIdle() = AssistChipDefaults.assistChipColors(
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    containerColor = Color.Transparent,
)

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
 * Turns a point from the displayed (turned and mirrored) preview back into the normalised image
 * space where masks are stored, so a mask stays on the part of the photo it was drawn over.
 */
private fun toImageSpace(p: Offset, rotation: Int, flip: Boolean): Offset {
    val quarter = ((rotation % 4) + 4) % 4
    var x = p.x
    var y = p.y
    when (quarter) {
        1 -> { x = p.y; y = 1f - p.x }
        2 -> { x = 1f - p.x; y = 1f - p.y }
        3 -> { x = 1f - p.y; y = p.x }
    }
    if (flip) x = 1f - x
    return Offset(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

/** Gives a bitmap built in image space the same turn and mirror the preview is shown with. */
private fun oriented(src: Bitmap, rotation: Int, flip: Boolean): Bitmap {
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
