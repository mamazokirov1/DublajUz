package uz.dublaj.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uz.dublaj.ui.theme.Brand
import uz.dublaj.ui.viewmodel.PipelineViewModel
import uz.dublaj.util.StorageScanner
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

// ---------------------------------------------------------------- Hero

@Composable
fun GradientHero(
    title: String,
    subtitle: String,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onQueue: () -> Unit,
    onTools: (() -> Unit)? = null
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
            .background(Brush.linearGradient(listOf(Brand.Indigo, Brand.Violet, Brand.Teal)))
    ) {
        Canvas(Modifier.matchParentSize()) {
            val n = 30
            val w = size.width / n
            for (i in 0 until n) {
                val h = size.height * (0.18f + 0.42f * abs(sin(i * 0.62f + 0.5f)))
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.10f),
                    topLeft = Offset(i * w + w * 0.25f, size.height - h),
                    size = Size(w * 0.5f, h),
                    cornerRadius = CornerRadius(w * 0.25f)
                )
            }
        }
        Row(
            Modifier
                .statusBarsPadding()
                .padding(start = 22.dp, end = 10.dp, top = 14.dp, bottom = 26.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
            }
            if (onTools != null) {
                IconButton(onClick = onTools) {
                    Icon(Icons.Default.RecordVoiceOver, contentDescription = "Matn va ovoz", tint = Color.White)
                }
            }
            IconButton(onClick = onQueue) {
                Icon(Icons.Default.Queue, contentDescription = "Navbat", tint = Color.White)
            }
            IconButton(onClick = onHistory) {
                Icon(Icons.Default.History, contentDescription = "Tarix", tint = Color.White)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Sozlamalar", tint = Color.White)
            }
        }
    }
}

// ---------------------------------------------------------------- Bosqich kartasi

enum class StepState { DONE, ACTIVE, LOCKED }

@Composable
fun StepCard(
    number: Int,
    title: String,
    summary: String?,
    state: StepState,
    content: @Composable ColumnScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var userExpanded by remember { mutableStateOf(false) }
    val expanded = state == StepState.ACTIVE || (state == StepState.DONE && userExpanded)
    val container by animateColorAsState(
        when (state) {
            StepState.LOCKED -> cs.surfaceVariant.copy(alpha = 0.45f)
            else -> cs.surface
        },
        label = "stepContainer"
    )
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "stepArrow")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
        border = if (state == StepState.ACTIVE) BorderStroke(1.5.dp, cs.primary) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = if (state == StepState.ACTIVE) 3.dp else 0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (state == StepState.DONE) Modifier.clickable { userExpanded = !userExpanded } else Modifier),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StepBadge(number, state)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (state == StepState.LOCKED) cs.onSurfaceVariant.copy(alpha = 0.7f) else cs.onSurface
                    )
                    if (!summary.isNullOrBlank() && !expanded) {
                        Text(summary, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                    }
                }
                if (state == StepState.DONE) {
                    Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.rotate(rotation), tint = cs.onSurfaceVariant)
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier.padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun StepBadge(number: Int, state: StepState) {
    val cs = MaterialTheme.colorScheme
    val bg = when (state) {
        StepState.DONE -> cs.secondary
        StepState.ACTIVE -> cs.primary
        StepState.LOCKED -> cs.outlineVariant
    }
    Box(Modifier.size(36.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        when (state) {
            StepState.DONE -> Icon(Icons.Default.Check, null, tint = cs.onSecondary, modifier = Modifier.size(20.dp))
            StepState.LOCKED -> Icon(Icons.Default.Lock, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            StepState.ACTIVE -> Text("$number", color = cs.onPrimary, fontWeight = FontWeight.Bold)
        }
    }
}

// ---------------------------------------------------------------- Kichik komponentlar

@Composable
fun StatusPill(label: String, ok: Boolean) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (ok) cs.secondaryContainer else cs.tertiaryContainer
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(8.dp).clip(CircleShape).background(if (ok) cs.secondary else cs.tertiary)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (ok) cs.onSecondaryContainer else cs.onTertiaryContainer
            )
        }
    }
}

@Composable
fun SectionCard(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "sectionArrow")
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = cs.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(cs.primaryContainer),
                    contentAlignment = Alignment.Center
                ) { Icon(icon, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                Icon(Icons.Default.ExpandMore, null, modifier = Modifier.rotate(rotation), tint = cs.onSurfaceVariant)
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier.padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content
                )
            }
        }
    }
}

/** Sarlavha + qiymat + (i) tushuntirish tugmasi. Tushuntirish bosilganda ochiladi — ekran toza qoladi. */
@Composable
fun HelpSlider(
    title: String,
    valueText: String,
    help: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var showHelp by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Surface(shape = CircleShape, color = cs.primaryContainer) {
                Text(
                    valueText,
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
            IconButton(onClick = { showHelp = !showHelp }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Info, contentDescription = "Tushuntirish", tint = cs.outline, modifier = Modifier.size(20.dp))
            }
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps
        )
        AnimatedVisibility(visible = showHelp) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = cs.secondaryContainer.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(help, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(cs.primaryContainer),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(44.dp)) }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- Yordamchilar

fun fmtDuration(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

fun fmtTime(sec: Double): String {
    val s = sec.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

fun fmtSize(bytes: Long): String =
    if (bytes >= 1024L * 1024L * 1024L) "%.1f GB".format(bytes / 1073741824.0)
    else "%.1f MB".format(bytes / 1048576.0)

data class VideoInfo(val thumb: Bitmap?, val durationMs: Long)

private fun loadInfo(context: Context, uri: Uri?, file: File?): VideoInfo? {
    val r = MediaMetadataRetriever()
    return try {
        if (file != null) r.setDataSource(file.absolutePath) else r.setDataSource(context, uri!!)
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val frame = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        val thumb = frame?.let { b ->
            val w = 640
            if (b.width > w) Bitmap.createScaledBitmap(b, w, (b.height * w.toFloat() / b.width).toInt().coerceAtLeast(1), true) else b
        }
        VideoInfo(thumb, dur)
    } catch (e: Exception) {
        null
    } finally {
        try { r.release() } catch (_: Exception) {}
    }
}

@Composable
fun rememberVideoInfo(uri: Uri? = null, file: File? = null): State<VideoInfo?> {
    val context = LocalContext.current
    return produceState<VideoInfo?>(null, uri, file) {
        value = if (uri == null && file == null) null
        else withContext(Dispatchers.IO) { loadInfo(context.applicationContext, uri, file) }
    }
}

/** "Download papkadan avtomatik import" — ruxsatni so'raydi, so'ng modellarni topib nusxalaydi. */
@Composable
fun rememberAutoImportAction(vm: PipelineViewModel): () -> Unit {
    val context = LocalContext.current
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (StorageScanner.hasAccess(context)) vm.autoImport(context)
    }
    val legacyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.autoImport(context)
    }
    return {
        if (StorageScanner.hasAccess(context)) {
            vm.autoImport(context)
        } else if (Build.VERSION.SDK_INT >= 30) {
            try {
                accessLauncher.launch(
                    Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                )
            } catch (e: Exception) {
                accessLauncher.launch(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
