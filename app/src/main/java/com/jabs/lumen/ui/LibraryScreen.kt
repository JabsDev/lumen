package com.jabs.lumen.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.result.PickVisualMediaRequest
import com.jabs.lumen.BuildConfig
import com.jabs.lumen.data.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: LumenViewModel) {
    val photos by vm.photos.collectAsState()
    val update by vm.update.collectAsState()
    // One quiet check per session; the dialog only appears when there is something to install.
    LaunchedEffect(Unit) { vm.checkForUpdates() }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(50)
    ) { uris -> vm.importPhotos(uris) }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.importPhotos(uris) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Biblioteca", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Mais opções")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Verificar atualizações") },
                                onClick = {
                                    menu = false
                                    vm.checkForUpdates(manual = true)
                                },
                            )
                        }
                    }
                    IconButton(onClick = vm::openCamera) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = "Conectar à câmera Lumix")
                    }
                    IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Importar arquivos (RAW, DNG)")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Importar") },
            )
        },
    ) { padding ->
        if (photos.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Importe fotos para começar.\nAs cópias ficam no app, então você edita offline, na viagem.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 88.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(photos, key = { it.id }) { photo ->
                    Thumbnail(vm, photo) { vm.open(photo) }
                }
            }
        }
    }

    if (update !is UpdateState.Idle) UpdateDialog(vm, update)
}

@Composable
private fun UpdateDialog(vm: LumenViewModel, state: UpdateState) {
    AlertDialog(
        onDismissRequest = { vm.dismissUpdate() },
        title = { Text(if (state is UpdateState.Downloading) "Baixando atualização" else "Atualização") },
        text = {
            when (state) {
                UpdateState.Checking -> Text("Procurando atualizações…")
                UpdateState.UpToDate -> Text("Você já está na versão mais recente.")
                is UpdateState.Available -> Column {
                    Text("Versão ${state.info.version} disponível (você tem ${BuildConfig.VERSION_NAME}).")
                    if (state.info.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.info.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                is UpdateState.Downloading -> Text("${state.percent}% concluído…")
                is UpdateState.Ready -> Text("Download concluído. O Android vai pedir para confirmar a instalação.")
                is UpdateState.Failed -> Text(state.message)
                UpdateState.Idle -> {}
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Available -> TextButton(onClick = { vm.downloadUpdate() }) { Text("Atualizar") }
                is UpdateState.Ready -> TextButton(onClick = { vm.installUpdate() }) { Text("Instalar") }
                is UpdateState.Failed, UpdateState.UpToDate ->
                    TextButton(onClick = { vm.dismissUpdate() }) { Text("OK") }
                else -> {}
            }
        },
        dismissButton = {
            if (state is UpdateState.Available || state is UpdateState.Checking) {
                TextButton(onClick = { vm.dismissUpdate() }) { Text("Depois") }
            }
        },
    )
}

@Composable
private fun Thumbnail(vm: LumenViewModel, photo: Photo, onClick: () -> Unit) {
    val bitmap by produceState<Bitmap?>(initialValue = null, photo) {
        value = withContext(Dispatchers.IO) { vm.thumbnail(photo) }
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = photo.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
