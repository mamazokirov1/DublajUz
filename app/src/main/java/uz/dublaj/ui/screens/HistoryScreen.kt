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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uz.dublaj.ui.components.*
import uz.dublaj.util.ExportUtils
import uz.dublaj.util.HistoryEntry
import uz.dublaj.util.HistoryStore
import uz.dublaj.util.OutputUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var entries by remember { mutableStateOf<List<HistoryEntry>?>(null) }
    var toDelete by remember { mutableStateOf<HistoryEntry?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        entries = withContext(Dispatchers.IO) { HistoryStore.list(context) }
    }

    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = { Text("Dublyaj tarixi") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Orqaga")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menyu") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Hammasini o'chirish") },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                                enabled = !entries.isNullOrEmpty(),
                                onClick = { menu = false; confirmAll = true }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background)
            )
        }
    ) { padding ->
        val list = entries
        when {
            list == null -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Default.Movie,
                    title = "Hali dublyaj yo'q",
                    text = "Tayyor bo'lgan videolaringiz shu yerda saqlanadi, ilovani yopsangiz ham yo'qolmaydi."
                )
            }
            else -> LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card(
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer.copy(alpha = 0.6f))
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "${list.size} ta video • ${fmtSize(HistoryStore.totalBytes(list))}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Videolar ilova yopilsa ham saqlanadi. Joy kerak bo'lsa, \"Galereyaga ko'chirish\" ni bosing: " +
                                    "video galereyaga o'tadi, ilova nusxasi o'chadi. Ilova o'chirib tashlansa, ilova ichidagi nusxalar yo'qoladi.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                items(list, key = { it.video.absolutePath }) { entry ->
                    HistoryItem(
                        entry,
                        onDelete = { toDelete = entry },
                        onChanged = { refresh++ }
                    )
                }
            }
        }
    }

    toDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("O'chirilsinmi?") },
            text = { Text("Video, subtitr va audio fayllari o'chiriladi. Qaytarib bo'lmaydi.") },
            confirmButton = {
                TextButton(onClick = {
                    HistoryStore.delete(e.video)
                    toDelete = null
                    refresh++
                }) { Text("O'chirish") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Bekor qilish") } }
        )
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Hammasi o'chirilsinmi?") },
            text = { Text("Tarixdagi barcha videolar va ularning fayllari o'chadi. Galereyaga saqlanganlar qoladi.") },
            confirmButton = {
                TextButton(onClick = {
                    HistoryStore.deleteAll(context)
                    confirmAll = false
                    refresh++
                }) { Text("Hammasini o'chirish") }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Bekor qilish") } }
        )
    }
}

private fun dateOf(ms: Long): String =
    SimpleDateFormat("dd.MM.yyyy  HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
private fun HistoryItem(entry: HistoryEntry, onDelete: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val file = entry.video
    val info by rememberVideoInfo(file = file)
    var menu by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(cs.surfaceVariant)) {
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
                Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.title.ifBlank { dateOf(entry.createdAt) },
                        style = MaterialTheme.typography.titleMedium, maxLines = 1
                    )
                    Text(
                        "${dateOf(entry.createdAt)} • ${fmtSize(entry.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                    )
                }
                FilledTonalIconButton(onClick = {
                    try { context.startActivity(OutputUtils.viewIntent(context, file)) }
                    catch (e: Exception) { toast("Video ochuvchi ilova topilmadi") }
                }) { Icon(Icons.Default.PlayArrow, contentDescription = "Ko'rish") }
                IconButton(onClick = { context.startActivity(OutputUtils.shareIntent(context, file)) }) {
                    Icon(Icons.Default.Share, contentDescription = "Ulashish")
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Yana") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Audio (M4A)") },
                            leadingIcon = { Icon(Icons.Default.AudioFile, null) },
                            onClick = {
                                menu = false
                                toast("Audio tayyorlanmoqda…")
                                scope.launch {
                                    try {
                                        val m4a = withContext(Dispatchers.IO) { ExportUtils.ensureM4a(file) }
                                        context.startActivity(ExportUtils.shareIntent(context, m4a, "audio/mp4", "Audio ulashish"))
                                        onChanged()
                                    } catch (e: Exception) {
                                        toast("Audio ajratilmadi: ${e.message}")
                                    }
                                }
                            }
                        )
                        if (entry.hasUzSrt) {
                            DropdownMenuItem(
                                text = { Text("Subtitr (o'zbekcha .srt)") },
                                leadingIcon = { Icon(Icons.Default.Subtitles, null) },
                                onClick = {
                                    menu = false
                                    context.startActivity(
                                        ExportUtils.shareIntent(context, ExportUtils.srtFile(file, true), "application/x-subrip", "Subtitr ulashish")
                                    )
                                }
                            )
                            if (ExportUtils.srtFile(file, false).isFile) {
                                DropdownMenuItem(
                                    text = { Text("Subtitr (asl til .srt)") },
                                    leadingIcon = { Icon(Icons.Default.Subtitles, null) },
                                    onClick = {
                                        menu = false
                                        context.startActivity(
                                            ExportUtils.shareIntent(context, ExportUtils.srtFile(file, false), "application/x-subrip", "Subtitr ulashish")
                                        )
                                    }
                                )
                            }
                        }
                        DropdownMenuItem(
                            text = { Text("Galereyaga saqlash") },
                            leadingIcon = { Icon(Icons.Default.SaveAlt, null) },
                            onClick = {
                                menu = false
                                toast(
                                    try {
                                        if (OutputUtils.saveToGallery(context, file) != null) "Galereyaga saqlandi (Movies/DublajUz)"
                                        else "Android 10+ kerak. 'Ulashish' orqali saqlang."
                                    } catch (e: Exception) { "Saqlash xatosi: ${e.message}" }
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Galereyaga ko'chirish (ilovadan o'chadi)") },
                            leadingIcon = { Icon(Icons.Default.MoveToInbox, null) },
                            onClick = {
                                menu = false
                                scope.launch {
                                    try {
                                        val uri = withContext(Dispatchers.IO) { OutputUtils.saveToGallery(context, file) }
                                        if (uri != null) {
                                            withContext(Dispatchers.IO) { HistoryStore.delete(file) }
                                            toast("Galereyaga ko'chirildi (Movies/DublajUz)")
                                            onChanged()
                                        } else {
                                            toast("Android 10+ kerak. Ko'chirilmadi, video o'chirilmadi.")
                                        }
                                    } catch (e: Exception) {
                                        toast("Ko'chirish xatosi: ${e.message}. Video o'chirilmadi.")
                                    }
                                }
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("O'chirish", color = cs.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = cs.error) },
                            onClick = { menu = false; onDelete() }
                        )
                    }
                }
            }
        }
    }
}
