package com.jabs.lumen.ui

import android.app.Application
import android.content.Context
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jabs.lumen.camera.CameraItem
import com.jabs.lumen.camera.CameraState
import com.jabs.lumen.camera.LiveViewSource
import com.jabs.lumen.camera.LumixClient
import com.jabs.lumen.data.Photo
import com.jabs.lumen.data.PhotoRepository
import com.jabs.lumen.engine.DeviceProfile
import com.jabs.lumen.engine.ImageEngine
import com.jabs.lumen.engine.LutLibrary
import com.jabs.lumen.engine.Segmenter
import com.jabs.lumen.engine.UpdateInfo
import com.jabs.lumen.engine.Updater
import com.jabs.lumen.model.EditParams
import com.jabs.lumen.model.Preset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max

private val initialDragSize = DeviceProfile.initialDragSize()
private val initialPreviewSize = DeviceProfile.initialPreviewSize(initialDragSize)
private const val THUMB_SIZE = 360
private const val MAX_HISTORY = 100

/** Moving previews with masks on are rendered at this fraction of the working copy. */
private const val MASK_DRAG_SCALE = 0.5f

/** How long after the finger lifts the full-size preview is rebuilt. */
private const val SETTLE_DELAY_MS = 180L
private const val CONNECT_TIMEOUT_MS = 20_000L
private const val ACCESS_TIMEOUT_MS = 90_000L
private const val ACCESS_POLL_MS = 2_000L
private const val DEVICE_NAME = "Lumen"
private val CAMERA_SETTING_TYPES = listOf("iso", "focal", "shtrspeed", "exposure", "whitebalance", "focusmode")

/** The dials speak short names; the camera speaks its own setting names. */
private fun dialForSetting(type: String) = when (type) {
    "focal" -> "fnum"
    "shtrspeed" -> "ss"
    "exposure" -> "expcomp"
    else -> type
}

private fun settingForDial(dial: String) = when (dial) {
    "fnum" -> "focal"
    "ss" -> "shtrspeed"
    "expcomp" -> "exposure"
    else -> dial
}

data class ImportProgress(val bytes: Long, val total: Long) {
    val fraction: Float get() = if (total > 0L) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    val megabytes: String get() = "%.1f / %.1f MB".format(bytes / 1_048_576f, total / 1_048_576f)
}

/** The in-app updater's small state machine, surfaced by the Library screen dialog. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data class Ready(val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}
private const val THUMB_CACHE_SIZE = 150
private const val PREVIEW_CACHE_SIZE = 8

class LumenViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PhotoRepository(app)
    private val resolver = app.contentResolver
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)

    private val _photos = MutableStateFlow(repo.load())
    val photos: StateFlow<List<Photo>> = _photos.asStateFlow()

    private val _openId = MutableStateFlow<String?>(null)
    val openPhoto: StateFlow<Photo?> = combine(_photos, _openId) { list, id ->
        list.find { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _original = MutableStateFlow<Bitmap?>(null)
    val original: StateFlow<Bitmap?> = _original.asStateFlow()

    /** Smaller copy used while a slider is being dragged, so the preview keeps up. */
    private var dragSource: Bitmap? = null

    private val _params = MutableStateFlow(EditParams())
    val params: StateFlow<EditParams> = _params.asStateFlow()

    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    private val _histogram = MutableStateFlow(IntArray(256))
    val histogram: StateFlow<IntArray> = _histogram.asStateFlow()

    private val _showOriginal = MutableStateFlow(false)
    val showOriginal: StateFlow<Boolean> = _showOriginal.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val _showCamera = MutableStateFlow(false)
    val showCamera: StateFlow<Boolean> = _showCamera.asStateFlow()

    private val _cameraStatus = MutableStateFlow("Desconectada")
    val cameraStatus: StateFlow<String> = _cameraStatus.asStateFlow()

    private val _cameraConnected = MutableStateFlow(false)
    val cameraConnected: StateFlow<Boolean> = _cameraConnected.asStateFlow()

    private val _cameraState = MutableStateFlow<CameraState?>(null)
    val cameraState: StateFlow<CameraState?> = _cameraState.asStateFlow()

    private val _liveFrame = MutableStateFlow<Bitmap?>(null)
    val liveFrame: StateFlow<Bitmap?> = _liveFrame.asStateFlow()

    private val _liveViewOn = MutableStateFlow(false)
    val liveViewOn: StateFlow<Boolean> = _liveViewOn.asStateFlow()

    private val _cameraSettings = MutableStateFlow<Map<String, String>>(emptyMap())
    val cameraSettings: StateFlow<Map<String, String>> = _cameraSettings.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _cameraFiles = MutableStateFlow<List<CameraItem>>(emptyList())
    val cameraFiles: StateFlow<List<CameraItem>> = _cameraFiles.asStateFlow()

    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()

    /** The bundled .cube looks, listed once at startup for the Efeitos tab. */
    private val _luts = MutableStateFlow<List<LutLibrary.Entry>>(emptyList())
    val luts: StateFlow<List<LutLibrary.Entry>> = _luts.asStateFlow()

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()
    private var updateCheckedOnce = false

    

    private val undoStack = ArrayDeque<EditParams>()
    private val redoStack = ArrayDeque<EditParams>()
    private var committed = EditParams()
    private var lastRenderedParams: EditParams? = null
    private var lastRenderedGeometry: Pair<Int, Boolean>? = null
    private var previewSize: Int = initialPreviewSize
    private var dragSize: Int = initialDragSize
    private var renderJob: Job? = null
    private var histogramJob: Job? = null
    private var settleJob: Job? = null

    private var lumix: LumixClient? = null
    private var liveViewJob: Job? = null
    private var liveViewSource: LiveViewSource? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    private val thumbCache = LruCache<String, Bitmap>(THUMB_CACHE_SIZE)
    private val previewCache = LruCache<String, Bitmap>(PREVIEW_CACHE_SIZE)

    init {
        LutLibrary.init(app)
        // Load the offline segmentation model up front; it is tiny and masks may ask for it at any time.
        Segmenter.init(app)
        viewModelScope.launch {
            _luts.value = withContext(Dispatchers.IO) { LutLibrary.scan() }
        }
    }

    fun thumbnail(photo: Photo): Bitmap? = runCatching {
        // A look saved on another visit starts cold here; the thumb must not miss it.
        LutLibrary.warm(photo.edit.lutId)
        ImageEngine.render(ImageEngine.decode(repo.file(photo), THUMB_SIZE), photo.edit)
    }.getOrNull()

    fun open(photo: Photo) {
        viewModelScope.launch {
            _importProgress.value = ImportProgress(0L, 0L)
            val bmp = withContext(Dispatchers.IO) {
                val decoded = runCatching { ImageEngine.decode(repo.file(photo), previewSize) }.getOrNull()
                // A saved look must be parsed before the first render, or it opens without it.
                LutLibrary.warm(photo.edit.lutId)
                decoded
            }
            _importProgress.value = null
            if (bmp == null) {
                _messages.emit("Não foi possível abrir ${photo.displayName}: formato não suportado")
                return@launch
            }
            committed = photo.edit
            _params.value = photo.edit
            _showOriginal.value = false
            undoStack.clear()
            redoStack.clear()
            updateHistoryFlags()
            _original.value = bmp
            dragSource = withContext(Dispatchers.Default) {
                val scale = dragSize / max(bmp.width, bmp.height).toFloat()
                if (scale < 1f) {
                    android.graphics.Bitmap.createScaledBitmap(
                        bmp,
                        (bmp.width * scale).toInt().coerceAtLeast(1),
                        (bmp.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                } else bmp
            }
            lastRenderedParams = null
            _openId.value = photo.id
            scheduleRender()
        }
    }

    fun close() {
        renderJob?.cancel()
        settleJob?.cancel()
        _openId.value = null
        _original.value = null
        _preview.value = null
        dragSource = null
        maskSource = null
        maskSourceFrom = null
        pendingFullRender = false
    }

    fun updateLive(transform: (EditParams) -> EditParams) {
        _params.value = transform(_params.value)
        scheduleRender(fast = true)
    }

    fun commit() {
        val current = _params.value
        if (current == committed) return
        undoStack.addLast(committed)
        if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        committed = current
        updateHistoryFlags()
        persist(current)
        // The moving preview is a small working copy; the full-size pass waits until the
        // gesture is over and then replaces it.
        pendingFullRender = true
        settleRender()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(committed)
        applyCommitted(undoStack.removeLast())
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(committed)
        applyCommitted(redoStack.removeLast())
    }

    fun applyPreset(preset: Preset) {
        val current = _params.value
        // A preset swaps the adjustments; the chosen look stays until it is cleared by hand.
        _params.value = preset.params.copy(
            rotation = current.rotation,
            flipHorizontal = current.flipHorizontal,
            lutId = current.lutId,
            lutAmount = current.lutAmount,
        )
        scheduleRender(fast = false)
        commit()
    }

    /** Picks a bundled look. The file is parsed first so the render that follows finds it. */
    fun selectLut(id: String) {
        viewModelScope.launch {
            if (id.isNotEmpty() && LutLibrary.get(id) == null) {
                val loaded = withContext(Dispatchers.IO) { LutLibrary.warm(id) }
                if (loaded == null) {
                    _messages.emit("Não foi possível carregar a LUT")
                    return@launch
                }
            }
            updateLive { it.copy(lutId = id) }
            commit()
        }
    }

    /** Validates a picked .cube, keeps a copy in the app and puts it on the photo at once. */
    fun importLut(uri: Uri) {
        viewModelScope.launch {
            val entry = withContext(Dispatchers.IO) { LutLibrary.importUserLut(resolver, uri) }
            if (entry == null) {
                _messages.emit("Arquivo .cube inválido ou muito grande")
                return@launch
            }
            _luts.value = withContext(Dispatchers.IO) { LutLibrary.scan() }
            _messages.emit("${entry.name} importada")
            selectLut(entry.id)
        }
    }

    /** Deletes an imported look; the photo falls back to no LUT if that was the one in use. */
    fun removeLut(id: String) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { LutLibrary.removeUserLut(id) }
            if (!removed) return@launch
            _luts.value = withContext(Dispatchers.IO) { LutLibrary.scan() }
            if (_params.value.lutId == id) selectLut("")
            _messages.emit("LUT removida")
        }
    }

    fun resetAll() {
        _params.value = EditParams()
        scheduleRender(fast = false)
        commit()
    }

    fun setShowOriginal(show: Boolean) {
        _showOriginal.value = show
    }

    fun importPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = mutableListOf<Photo>()
            var rejected = 0
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    val photo = runCatching { repo.importFile(resolver, uri) }.getOrNull()
                    if (photo != null && repo.isDecodable(photo)) {
                        added += photo
                    } else {
                        photo?.let { repo.delete(it) }
                        rejected++
                    }
                }
            }
            commitImports(added, rejected)
        }
    }

    fun exportCurrent() {
        val photo = openPhoto.value ?: return
        val params = _params.value
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val full = ImageEngine.decode(repo.file(photo), null)
                    saveToGallery(ImageEngine.render(full, params), photo.displayName)
                }
            }
            _messages.emit(
                if (result.isSuccess) "Exportado em Pictures/Lumen" else "Falha ao exportar"
            )
        }
    }

    fun showFilesTab() {
        stopLiveView()
    }

    fun openCamera() {
        _showCamera.value = true
        if (!_cameraConnected.value) connectCamera()
    }

    fun closeCamera() {
        disconnectCamera()
        _showCamera.value = false
    }

    fun connectCamera() {
        if (_cameraConnected.value) return
        networkCallback?.let { connectivity.unregisterNetworkCallback(it) }
        networkCallback = null
        _cameraStatus.value = "Procurando o Wi-Fi da câmera..."
        viewModelScope.launch {
            val wifi = connectivity.allNetworks.firstOrNull { network ->
                connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (wifi != null) {
                attachCamera(cameraHost(), wifi)
            } else {
                requestWifi()
            }
        }
    }

    private fun requestWifi() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                viewModelScope.launch { attachCamera(cameraHost(), network) }
            }
        }
        networkCallback = callback
        connectivity.requestNetwork(request, callback)
        viewModelScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (!_cameraConnected.value && networkCallback === callback) {
                _cameraStatus.value = "Não achei a rede da câmera. Confirme que o celular está no Wi-Fi da câmera."
            }
        }
    }

    fun disconnectCamera() {
        networkCallback?.let { connectivity.unregisterNetworkCallback(it) }
        networkCallback = null
        connectivity.bindProcessToNetwork(null)
        stopLiveView()
        lumix = null
        _cameraConnected.value = false
        _cameraFiles.value = emptyList()
        _cameraStatus.value = "Desconectada"
    }

    fun refreshCameraFiles() {
        val client = lumix ?: return
        _cameraStatus.value = "Lendo os arquivos da câmera..."
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { client.listFiles() } }
            result.onSuccess { files ->
                _cameraFiles.value = files
                _cameraStatus.value = "${files.size} arquivo(s) na câmera"
            }.onFailure {
                _cameraStatus.value = "Falha ao listar: ${it.message}"
            }
        }
    }

    suspend fun cameraThumbnail(item: CameraItem): Bitmap? =
        loadCameraImage(item.thumbUrl, thumbCache)

    suspend fun cameraPreview(item: CameraItem): Bitmap? =
        loadCameraImage(item.largeUrl.ifEmpty { item.thumbUrl }, previewCache)

    private suspend fun loadCameraImage(url: String, cache: LruCache<String, Bitmap>): Bitmap? {
        if (url.isEmpty()) return null
        cache.get(url)?.let { return it }
        val client = lumix ?: return null
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { client.openStream(url).use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
        bitmap?.let { cache.put(url, it) }
        return bitmap
    }

    fun toggleRecording() {
        val client = lumix ?: return
        viewModelScope.launch {
            // The reply is not trustworthy (the camera answers err_reject while obeying), so the
            // state is re-read and that is what the UI shows.
            withContext(Dispatchers.IO) {
                runCatching { if (_recording.value) client.recordStop() else client.recordStart() }
            }
            val verified = withContext(Dispatchers.IO) { runCatching { client.state() }.getOrNull() }
            if (verified != null) {
                _cameraState.value = verified
                _recording.value = verified.isRecording
            }
            _messages.emit(if (_recording.value) "Gravando" else "Gravação finalizada")
        }
    }

    fun setCameraSetting(type: String, value: String) {
        val client = lumix ?: return
        viewModelScope.launch {
            // The camera clamps or refines some values, so the read-back is the truth, not the request.
            val actual = withContext(Dispatchers.IO) {
                runCatching {
                    val setting = settingForDial(type)
                    if (!client.setSetting(setting, value)) return@runCatching null
                    client.getSetting(setting) ?: value
                }.getOrNull()
            }
            if (actual != null) {
                _cameraSettings.value = _cameraSettings.value + (type to actual)
            } else {
                _messages.emit("A câmera recusou $type=$value")
            }
        }
    }

    fun refreshCameraState() {
        val client = lumix ?: return
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) { runCatching { client.state() }.getOrNull() }
            if (state != null) {
                _cameraState.value = state
                _recording.value = state.isRecording
            }
            val settings = withContext(Dispatchers.IO) {
                CAMERA_SETTING_TYPES.mapNotNull { type: String ->
                    runCatching { client.getSetting(type) }.getOrNull()?.let { dialForSetting(type) to it }
                }.toMap()
            }
            _cameraSettings.value = _cameraSettings.value + settings
            // The live menu reports the real values under the official ids, including the ones
            // plain getsetting does not serve. It only overrides when the camera answered.
            val menu = withContext(Dispatchers.IO) { runCatching { client.curMenu() }.getOrDefault(emptyMap()) }
            if (menu.isNotEmpty()) {
                val mapped = mutableMapOf<String, String>()
                fun take(dialType: String, vararg ids: String) {
                    ids.firstNotNullOfOrNull { menu[it]?.value?.takeIf { v -> v.isNotEmpty() } }
                        ?.let { mapped[dialType] = it }
                }
                take("whitebalance", "menu_item_id_whitebalance")
                take("expcomp", "menu_item_id_exposure2", "menu_item_id_exposure3")
                take("iso", "menu_item_id_sensitivity", "menu_item_id_iso", "menu_item_id_gain")
                _cameraSettings.value = _cameraSettings.value + mapped
            }
        }
    }

    fun startLiveView() {
        val client = lumix ?: return
        if (liveViewJob != null) return
        _liveViewOn.value = true
        liveViewJob = viewModelScope.launch(Dispatchers.IO) {
            val source = LiveViewSource(client) { frame -> _liveFrame.value = frame }
            liveViewSource = source
            source.open()
        }
    }

    fun stopLiveView() {
        runCatching { liveViewSource?.close() }
        liveViewSource = null
        liveViewJob?.cancel()
        liveViewJob = null
        _liveViewOn.value = false
        _liveFrame.value = null
    }

    fun capture() {
        val client = lumix ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { client.capture() }.isSuccess
            }
            _messages.emit(if (ok) "Disparo enviado" else "Falha no disparo")
        }
    }

    fun importFromCamera(items: List<CameraItem>, openAfter: Boolean = false) {
        val client = lumix ?: return
        if (items.isEmpty()) return
        viewModelScope.launch {
            val added = mutableListOf<Photo>()
            var rejected = 0
            var doneBytes = 0L
            _importProgress.value = ImportProgress(0L, 0L)
            withContext(Dispatchers.IO) {
                items.forEachIndexed { index: Int, item: CameraItem ->
                    val fileName = item.url.substringBefore('?').substringAfterLast('/').ifEmpty { item.title }
                    var fileTotal = 0L
                    val openStream = {
                        val stream = client.openStream(item.url)
                        // The listing has no size; the camera reports it per file in X-FILE_SIZE.
                        fileTotal = if (item.size > 0L) item.size else stream.contentLength
                        stream
                    }
                    val report: (Long, Long) -> Unit = { copied: Long, _ ->
                        if (fileTotal > 0L) {
                            _importProgress.value = ImportProgress(copied.coerceAtMost(fileTotal), fileTotal)
                        }
                    }
                    val photo = runCatching { repo.importStream(fileName, fileTotal, openStream, report) }.getOrNull()
                    doneBytes += if (fileTotal > 0L) fileTotal else (photo?.let { repo.file(it).length() } ?: 0L)
                    _importProgress.value = ImportProgress(doneBytes, 0L)
                    if (photo != null && repo.isDecodable(photo)) {
                        added += photo
                    } else {
                        photo?.let { repo.delete(it) }
                        rejected++
                    }
                    if (items.size > 1) {
                        _importProgress.value = ImportProgress((index + 1).toLong(), items.size.toLong())
                    }
                }
            }
            commitImports(added, rejected)
            _importProgress.value = null
            if (openAfter && added.size == 1) open(added.first())
        }
    }

    private suspend fun attachCamera(host: String, network: Network) {
        val client = LumixClient(host, network)
        val state = withContext(Dispatchers.IO) { runCatching { client.state() } }
        if (state.isFailure) {
            _cameraStatus.value = "A câmera não respondeu em $host (${state.exceptionOrNull()?.javaClass?.simpleName})"
            return
        }
        if (!authorize(client)) return
        lumix = client
        _cameraConnected.value = true
        _cameraStatus.value = "Conectada em $host"
        refreshCameraState()
        refreshCameraFiles()
    }

    private suspend fun authorize(client: LumixClient): Boolean {
        val deadline = System.currentTimeMillis() + ACCESS_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val reply = withContext(Dispatchers.IO) {
                runCatching { client.requestAccess(deviceId(), DEVICE_NAME) }
            }.getOrElse {
                _cameraStatus.value = "Falha ao pedir acesso à câmera (${it.javaClass.simpleName})"
                return false
            }
            when (reply) {
                "ok", "upload_bt", "transfer" -> return true
                "ok_under_research", "ok_under_research_no_msg" -> {
                    _cameraStatus.value = "Confirme a conexão no display da câmera..."
                    delay(ACCESS_POLL_MS)
                }
                else -> {
                    _cameraStatus.value = "A câmera recusou a conexão ($reply)"
                    return false
                }
            }
        }
        _cameraStatus.value = "A câmera não confirmou a conexão a tempo"
        return false
    }

    private fun deviceId(): String {
        val prefs = getApplication<Application>().getSharedPreferences("lumen_camera", Context.MODE_PRIVATE)
        val seed = prefs.getString("device_seed", null) ?: UUID.randomUUID().toString().takeLast(12).also {
            prefs.edit().putString("device_seed", it).apply()
        }
        return "4D454930-0100-1000-8001-$seed"
    }

    private fun cameraHost(): String {
        val wifi = getApplication<Application>().getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val server = wifi?.dhcpInfo?.serverAddress ?: 0
        if (server == 0) return LumixClient.DEFAULT_HOST
        return "${server and 0xFF}.${(server shr 8) and 0xFF}.${(server shr 16) and 0xFF}.${(server shr 24) and 0xFF}"
    }

    private suspend fun commitImports(added: List<Photo>, rejected: Int) {
        val all = _photos.value + added
        _photos.value = all
        withContext(Dispatchers.IO) { repo.save(all) }
        val text = if (rejected == 0) {
            "${added.size} foto(s) importada(s)"
        } else {
            "${added.size} importada(s), $rejected não suportada(s)"
        }
        _messages.emit(text)
    }

    /**
     * Asks GitHub for the latest release. An automatic check stays quiet when there is nothing
     * new; a manual one always reports back so the button never feels dead.
     */
    fun checkForUpdates(manual: Boolean = false) {
        val state = _update.value
        if (state is UpdateState.Checking || state is UpdateState.Downloading) return
        if (!manual && updateCheckedOnce) return
        updateCheckedOnce = true
        _update.value = UpdateState.Checking
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { Updater.check() }.getOrNull() }
            _update.value = when {
                info != null -> UpdateState.Available(info)
                manual -> UpdateState.UpToDate
                else -> UpdateState.Idle
            }
        }
    }

    /** Downloads the pending update and opens the installer as soon as it is ready. */
    fun downloadUpdate() {
        val info = (_update.value as? UpdateState.Available)?.info ?: return
        _update.value = UpdateState.Downloading(0)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    Updater.download(getApplication(), info) { percent ->
                        _update.value = UpdateState.Downloading(percent)
                    }
                }
            }.onSuccess { file ->
                _update.value = UpdateState.Ready(file)
                installUpdate()
            }.onFailure { e ->
                _update.value = UpdateState.Failed(e.message ?: "Falha ao baixar a atualização")
            }
        }
    }

    /** Opens the system installer, asking for the unknown-sources permission first if needed. */
    fun installUpdate() {
        val file = (_update.value as? UpdateState.Ready)?.file ?: return
        val context = getApplication<Application>()
        if (!Updater.canInstall(context)) {
            Updater.requestInstallPermission(context)
            _messages.tryEmit("Permita instalar apps desconhecidos para concluir a atualização")
            return
        }
        runCatching { Updater.install(context, file) }
            .onFailure { _update.value = UpdateState.Failed("Não foi possível abrir o instalador") }
    }

    fun dismissUpdate() {
        if (_update.value is UpdateState.Downloading) return
        _update.value = UpdateState.Idle
    }

    private fun applyCommitted(p: EditParams) {
        committed = p
        _params.value = p
        updateHistoryFlags()
        persist(p)
        scheduleRender(fast = false)
    }

    private fun scheduleRender(fast: Boolean = false) {
        val full = _original.value ?: return
        val src = if (fast) (dragSource ?: full) else full
        val p = _params.value
        val key = if (fast) p.copy(rotation = 0, flipHorizontal = false) else p
        // Turn and mirror are stripped from the drag key, so they are watched separately —
        // otherwise a rotate would produce the same key as before and never re-render.
        val geometry = p.rotation to p.flipHorizontal
        if (key == lastRenderedParams && geometry == lastRenderedGeometry && _preview.value != null) return
        lastRenderedParams = key
        lastRenderedGeometry = geometry
        renderJob?.cancel()
        // The settled full-size pass waits for the finger; a new drag pushes it back again.
        if (fast) settleJob?.cancel()
        if (!fast) histogramJob?.cancel()
        renderJob = viewModelScope.launch {
            val started = System.nanoTime()
            val out = withContext(Dispatchers.Default) { ImageEngine.renderFast(maskedDragSource(src, p), p) }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            _preview.value = out
            if (fast) measureRender(elapsed)
            if (key != _renderedKey()) lastRenderedParams = null
            if (fast) {
                // Dragging: skip the histogram and settle at full size when the finger lifts.
                pendingFullRender = true
            } else {
                pendingFullRender = false
                histogramJob = viewModelScope.launch {
                    withContext(Dispatchers.Default) { ImageEngine.histogram(out) }
                        .let { _histogram.value = it }
                }
            }
        }
    }

    private fun _renderedKey(): EditParams? = lastRenderedParams

    private fun activeMasks(p: EditParams): Boolean =
        p.masks.any { it.enabled && !it.adjust.isIdentity() }

    private var maskSource: Bitmap? = null
    private var maskSourceFrom: Bitmap? = null

    /**
     * Masks are applied per pixel, which is the one thing a weak phone cannot afford while a
     * finger is moving, so the moving preview is rendered from a smaller copy. The settled
     * preview and the export always use full resolution, and the mask travels with the photo
     * because its geometry is normalised.
     */
    private fun maskedDragSource(src: Bitmap, p: EditParams): Bitmap {
        if (!activeMasks(p)) return src
        val cached = maskSource
        if (cached != null && maskSourceFrom === src) return cached
        val scaled = Bitmap.createScaledBitmap(
            src,
            (src.width * MASK_DRAG_SCALE).toInt().coerceAtLeast(1),
            (src.height * MASK_DRAG_SCALE).toInt().coerceAtLeast(1),
            true,
        )
        maskSource = scaled
        maskSourceFrom = src
        return scaled
    }

    /**
     * Watches how long a drag render takes and shrinks or grows the working copy, so the
     * preview stays responsive on a slow phone without wasting detail on a fast one.
     */
    private fun measureRender(elapsedMs: Long) {
        val full = _original.value ?: return
        val tuned = DeviceProfile.tune(dragSize, elapsedMs, initialDragSize) ?: return
        dragSize = tuned
        viewModelScope.launch {
            dragSource = withContext(Dispatchers.Default) {
                val scale = dragSize / max(full.width, full.height).toFloat()
                if (scale < 1f) {
                    android.graphics.Bitmap.createScaledBitmap(
                        full,
                        (full.width * scale).toInt().coerceAtLeast(1),
                        (full.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                } else full
            }
        }
    }

    /** Called after a slider settles so the full-size preview replaces the fast one. */
    private fun settleRender() {
        if (!pendingFullRender) return
        pendingFullRender = false
        // A short wait keeps a run of small adjustments from paying a full-size render each time.
        settleJob?.cancel()
        settleJob = viewModelScope.launch {
            delay(SETTLE_DELAY_MS)
            scheduleRender(fast = false)
        }
    }

    private var pendingFullRender = false

    private fun persist(p: EditParams) {
        val id = _openId.value ?: return
        val updated = _photos.value.map { if (it.id == id) it.copy(edit = p) else it }
        _photos.value = updated
        viewModelScope.launch(Dispatchers.IO) { repo.save(updated) }
    }

    private fun updateHistoryFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    private fun saveToGallery(bitmap: Bitmap, name: String) {
        val base = name.substringBeforeLast('.')
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "lumen_${System.currentTimeMillis()}_$base.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lumen")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        val stream = checkNotNull(resolver.openOutputStream(uri))
        stream.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
}
