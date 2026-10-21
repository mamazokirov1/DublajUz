package uz.dublaj.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import uz.dublaj.data.QueueItem
import uz.dublaj.data.QueueStatus
import uz.dublaj.ui.components.EmptyState
import uz.dublaj.ui.viewmodel.PipelineViewModel
import uz.dublaj.util.OutputUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(vm: PipelineViewModel, onBack: () -> Unit) {
    val queue by vm.queue.collectAsState()
    val models by vm.models.collectAsState()
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme

    val running = queue.any { it.status == QueueStatus.RUNNING }
    val hasWaiting = queue.any { it.status == QueueStatus.WAITING }
    val ready = models.whisper && models.nllb && models.vits

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) vm.addToQueue(context, uris)
    }

    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = { Text("Videolar navbati") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Orqaga")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { vm.clearFinishedQueue() },
                        enabled = queue.any { it.status != QueueStatus.WAITING && it.status != QueueStatus.RUNNING }
                    ) { Text("Tugaganlarni tozalash") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background)
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, color = cs.surface) {
                Row(
                    Modifier.navigationBarsPadding().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { picker.launch("video/*") },
                        enabled = !running,
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Video qo'shish")
                    }
                    if (running) {
                        Button(
                            onClick = { vm.cancel() },
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError)
                        ) {
                            Icon(Icons.Default.Stop, null)
                            Spacer(Modifier.width(6.dp))
                            Text("To'xtatish")
                        }
                    } else {
                        Button(
                            onClick = {
                                if (!ready) {
                                    Toast.makeText(context, "Avval uchala modelni (Whisper, NLLB, VITS) tayyorlang", Toast.LENGTH_LONG).show()
                                } else vm.startQueue(context)
                            },
                            enabled = hasWaiting,
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Boshlash")
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
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
                        Text("Avtomatik rejim", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Har bir video ketma-ket: transkript → tarjima → dublyaj. Tarjimani tekshirish yo'q, " +
                                "tarjima qilinmagan gaplar o'tkazib yuboriladi. Navbat ishlayotganda ilovani yopmang. " +
                                "Joriy bitta-video loyihasi navbat boshlanganda tozalanadi.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Tayyor videolar Tarixda saqlanadi.",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer
                        )
                    }
                }
            }
            if (queue.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.Queue,
                        title = "Navbat bo'sh",
                        text = "Bir nechta videoni tanlab, hammasini ketma-ket dublyaj qiling."
                    )
                }
            }
            items(queue, key = { it.id }) { item ->
                QueueRow(item, onRemove = { vm.removeFromQueue(item.id) })
            }
        }
    }
}

@Composable
private fun QueueRow(item: QueueItem, onRemove: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (item.status) {
                    QueueStatus.WAITING -> Icon(Icons.Default.Schedule, null, tint = cs.outline)
                    QueueStatus.RUNNING -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    QueueStatus.DONE -> Icon(Icons.Default.CheckCircle, null, tint = cs.secondary)
                    QueueStatus.FAILED -> Icon(Icons.Default.ErrorOutline, null, tint = cs.error)
                    QueueStatus.CANCELLED -> Icon(Icons.Default.Cancel, null, tint = cs.outline)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        when (item.status) {
                            QueueStatus.WAITING -> "Kutilmoqda"
                            QueueStatus.RUNNING -> "${(item.progress * 100).toInt()}%  ${item.message}"
                            else -> item.message
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.status == QueueStatus.FAILED) cs.error else cs.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                if (item.status != QueueStatus.RUNNING) {
                    IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Olib tashlash") }
                }
            }
            if (item.status == QueueStatus.RUNNING) {
                LinearProgressIndicator(progress = { item.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            if (item.status == QueueStatus.DONE && item.outputPath != null) {
                TextButton(onClick = {
                    try { context.startActivity(OutputUtils.viewIntent(context, File(item.outputPath))) }
                    catch (e: Exception) { Toast.makeText(context, "Video ochuvchi ilova topilmadi", Toast.LENGTH_SHORT).show() }
                }) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ko'rish")
                }
            }
        }
    }
}
