package com.jabs.lumen.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jabs.lumen.camera.CameraItem

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "rw2", "raw", "dng", "heif", "heic")

@Composable
fun CameraScreen(vm: LumenViewModel) {
    val status by vm.cameraStatus.collectAsState()
    val connected by vm.cameraConnected.collectAsState()
    val files by vm.cameraFiles.collectAsState()
    var showFiles by rememberSaveable { mutableStateOf(false) }

    if (!showFiles) {
        ControlScreen(vm, onBack = vm::closeCamera, onFiles = { vm.stopLiveView(); showFiles = true })
        return
    }

    BackHandler(enabled = true) { showFiles = false }

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
            IconButton(onClick = { showFiles = false }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
            }
            Text("Fotos da câmera", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        FilesTab(vm, connected, files)
    }
}

@Composable
private fun FilesTab(vm: LumenViewModel, connected: Boolean, files: List<CameraItem>) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    var preview by remember { mutableStateOf<CameraItem?>(null) }
    val images = files.filter { !it.isContainer && extensionOf(it) in IMAGE_EXTENSIONS }
    val progress by vm.importProgress.collectAsState()
    val opened = preview

    BackHandler(enabled = opened != null || selected.isNotEmpty()) {
        if (opened != null) preview = null else selected = emptySet()
    }

    if (opened != null) {
        PreviewPane(
            vm = vm,
            item = opened,
            progress = progress,
            onClose = { preview = null },
            onEdit = { vm.importFromCamera(listOf(opened), openAfter = true) },
        )
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp)) {
            OutlinedButton(
                onClick = vm::refreshCameraFiles,
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Atualizar lista") }
            Spacer(Modifier.height(8.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(images, key = { it.url }) { item ->
                    GalleryCell(
                        vm = vm,
                        item = item,
                        selectionMode = selected.isNotEmpty(),
                        checked = item.url in selected,
                        onClick = {
                            if (selected.isEmpty()) {
                                preview = item
                            } else {
                                selected = if (item.url in selected) selected - item.url else selected + item.url
                            }
                        },
                        onLongClick = { selected = selected + item.url },
                    )
                }
            }

            if (selected.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { selected = emptySet() }) { Text("Cancelar") }
                    Button(
                        onClick = {
                            vm.importFromCamera(images.filter { it.url in selected })
                            selected = emptySet()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Importar ${selected.size}") }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryCell(
    vm: LumenViewModel,
    item: CameraItem,
    selectionMode: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val thumb by produceState<Bitmap?>(initialValue = null, item.thumbUrl) {
        value = vm.cameraThumbnail(item)
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        thumb?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (checked) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)))
        }
        if (selectionMode) {
            Icon(
                imageVector = if (checked) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = if (checked) "Selecionada" else "Não selecionada",
                tint = if (checked) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            )
        }
    }
}

@Composable
private fun PreviewPane(
    vm: LumenViewModel,
    item: CameraItem,
    progress: ImportProgress?,
    onClose: () -> Unit,
    onEdit: () -> Unit,
) {
    val large by produceState<Bitmap?>(initialValue = null, item.url) {
        value = vm.cameraPreview(item)
    }
    val busy = progress != null
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, enabled = !busy) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Fechar", tint = Color.White)
            }
            Text(
                item.title,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            large?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } ?: CircularProgressIndicator(color = Color.White)
        }
        if (busy) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (progress.total > 0L) "Importando para o celular..." else "Preparando a edição...",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (progress.total > 0L) progress.megabytes else "Decodificando o RAW",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        } else {
            Button(
                onClick = onEdit,
                modifier = Modifier.fillMaxWidth().padding(20.dp),
            ) { Text("Editar") }
        }
    }
}

private fun extensionOf(item: CameraItem): String {
    val fromUrl = item.url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
    val source = if (fromUrl.isNotEmpty()) fromUrl else item.title.substringAfterLast('.', "")
    return source.lowercase()
}
