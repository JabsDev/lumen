package com.jabs.lumen

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jabs.lumen.ui.CameraScreen
import com.jabs.lumen.ui.EditorScreen
import com.jabs.lumen.ui.LibraryScreen
import com.jabs.lumen.ui.LumenTheme
import com.jabs.lumen.ui.LumenViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LumenTheme {
                val vm: LumenViewModel = viewModel()
                val photo by vm.openPhoto.collectAsState()
                val showCamera by vm.showCamera.collectAsState()
                val context = LocalContext.current

                LaunchedEffect(vm) {
                    vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                }

                val current = photo
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    when {
                        current != null -> {
                            BackHandler { vm.close() }
                            EditorScreen(vm, current)
                        }
                        showCamera -> {
                            BackHandler { vm.closeCamera() }
                            CameraScreen(vm)
                        }
                        else -> LibraryScreen(vm)
                    }
                }
            }
        }
    }
}
