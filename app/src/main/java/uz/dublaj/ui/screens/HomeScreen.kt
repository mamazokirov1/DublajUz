package uz.dublaj.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import uz.dublaj.util.OutputUtils
import java.io.File

@Composable
fun HomeScreen(
    vm: PipelineViewModel,
    onEditTranslation: () -> Unit,
    onSettings: () -> Unit,
    onHistory: () -> Unit
) {
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
    val modelsReady = models.whisper && models.nllb && models.vits
    val autoImport = rememberAutoImportAction(vm)

    LaunchedEffect(Unit) { vm.restore(context) }

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
                onHistory = onHistory
            )

            Column(
                Modifier.padding(16.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ---- modellar
                if (!modelsReady) {
                    SetupCard(
                        whisper = models.whisper, nllb = models.nllb, vits = models.vits,
                        status = modelStatus,
                        busy = vm.isBusy,
                        onAuto = autoImport,
                        onManual = onSettings
                    )
                }

                // ---- jarayon holati
                when (val s = state) {
                    is PipelineState.Running -> RunningCard(s, onCancel = { vm.cancel() })
                    is PipelineState.Success -> SuccessCard(File(s.outputPath))
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
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(MaterialTheme.shapes.medium)
                                .background(cs.surfaceVariant)
                        ) {
                            info?.thumb?.let {
                                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                            info?.let {
                                Surface(
                                    color = Color.Black.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(8.dp),
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
                        OutlinedButton(onClick = { picker.launch("video/*") }, enabled = !running) {
                            Icon(Icons.Default.VideoFile, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Boshqa video tanlash")
                        }
                    }
                }

                // ---- 2. transkript + tarjima
                StepCard(
                    number = 2, title = "Transkript va tarjima",
                    summary = if (hasTranslation) "${units.size} gap tarjima qilindi" else null,
                    state = stepState(2)
                ) {
                    BigButton(
                        text = "Transkript + tarjima",
                        icon = Icons.Default.Translate,
                        enabled = videoUri != null && !running && models.whisper && models.nllb,
                        onClick = { vm.transcribeAndTranslate(context) }
                    )
                    if (!models.whisper || !models.nllb) {
                        Text(
                            "Avval Whisper va NLLB modellarini tayyorlang.",
                            style = MaterialTheme.typography.bodySmall, color = cs.tertiary
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = { vm.transcribe(context) },
                            enabled = videoUri != null && !running && models.whisper
                        ) { Text("Faqat transkript") }
                        TextButton(
                            onClick = { vm.translate(context) },
                            enabled = hasTranscript && !running && models.nllb
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
                            "VITS (o'zbekcha ovoz) modeli kerak.",
                            style = MaterialTheme.typography.bodySmall, color = cs.tertiary
                        )
                    }
                    TextButton(onClick = onSettings) {
                        Icon(Icons.Default.GraphicEq, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Ovoz sozlamalari va tinglab ko'rish")
                    }
                }
            }
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
                Text("Modellarni tayyorlang", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Ilova to'liq offline ishlaydi, buning uchun 3 ta model kerak.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill("Whisper", whisper)
                StatusPill("NLLB", nllb)
                StatusPill("VITS", vits)
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
private fun RunningCard(s: PipelineState.Running, onCancel: () -> Unit) {
    val progress by animateFloatAsState(s.progress.coerceIn(0f, 1f), label = "progress")
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(Color(0xFF4A2FD0), Color(0xFF7B4DFF), Color(0xFF00A6A6))))
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${s.stage}…", color = Color.White,
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
                )
                Text(
                    "${(progress * 100).toInt()}%", color = Color.White,
                    style = MaterialTheme.typography.headlineMedium
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f)
            )
            Text(s.message, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
            Text(
                "Ilovani yopmang, u fonda ishlayveradi (bildirishnoma ko'rinadi).",
                color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = onCancel) { Text("Bekor qilish", color = Color.White) }
        }
    }
}

@Composable
private fun SuccessCard(file: File) {
    val context = LocalContext.current
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
                    Text(file.name, style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer.copy(alpha = 0.8f))
                }
            }
            Button(
                onClick = {
                    try { context.startActivity(OutputUtils.viewIntent(context, file)) }
                    catch (e: Exception) { Toast.makeText(context, "Video ochuvchi ilova topilmadi", Toast.LENGTH_SHORT).show() }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("Ko'rish", style = MaterialTheme.typography.titleMedium)
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
        }
    }
}

private fun hintFor(message: String): String? {
    val m = message.lowercase()
    return when {
        "vits" in m -> "Sozlamalar → Modellar bo'limida VITS modelini va uz_tts_base.json ni import qiling."
        "tokenizer" in m || "nllb" in m -> "Sozlamalar → Modellar bo'limida NLLB modelini qayta import qiling."
        "joy" in m || "space" in m || "xotira" in m -> "Telefonda bo'sh joy yetarli emas. Sozlamalar → Xotira dan vaqtinchalik fayllarni tozalang."
        "kodek" in m || "codec" in m -> "Videoni H.264 (MP4) formatiga o'giring yoki boshqa video tanlang."
        "whisper" in m -> "Sozlamalar → Modellar bo'limida Whisper modelini tekshiring."
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
