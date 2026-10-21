package uz.dublaj.ui.screens

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uz.dublaj.ui.components.*
import uz.dublaj.util.OutputUtils
import uz.dublaj.util.WorkDir
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<File>?>(null) }
    var toDelete by remember { mutableStateOf<File?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        files = withContext(Dispatchers.IO) {
            WorkDir(context.applicationContext).output
                .listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Dublyaj tarixi") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Orqaga")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        val list = files
        when {
            list == null -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Default.Movie,
                    title = "Hali dublyaj yo'q",
                    text = "Tayyor bo'lgan videolaringiz shu yerda saqlanadi."
                )
            }
            else -> LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(list, key = { it.absolutePath }) { file ->
                    HistoryItem(file, onDelete = { toDelete = file })
                }
            }
        }
    }

    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("O'chirilsinmi?") },
            text = { Text("${f.name} qaytarib bo'lmaydi.") },
            confirmButton = {
                TextButton(onClick = {
                    f.delete()
                    toDelete = null
                    refresh++
                }) { Text("O'chirish") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Bekor qilish") } }
        )
    }
}

private fun titleOf(file: File): String {
    val ms = Regex("dublaj_(\\d+)\\.mp4").matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()
        ?: file.lastModified()
    return SimpleDateFormat("dd.MM.yyyy  HH:mm", Locale.getDefault()).format(Date(ms))
}

@Composable
private fun HistoryItem(file: File, onDelete: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val info by rememberVideoInfo(file = file)
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = cs.surface)
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(cs.surfaceVariant)
            ) {
                info?.thumb?.let {
                    Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                info?.let {
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = MaterialTheme.shapes.extraSmall,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    ) {
                        Text(
                            fmtDuration(it.durationMs), color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Row(
                Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(titleOf(file), style = MaterialTheme.typography.titleMedium)
                    Text(fmtSize(file.length()), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                FilledTonalIconButton(onClick = {
                    try { context.startActivity(OutputUtils.viewIntent(context, file)) }
                    catch (e: Exception) { Toast.makeText(context, "Video ochuvchi ilova topilmadi", Toast.LENGTH_SHORT).show() }
                }) { Icon(Icons.Default.PlayArrow, contentDescription = "Ko'rish") }
                IconButton(onClick = { context.startActivity(OutputUtils.shareIntent(context, file)) }) {
                    Icon(Icons.Default.Share, contentDescription = "Ulashish")
                }
                IconButton(onClick = {
                    val msg = try {
                        if (OutputUtils.saveToGallery(context, file) != null) "Galereyaga saqlandi (Movies/DublajUz)"
                        else "Android 10+ kerak. 'Ulashish' orqali saqlang."
                    } catch (e: Exception) { "Saqlash xatosi: ${e.message}" }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }) { Icon(Icons.Default.SaveAlt, contentDescription = "Galereyaga") }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "O'chirish", tint = cs.error)
                }
            }
        }
    }
}
