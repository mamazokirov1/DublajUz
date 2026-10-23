package uz.dublaj.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import uz.dublaj.data.PipelineState
import uz.dublaj.ui.components.*
import uz.dublaj.ui.theme.findActivity
import uz.dublaj.ui.viewmodel.PipelineViewModel
import uz.dublaj.util.BugReport
import uz.dublaj.util.ExportUtils
import uz.dublaj.util.HistoryEntry
import uz.dublaj.util.HistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uz.dublaj.util.OutputUtils
import java.io.File

@Composable
fun HomeScreen(
    vm: PipelineViewModel,
    onEditTranslation: () -> Unit,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onQueue: () -> Unit,
    onTextTools: () -> Unit = {},
    onPlay: (Uri) -> Unit = {}
) {
    val busy by vm.busy.collectAsState()
    val cancelling by vm.cancelling.collectAsState()
    val host = remember { SnackbarHostState() }
    CollectUiEvents(vm, host)
    val update by vm.update.collectAsState()
    val updateProgress by vm.updateProgress.collectAsState()
    val state by vm.state.collectAsState()
    val videoUri by vm.videoUri.collectAsState()
    val hasTranscript by vm.hasTranscript.collectAsState()
    val units by vm.translationUnits.collectAsState()
    val approved by vm.approved.collectAsState()
    val models by vm.models.collectAsState()
    val modelStatus by vm.modelStatus.collectAsState()
    val config by vm.config.collectAsState()
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme

    val hasTranslation = units.isNotEmpty()
    val running = state is PipelineState.Running
    val translateOk = models.nllb || config.onlineReady
    val modelsReady = models.whisper && translateOk && models.vits
    val autoImport = rememberAutoImportAction(vm)

    LaunchedEffect(Unit) {
        vm.restore(context)
        vm.checkUpdate(context, manual = false)
    }
    val recents by produceState<List<HistoryEntry>>(emptyList(), state) {
        value = withContext(Dispatchers.IO) { HistoryStore.list(context).take(6) }
    }

    // Hero ostida status bar belgilari oq bo'lsin
    val view = LocalView.current
    val darkBg = cs.background.luminance() < 0.5f
    DisposableEffect(darkBg) {
        val window = context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = false
        onDispose { controller?.isAppearanceLightStatusBars = !darkBg }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { vm.setVideo(it) }
    }

    // Joriy bosqich
    val current = when {
        videoUri == null -> 1
        !hasTranslation -> 2
        !approved -> 3
        else -> 4
    }
    fun stepState(i: Int) = when {
        i < current -> StepState.DONE
        i == current -> StepState.ACTIVE
        else -> StepState.LOCKED
    }

    Scaffold(
        containerColor = cs.background,
        snackbarHost = { SnackbarHost(host) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            GradientHero(
                title = "DublajUz",
                subtitle = "Offline o'zbekcha video dublyaj",
                onSettings = onSettings,
                onHistory = onHistory,
                onQueue = onQueue,
                onTools = onTextTools
            )

            Column(
                Modifier.padding(16.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ---- modellar
                if (!modelsReady) {
                    SetupCard(
                        whisper = models.whisper, nllb = translateOk, vits = models.vits,
                        status = modelStatus,
                        busy = busy,
                        onAuto = autoImport,
                        onManual = onSettings
                    )
                }

                // ---- jarayon holati
                when (val s = state) {
                    is PipelineState.Running -> RunningBanner(s, cancelling, onCancel = { vm.cancel() })
                    is PipelineState.Success -> SuccessCard(File(s.outputPath), onPlay)
                    is PipelineState.Error -> ErrorCard(
                        message = s.message,
                        onClose = { vm.resetState() },
                        onReport = {
                            val f = BugReport.build(context, config, models, "Xato: ${s.message}")
                            BugReport.share(context, f)
                        }
                    )
                    PipelineState.Idle -> {}
                }

                // ---- 1. video
                StepCard(
                    number = 1, title = "Video tanlang",
                    summary = videoUri?.lastPathSegment?.substringAfterLast('/'),
                    state = stepState(1)
                ) {
                    val info by rememberVideoInfo(uri = videoUri)
                    if (videoUri == null) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(cs.primaryContainer.copy(alpha = 0.45f))
                                .clickable(enabled = !running) { picker.launch("video/*") }
                                .padding(vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.VideoLibrary, null, tint = cs.primary, modifier = Modifier.size(44.dp))
                            Text("Galereyadan video tanlash", style = MaterialTheme.typography.titleMedium)
                            Text("MP4, MOV, WebM", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    } else {
                        videoUri?.let { VideoPlayer(it, Modifier.fillMaxWidth()) }
                        info?.let {
                            Text(
                                "Davomiyligi: ${fmtDuration(it.durationMs)}",
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = { picker.launch("video/*") }, enabled = !running) {
                            Icon(Icons.Default.VideoFile, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Boshqa video tanlash")
                        }
                    }
                }

                // ---- 2. transkript + tarjima
                StepCard(
                    number = 2, title = "Matn va tarjima",
                    summary = if (hasTranslation) "${units.size} gap tarjima qilindi" else null,
                    state = stepState(2)
                ) {
                    BigButton(
                        text = "Matnni ajratib tarjima qilish",
                        icon = Icons.Default.Translate,
                        enabled = videoUri != null && !running && models.whisper && translateOk,
                        onClick = { vm.transcribeAndTranslate(context) }
                    )
                    if (!models.whisper || !translateOk) {
                        Text(
                            "Avval Sozlamalardan matn aniqlash va tarjima komponentlarini yuklab oling (yoki onlayn tarjimani yoqing).",
                            style = MaterialTheme.typography.bodySmall, color = cs.tertiary
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = { vm.transcribe(context) },
                            enabled = videoUri != null && !running && models.whisper
                        ) { Text("Faqat matn ajratish") }
                        TextButton(
                            onClick = { vm.translate(context) },
                            enabled = hasTranscript && !running && translateOk
                        ) { Text("Qayta tarjima") }
                    }
                }

                // ---- 3. tekshirish
                StepCard(
                    number = 3, title = "Tekshirish va tasdiqlash",
                    summary = if (approved) "Tasdiqlangan" else null,
                    state = stepState(3)
                ) {
                    val pending = units.count { it.check }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {}, label = { Text("${units.size} gap") })
                        if (pending > 0) {
                            AssistChip(
                                onClick = onEditTranslation,
                                label = { Text("⚠ $pending tekshirilsin") }
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onEditTranslation,
                        enabled = hasTranslation && !running,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Default.Edit, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Tarjimani tahrirlash")
                    }
                    BigButton(
                        text = if (approved) "Tasdiqlangan ✓" else "Tasdiqlash",
                        icon = Icons.Default.Check,
                        enabled = hasTranslation && !approved && !running,
                        onClick = { vm.approveTranslation(context) }
                    )
                }

                // ---- 4. dublyaj
                StepCard(
                    number = 4, title = "Dublyaj",
                    summary = null,
                    state = stepState(4)
                ) {
                    BigButton(
                        text = "Dublyaj qilish",
                        icon = Icons.Default.Mic,
                        enabled = approved && !running && models.vits,
                        onClick = { vm.dub(context) }
                    )
                    if (!models.vits) {
                        Text(
                            "O'zbekcha ovoz fayllari kerak (Sozlamalar bo'limida qo'shing).",
                            style = MaterialTheme.typography.bodySmall, color = cs.tertiary
                        )
                    }
                    TextButton(onClick = onSettings) {
                        Icon(Icons.Default.GraphicEq, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Ovoz sozlamalari va tinglab ko'rish")
                    }
                }

                FilledTonalButton(
                    onClick = onTextTools,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.RecordVoiceOver, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Matn va ovoz: videodan matn / matndan audio")
                }

                // ---- so'nggi dublyajlar (ilova yopilsa ham saqlanadi)
                if (recents.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "So'nggi dublyajlar",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onHistory) { Text("Hammasi") }
                    }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        recents.forEach { RecentCard(it) }
                    }
                }
            }
        }
    }

    update?.let { u ->
        AlertDialog(
            onDismissRequest = { vm.dismissUpdate(context, false) },
            title = { Text("Yangi versiya mavjud") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(u.versionName, style = MaterialTheme.typography.titleMedium)
                    if (u.notes.isNotBlank()) Text(u.notes.take(400), style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Ilova o'zi yuklab oladi. O'rnatishda \"Yangilash\" ni bosing — modellar va tarix saqlanadi.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    updateProgress?.let { p ->
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = updateProgress == null,
                    onClick = { vm.downloadUpdate(context) }
                ) { Text(if (updateProgress != null) "Yuklanmoqda…" else "Yuklab olish va o'rnatish") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.dismissUpdate(context, true) }) { Text("O'tkazib yuborish") }
                    TextButton(onClick = { vm.dismissUpdate(context, false) }) { Text("Keyinroq") }
                }
            }
        )
    }
}

@Composable
private fun RecentCard(entry: HistoryEntry) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val info by rememberVideoInfo(file = entry.video)
    Card(
        modifier = Modifier.width(160.dp).clickable {
            try { context.startActivity(OutputUtils.viewIntent(context, entry.video)) }
            catch (e: Exception) { Toast.makeText(context, "Video ochuvchi ilova topilmadi", Toast.LENGTH_SHORT).show() }
        },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = cs.surface)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(cs.surfaceVariant)) {
            info?.thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Icon(
                Icons.Default.PlayCircle, null, tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.Center).size(34.dp)
            )
        }
        Column(Modifier.padding(10.dp)) {
            Text(
                entry.title.ifBlank { "Dublyaj" }, style = MaterialTheme.typography.labelLarge, maxLines = 1
            )
            Text(fmtSize(entry.sizeBytes), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun BigButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SetupCard(
    whisper: Boolean, nllb: Boolean, vits: Boolean,
    status: String?, busy: Boolean,
    onAuto: () -> Unit, onManual: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = cs.tertiaryContainer.copy(alpha = 0.6f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = cs.tertiary)
                Spacer(Modifier.width(8.dp))
                Text("Dastlabki sozlash", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Ilova internetsiz ishlaydi. Buning uchun 3 ta komponentni bir marta yuklab olish kerak: matn aniqlash, tarjima va o'zbekcha ovoz.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill("Matn aniqlash", whisper)
                StatusPill("Tarjima", nllb)
                StatusPill("Ovoz", vits)
            }
            if (status != null) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = cs.primary)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAuto, enabled = !busy) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Avtomatik topish")
                }
                OutlinedButton(onClick = onManual) { Text("Qo'lda") }
            }
        }
    }
}

@Composable
private fun SuccessCard(file: File, onPlay: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(cs.secondary),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Check, null, tint = cs.onSecondary) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Dublyaj tayyor!", style = MaterialTheme.typography.titleLarge, color = cs.onSecondaryContainer)
                    Text("Tarixda saqlandi ✓", style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer.copy(alpha = 0.8f))
                }
            }
            VideoPlayer(Uri.fromFile(file), Modifier.fillMaxWidth())
            Button(
                onClick = { onPlay(Uri.fromFile(file)) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.Fullscreen, null)
                Spacer(Modifier.width(8.dp))
                Text("Butun ekranda ko'rish", style = MaterialTheme.typography.titleMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { context.startActivity(OutputUtils.shareIntent(context, file)) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ulashish")
                }
                FilledTonalButton(
                    onClick = {
                        val msg = try {
                            if (OutputUtils.saveToGallery(context, file) != null) "Galereyaga saqlandi (Movies/DublajUz)"
                            else "Android 10+ kerak. 'Ulashish' orqali saqlang."
                        } catch (e: Exception) { "Saqlash xatosi: ${e.message}" }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.SaveAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Galereya")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        Toast.makeText(context, "Audio tayyorlanmoqda…", Toast.LENGTH_SHORT).show()
                        scope.launch {
                            try {
                                val m4a = withContext(Dispatchers.IO) { ExportUtils.ensureM4a(file) }
                                context.startActivity(ExportUtils.shareIntent(context, m4a, "audio/mp4", "Audio ulashish"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "Audio ajratilmadi: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.AudioFile, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Audio")
                }
                FilledTonalButton(
                    onClick = {
                        val srt = ExportUtils.srtFile(file, true)
                        if (srt.isFile) context.startActivity(ExportUtils.shareIntent(context, srt, "application/x-subrip", "Subtitr ulashish"))
                        else Toast.makeText(context, "Subtitr topilmadi", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Subtitles, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Subtitr")
                }
            }
        }
    }
}

private fun hintFor(message: String): String? {
    val m = message.lowercase()
    return when {
        "vits" in m || "ovoz komponenti" in m -> "Sozlamalar bo'limida o'zbekcha ovoz fayllarini (.onnx va .json) qo'shing."
        "tokenizer" in m || "nllb" in m || "tarjima komponenti" in m -> "Sozlamalar bo'limida tarjima komponentini qaytadan yuklab oling."
        "joy" in m || "space" in m || "xotira" in m -> "Telefonda bo'sh joy yetarli emas. Sozlamalar → Xotira bo'limida vaqtinchalik fayllarni tozalang."
        "kodek" in m || "codec" in m -> "Videoni H.264 (MP4) formatiga o'giring yoki boshqa video tanlang."
        "whisper" in m || "matn aniqlash" in m -> "Sozlamalar bo'limida matn aniqlash komponentini tekshiring."
        else -> null
    }
}

@Composable
private fun ErrorCard(message: String, onClose: () -> Unit, onReport: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = cs.errorContainer)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ErrorOutline, null, tint = cs.error)
                Spacer(Modifier.width(8.dp))
                Text("Xatolik yuz berdi", style = MaterialTheme.typography.titleMedium, color = cs.onErrorContainer)
            }
            Text(message, style = MaterialTheme.typography.bodyMedium, color = cs.onErrorContainer)
            hintFor(message)?.let {
                Surface(shape = MaterialTheme.shapes.small, color = cs.surface.copy(alpha = 0.6f)) {
                    Text("Nima qilish kerak: $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onReport) { Text("Hisobot yuborish") }
                TextButton(onClick = onClose) { Text("Yopish") }
            }
        }
    }
}
