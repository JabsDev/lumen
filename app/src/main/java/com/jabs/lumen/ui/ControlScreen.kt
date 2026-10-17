package com.jabs.lumen.ui

import android.content.Context
import android.content.res.Configuration
import android.view.Surface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** One dial on the camera overlay: a short label plus the values the camera accepts. */
private data class Dial(
    val type: String,
    val label: String,
    val values: List<String>,
    /** Spelled out, for the highlighted value. */
    val display: (String) -> String,
    /** Abbreviated, for the scale crowded along the ring. */
    val short: (String) -> String,
)

private val DIALS = listOf(
    Dial(
        "iso", "ISO",
        listOf("100", "200", "400", "800", "1600", "3200", "6400", "12800", "25600"),
        { "ISO $it" }, { it },
    ),
    // The camera speaks focal length codes: f-number times 256, as a fraction.
    Dial(
        "fnum", "F",
        listOf(
            "896/256", "1024/256", "1152/256", "1280/256", "1434/256", "1613/256", "1818/256",
            "2048/256", "2304/256", "2560/256", "2816/256", "3328/256", "3584/256", "4096/256",
            "4608/256", "5120/256", "5632/256",
        ),
        ::focalDisplay, ::focalDisplay,
    ),
    // Shutter codes: denominator of the fraction of a second, times 256.
    Dial(
        "ss", "SS",
        listOf(
            "7680/256", "15360/256", "32000/256", "64000/256",
            "128000/256", "256000/256", "512000/256", "1024000/256",
        ),
        ::shutterDisplay, ::shutterDisplay,
    ),
    Dial(
        "expcomp", "EV",
        listOf("-3", "-2", "-1", "0", "1", "2", "3"),
        { value ->
            val step = value.toIntOrNull() ?: 0
            "${if (step > 0) "+" else ""}$step EV"
        },
        { value ->
            val step = value.toIntOrNull() ?: 0
            "${if (step > 0) "+" else ""}$step"
        },
    ),
    Dial(
        "whitebalance", "WB",
        listOf("auto", "daylight", "cloudy", "incandescent", "fluorescent", "flash", "manual"),
        { it },
        { value -> WHITE_BALANCE_SHORT[value] ?: value.take(4).uppercase() },
    ),
)

/** "1280/256" reads as f/5. Falls back to the raw code when it does not parse. */
private fun focalDisplay(code: String): String {
    val f = code.substringBefore("/").toFloatOrNull()?.div(256f) ?: return code
    val tenth = (f * 10).roundToInt()
    return if (tenth % 10 == 0) "f/${tenth / 10}" else "f/${tenth / 10f}"
}

/** "2560/256" reads as 1/10s. Falls back to the raw code when it does not parse. */
private fun shutterDisplay(code: String): String {
    val denom = code.substringBefore("/").toFloatOrNull()?.div(256f) ?: return code
    return if (denom >= 1) "1/${denom.roundToInt()}s" else "${(1f / denom).roundToInt()}s"
}

private val WHITE_BALANCE_SHORT = mapOf(
    "auto" to "AUTO",
    "daylight" to "DAY",
    "cloudy" to "CLDY",
    "incandescent" to "TUNG",
    "fluorescent" to "FLUO",
    "flash" to "FLAS",
    "manual" to "MAN",
)

/**
 * The shape of the dial. The centre sits off the screen so the rings sweep across it as a fan
 * instead of closing into a full circle, and the whole assembly turns a quarter turn with the phone.
 */
private class ArcGeometry(
    val centre: Offset,
    val focus: Float,
    val r0: Float,
    val gap: Float,
    /** The axis the fan spreads across, which decides how much angle a ring may use. */
    val spread: Float,
) {
    fun radius(ring: Int): Float = r0 + ring * gap

    /**
     * Each ring gets the widest angle that still keeps its whole scale on screen. Big rings are
     * therefore compressed, which is what gives the dial its nested look instead of a tight fan.
     */
    fun sweepFor(ring: Int): Float {
        val room = 0.44f * spread / radius(ring)
        if (room >= 0.98f) return 150f
        return 2f * Math.toDegrees(asin(room.toDouble())).toFloat()
    }

    fun angle(ring: Int, index: Int, count: Int): Float {
        val sweep = sweepFor(ring)
        val t = if (count <= 1) 0.5f else index / (count - 1f)
        return focus - sweep / 2f + sweep * t
    }

    /** A point on the ring, pushed `offset` pixels away from the centre along the same radius. */
    fun radial(ring: Int, index: Int, count: Int, at: Float): Offset {
        val a = Math.toRadians(angle(ring, index, count).toDouble())
        return Offset(centre.x + at * cos(a).toFloat(), centre.y + at * sin(a).toFloat())
    }

    fun onRing(ring: Int, degrees: Float): Offset {
        val a = Math.toRadians(degrees.toDouble())
        val r = radius(ring)
        return Offset(centre.x + r * cos(a).toFloat(), centre.y + r * sin(a).toFloat())
    }

    fun isOnArc(ring: Int, degrees: Float): Boolean {
        var delta = degrees - focus
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        return abs(delta) <= sweepFor(ring) / 2f
    }
}

/**
 * The centre sits off the right edge, so the rings are big and shallow and sweep the screen from top
 * to bottom. Portrait turns that whole arrangement a quarter turn: the centre moves below the bottom
 * edge and the rings stack across the screen instead.
 */
private fun geometryFor(landscape: Boolean, width: Float, height: Float): ArcGeometry =
    if (landscape) {
        ArcGeometry(
            centre = Offset(width * 1.02f, height * 0.5f),
            focus = 180f,
            r0 = width * 0.46f,
            gap = width * 0.135f,
            spread = height,
        )
    } else {
        ArcGeometry(
            centre = Offset(width * 0.5f, height * 1.04f),
            focus = 270f,
            r0 = height * 0.30f,
            gap = height * 0.09f,
            spread = width,
        )
    }

/** Which ring a touch landed on and how far along it, or null when it missed every arc. */
private fun ringAt(point: Offset, g: ArcGeometry): Pair<Int, Int>? {
    val dx = point.x - g.centre.x
    val dy = point.y - g.centre.y
    val distance = hypot(dx, dy)

    var ring = -1
    var closest = Float.MAX_VALUE
    for (candidate in DIALS.indices) {
        val off = abs(g.radius(candidate) - distance)
        if (off < closest) {
            closest = off
            ring = candidate
        }
    }
    if (ring < 0 || closest > g.gap * 0.45f) return null

    val degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    if (!g.isOnArc(ring, degrees)) return null

    val sweep = g.sweepFor(ring)
    var delta = degrees - g.focus
    while (delta > 180f) delta -= 360f
    while (delta < -180f) delta += 360f
    val t = (delta + sweep / 2f) / sweep
    val count = DIALS[ring].values.size
    return ring to (t * (count - 1)).roundToInt().coerceIn(0, count - 1)
}

/**
 * Lumia-style remote.
 *
 * The setup is always readable from a strip pinned in a corner that only moves when the phone turns.
 * Touching a row there opens just that parameter's arc; pulling sideways off the shutter opens every
 * arc at once. Each ring's marker sits at the angle its current value falls on, so the arcs double
 * as a readout of what the camera is really set to.
 */
@Composable
fun ControlScreen(
    vm: LumenViewModel,
    onBack: () -> Unit,
    onFiles: () -> Unit,
) {
    val status by vm.cameraStatus.collectAsState()
    val connected by vm.cameraConnected.collectAsState()
    val frame by vm.liveFrame.collectAsState()
    val recording by vm.recording.collectAsState()
    val state by vm.cameraState.collectAsState()
    val settings by vm.cameraSettings.collectAsState()

    // Saved, so turning the phone keeps whatever you were adjusting open.
    var focusType by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf<Map<String, String>>(emptyMap()) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(connected) { if (connected) { vm.refreshCameraState(); vm.startLiveView() } }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Reverse rotations mirror the layout: the strip and the buttons swap to the opposite edge,
    // so they always sit on the same physical side of the phone.
    @Suppress("DEPRECATION")
    val rotation = (LocalContext.current.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation
    val flipped = rotation == Surface.ROTATION_180 || rotation == Surface.ROTATION_270
    val arcsOpen = expanded || focusType != null

    fun closeArcs() {
        expanded = false
        focusType = null
        draft = emptyMap()
    }

    fun preview(ring: Int, index: Int) {
        val dial = DIALS[ring]
        draft = draft + (dial.type to dial.values[index])
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { rootSize = it }
    ) {
        fun expandMove(point: Offset) {
            ringAt(point, geometryFor(landscape, rootSize.width.toFloat(), rootSize.height.toFloat()))
                ?.let { preview(it.first, it.second) }
        }

        fun expandEnd() {
            draft.forEach { (type, value) -> vm.setCameraSetting(type, value) }
            closeArcs()
        }

        // The preview stays centred: full bleed in landscape, a centred frame in portrait.
        if (landscape) {
            PreviewPane(frame, connected, Modifier.fillMaxSize())
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(previewAspect(frame))
                    ) {
                        PreviewPane(frame, connected, Modifier.fillMaxSize())
                        if (recording) RecBadge(Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
                    }
                    if (!connected && !arcsOpen) {
                        StatusLine(status, Modifier.align(Alignment.TopCenter).padding(top = 64.dp))
                    }
                }
                // Photo centred, video beside it. The trailing spacer matches the record
                // button plus gap, so the shutter lands exactly on the centre.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(vertical = 12.dp),
                ) {
                    Row(
                        Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RecordButton(recording, { vm.toggleRecording() })
                        Spacer(Modifier.size(12.dp))
                        ShutterCluster(
                            onCapture = { vm.capture() },
                            onExpandStart = { expanded = true },
                            onExpandMove = ::expandMove,
                            onExpandEnd = ::expandEnd,
                        )
                        Spacer(Modifier.size(62.dp))
                    }
                    GalleryThumb(
                        vm,
                        onFiles,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                    )
                }
            }
        }

        // Arcs and strip never share the screen: one is the editor, the other the readout.
        if (arcsOpen) {
            DialArcs(
                settings = settings,
                draft = draft,
                focus = focusType,
                open = true,
                landscape = landscape,
                onFocus = { type -> focusType = type },
                onPreview = { ring, index -> preview(ring, index) },
                onCommit = { type, value -> vm.setCameraSetting(type, value) },
                onClose = {
                    draft.forEach { (type, value) -> vm.setCameraSetting(type, value) }
                    closeArcs()
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            SettingStrip(
                settings = settings,
                onPick = { type -> focusType = type },
                landscape = landscape,
                modifier = when {
                    landscape && !flipped ->
                        Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)
                    landscape ->
                        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp)
                    !flipped ->
                        Modifier.align(Alignment.CenterEnd).padding(end = 10.dp)
                    else ->
                        Modifier.align(Alignment.CenterStart).padding(start = 10.dp)
                },
            )
        }

        if (landscape) {
            // Photo and video share the side edge, stacked like the sketch: video above photo.
            Column(
                Modifier
                    .align(if (!flipped) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RecordButton(recording, onClick = { vm.toggleRecording() })
                Spacer(Modifier.size(10.dp))
                ShutterCluster(
                    onCapture = { vm.capture() },
                    onExpandStart = { expanded = true },
                    onExpandMove = ::expandMove,
                    onExpandEnd = ::expandEnd,
                )
            }
            if (recording) RecBadge(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 96.dp))
            GalleryThumb(
                vm,
                onFiles,
                modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 20.dp, bottom = 16.dp),
            )
            // The status sits on the long edge the strip is not using.
            if (!connected && !arcsOpen) {
                StatusLine(
                    status,
                    if (!flipped) {
                        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp)
                    } else {
                        Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)
                    },
                )
            }
        }

        Row(
            Modifier.align(Alignment.TopStart).statusBarsPadding().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.stopLiveView(); onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
            }
            if (state?.battery != null) {
                Text(
                    state?.battery.orEmpty(),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** The resting readout: every value the camera is set to, pinned to a corner. */
@Composable
private fun SettingStrip(
    settings: Map<String, String>,
    onPick: (String) -> Unit,
    landscape: Boolean,
    modifier: Modifier = Modifier,
) {
    val entries = @Composable { vertical: Boolean ->
        DIALS.forEach { dial ->
            val value = settings[dial.type]
            if (vertical) {
                Row(
                    Modifier
                        .widthIn(min = 118.dp)
                        .pointerInput(dial.type) { detectTapGestures(onTap = { onPick(dial.type) }) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(dial.label, color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
                    Text(
                        value?.let(dial.display) ?: "—",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            } else {
                Column(
                    Modifier
                        .pointerInput(dial.type) { detectTapGestures(onTap = { onPick(dial.type) }) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        value?.let(dial.display) ?: dial.label,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }

    if (landscape) {
        Row(
            modifier
                .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(22.dp))
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) { entries(false) }
    } else {
        Column(
            modifier
                .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(22.dp))
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) { entries(true) }
    }
}

/** Draws the concentric rings and turns touches on them into setting changes. */
@Composable
private fun DialArcs(
    settings: Map<String, String>,
    draft: Map<String, String>,
    focus: String?,
    open: Boolean,
    landscape: Boolean,
    onFocus: (String) -> Unit,
    onPreview: (Int, Int) -> Unit,
    onCommit: (String, String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val geometry = geometryFor(landscape, size.width.toFloat(), size.height.toFloat())
    val live = open

    Canvas(
        modifier
            .onSizeChanged { size = it }
            .pointerInput(focus, geometry) {
                // At rest the arcs are a readout, so they only take input while a dial is open.
                if (!live) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = { offset -> ringAt(offset, geometry)?.let { onPreview(it.first, it.second) } },
                    onDragEnd = {
                        draft.forEach { (type, value) -> onCommit(type, value) }
                        onClose()
                    },
                    onDragCancel = { onClose() },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        ringAt(change.position, geometry)?.let { onPreview(it.first, it.second) }
                    },
                )
            }
            .pointerInput(focus, geometry) {
                if (!live) return@pointerInput
                detectTapGestures(
                    onTap = { offset ->
                        val hit = ringAt(offset, geometry)
                        if (hit == null) onClose()
                        else {
                            onFocus(DIALS[hit.first].type)
                            onPreview(hit.first, hit.second)
                        }
                    },
                )
            },
    ) {
        val g = geometryFor(landscape, size.width.toFloat(), size.height.toFloat())

        DIALS.forEachIndexed { ring, dial ->
            val active = focus == dial.type
            val current = draft[dial.type] ?: settings[dial.type]
            val known = current != null && dial.values.contains(current)
            val selected = current?.let { dial.values.indexOf(it) }?.takeIf { it >= 0 }
                ?: (dial.values.size / 2)
            val count = dial.values.size
            // Rested rings stay legible; the one being adjusted lifts off the rest.
            val strength = when {
                active -> 1f
                focus == null -> 0.55f
                else -> 0.16f
            }
            val sweep = g.sweepFor(ring)

            val ringPath = Path()
            for (step in 0..48) {
                val p = g.onRing(ring, g.focus - sweep / 2f + sweep * step / 48f)
                if (step == 0) ringPath.moveTo(p.x, p.y) else ringPath.lineTo(p.x, p.y)
            }
            drawPath(
                ringPath,
                Color.White.copy(alpha = 0.3f * strength),
                style = Stroke(width = if (active) 2.4f else 1.3f, cap = StrokeCap.Round),
            )

            dial.values.forEachIndexed { index, value ->
                val hit = index == selected
                val length = if (hit) 12f else 5f
                drawLine(
                    color = Color.White.copy(alpha = (if (hit) 0.95f else 0.38f) * strength),
                    start = g.radial(ring, index, count, g.radius(ring) - length),
                    end = g.radial(ring, index, count, g.radius(ring) + length),
                    strokeWidth = if (hit) 3f else 1.3f,
                    cap = StrokeCap.Round,
                )
                // The value in play is spoken by the big label instead, so it is not repeated here.
                if (hit && known) return@forEachIndexed
                if (!hit && count > 7 && index % 2 != 0) return@forEachIndexed
                val anchor = g.radial(ring, index, count, g.radius(ring) + 19f)
                val tilt = g.angle(ring, index, count) + 90f
                val upright = if (tilt > 90f && tilt < 270f) tilt - 180f else tilt
                rotate(degrees = upright, pivot = anchor) {
                    drawCentredLabel(
                        measurer,
                        dial.short(value),
                        anchor,
                        9f,
                        Color.White.copy(alpha = 0.5f * strength),
                    )
                }
            }

            // The current value, always upright so it can be read at a glance.
            if (known) {
                drawCentredLabel(
                    measurer,
                    dial.display(current),
                    g.radial(ring, selected, count, g.radius(ring) - 30f),
                    16f,
                    Color.White.copy(alpha = strength),
                )
            }
        }
    }
}

/**
 * The shutter, with the sideways pull that reveals every arc. That drag has to be measured in root
 * coordinates because it starts on the button but ends up hunting for a ring on the far side.
 */
@Composable
private fun ShutterCluster(
    onCapture: () -> Unit,
    onExpandStart: () -> Unit,
    onExpandMove: (Offset) -> Unit,
    onExpandEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var root by remember { mutableStateOf(Offset.Zero) }
    Canvas(
        modifier
            .size(76.dp)
            .onGloballyPositioned { root = it.positionInRoot() }
            .pointerInput(Unit) { detectTapGestures(onTap = { onCapture() }) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onExpandStart() },
                    onDragEnd = { onExpandEnd() },
                    onDragCancel = { onExpandEnd() },
                    onDrag = { change, _ ->
                        change.consume()
                        onExpandMove(root + change.position)
                    },
                )
            },
    ) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        drawCircle(Color.White.copy(alpha = 0.9f), 36f, centre, style = Stroke(2.5f))
        drawCircle(Color.White, 28f, centre)
    }
}

/** Frames arrive at the camera's own shape, so the preview keeps it instead of being stretched. */
private fun previewAspect(frame: android.graphics.Bitmap?): Float =
    if (frame != null && frame.height > 0) frame.width.toFloat() / frame.height else 16f / 9f

@Composable
private fun PreviewPane(frame: android.graphics.Bitmap?, connected: Boolean, modifier: Modifier) {
    if (frame != null) {
        androidx.compose.foundation.Image(
            bitmap = frame.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(modifier.background(Color(0xFF0B0C0E)), contentAlignment = Alignment.Center) {
            if (connected) CircularProgressIndicator(color = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun RecBadge(modifier: Modifier = Modifier) {
    Text(
        "REC",
        color = Color(0xFFE24B4B),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = modifier,
    )
}

@Composable
private fun StatusLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.55f),
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier,
    )
}

/** Gallery shortcut in the corner, like the phone camera: the latest camera photo, or a folder. */
@Composable
private fun GalleryThumb(vm: LumenViewModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val files by vm.cameraFiles.collectAsState()
    val first = files.firstOrNull { !it.isContainer }
    val thumb by produceState<android.graphics.Bitmap?>(initialValue = null, first?.thumbUrl) {
        value = first?.let { runCatching { vm.cameraThumbnail(it) }.getOrNull() }
    }
    Box(
        modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .pointerInput(first?.thumbUrl) { detectTapGestures(onTap = { onOpen() }) },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = thumb
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Fotos da câmera",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(Icons.Filled.FolderOpen, contentDescription = "Fotos da câmera", tint = Color.White)
        }
    }
}

@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(50.dp)
            .background(Color.Black.copy(alpha = 0.3f), CircleShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        if (recording) {
            Box(Modifier.size(20.dp).background(Color(0xFFE24B4B), RoundedCornerShape(4.dp)))
        } else {
            Box(Modifier.size(22.dp).border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape))
        }
    }
}

private fun DrawScope.drawCentredLabel(
    measurer: TextMeasurer,
    text: String,
    centre: Offset,
    sizeSp: Float,
    color: Color,
) {
    val layout = measurer.measure(
        text = text,
        style = TextStyle(color = color, fontSize = sizeSp.sp, fontWeight = FontWeight.Medium),
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(centre.x - layout.size.width / 2f, centre.y - layout.size.height / 2f),
    )
}