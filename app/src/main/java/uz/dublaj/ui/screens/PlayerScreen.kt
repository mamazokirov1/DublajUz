package uz.dublaj.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import uz.dublaj.ui.components.VideoPlayer
import uz.dublaj.ui.viewmodel.PipelineViewModel

/** To'liq ekran video ijrosi (tarix va natija uchun). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: PipelineViewModel, onBack: () -> Unit) {
    val uri by vm.playerUri.collectAsState()
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("Videoni ko'rish") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Orqaga")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier.padding(padding).fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            uri?.let { VideoPlayer(it, Modifier.fillMaxWidth(), autoPlay = true) }
                ?: Text("Video tanlanmagan", color = Color.White)
        }
    }
}
