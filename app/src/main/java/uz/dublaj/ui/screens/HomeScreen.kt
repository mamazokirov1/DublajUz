package uz.dublaj.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import uz.dublaj.data.PipelineState
import uz.dublaj.ui.viewmodel.PipelineViewModel
import uz.dublaj.util.OutputUtils
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: PipelineViewModel,
    onEditTranslation: () -> Unit,
    onSettings: () -> Unit
) {
    val state by vm.state.collectAsState()
    val videoUri by vm.videoUri.collectAsState()
    val hasTranscript by vm.hasTranscript.collectAsState()
    val units by vm.translationUnits.collectAsState()
    val approved by vm.approved.collectAsState()
    val models by vm.models.collectAsState()
    val context = LocalContext.current

    val hasTranslation = units.isNotEmpty()
    val running = state is PipelineState.Running

    LaunchedEffect(Unit) { vm.restore(context) }

    // Android 13+: bildirishnoma ruxsati (fon jarayoni xabari uchun)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("DublajUz", fontWeight = FontWeight.Bold)
                        Text("Offline o'zbekcha video dublyaj", style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Sozlamalar")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- modellar holati
            if (!models.whisper || !models.nllb || !models.vits) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    onClick = onSettings
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Modellar tayyor emas", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Whisper ${if (models.whisper) "✓" else "✗"}   " +
                                "NLLB ${if (models.nllb) "✓" else "✗"}   " +
                                "VITS ${if (models.vits) "✓" else "✗"}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text("Sozlamalarda yuklang yoki import qiling →", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ---- video
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Video", fontWeight = FontWeight.SemiBold)
                    Text(
                        videoUri?.lastPathSegment?.substringAfterLast('/') ?: "Tanlanmagan",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = { picker.launch("video/*") }, enabled = !running) {
                        Icon(Icons.Default.VideoFile, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Video tanlash")
                    }
                }
            }

            // ---- transkript + tarjima
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("2. Transkript va tarjima", fontWeight = FontWeight.SemiBold)
                    Button(
                        onClick = { vm.transcribeAndTranslate(context) },
                        enabled = videoUri != null && !running && models.whisper && models.nllb,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Translate, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Transkript + tarjima")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { vm.transcribe(context) },
                            enabled = videoUri != null && !running && models.whisper
                        ) { Text("Faqat transkript") }
                        OutlinedButton(
                            onClick = { vm.translate(context) },
                            enabled = hasTranscript && !running && models.nllb
                        ) { Text("Qayta tarjima") }
                    }
                }
            }

            // ---- tahrir / tasdiq
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("3. Tahrirlash va tasdiqlash", fontWeight = FontWeight.SemiBold)
                    if (hasTranslation) {
                        val pending = units.count { it.check }
                        Text(
                            "${units.size} segment" + if (pending > 0) " • ⚠ $pending ta tekshirilsin" else "",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    OutlinedButton(onClick = onEditTranslation, enabled = hasTranslation && !running) {
                        Icon(Icons.Default.Edit, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Tarjimani tahrirlash")
                    }
                    Button(
                        onClick = { vm.approveTranslation(context) },
                        enabled = hasTranslation && !approved && !running
                    ) {
                        Icon(Icons.Default.Check, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (approved) "Tasdiqlangan ✓" else "Tasdiqlash")
                    }
                }
            }

            // ---- dublyaj
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("4. Dublyaj", fontWeight = FontWeight.SemiBold)
                    Button(
                        onClick = { vm.dub(context) },
                        enabled = approved && !running && models.vits,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Mic, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Dublyaj qilish")
                    }
                }
            }

            // ---- holat
            when (val s = state) {
                is PipelineState.Running -> Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${s.stage}…", fontWeight = FontWeight.SemiBold)
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                        Text(s.message, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Ilovani yopmang — fonda ishlayveradi (bildirishnoma ko'rinadi).",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(onClick = { vm.cancel() }) { Text("Bekor qilish") }
                    }
                }

                is PipelineState.Success -> {
                    val file = File(s.outputPath)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Tayyor! ✓", fontWeight = FontWeight.Bold)
                            Text(file.name, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    try {
                                        context.startActivity(OutputUtils.viewIntent(context, file))
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Video ochuvchi ilova topilmadi", Toast.LENGTH_SHORT).show()
                                    }
                                }) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Ko'rish") }
                                OutlinedButton(onClick = {
                                    context.startActivity(OutputUtils.shareIntent(context, file))
                                }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(4.dp)); Text("Ulashish") }
                            }
                            OutlinedButton(onClick = {
                                val msg = try {
                                    if (OutputUtils.saveToGallery(context, file) != null)
                                        "Galereyaga saqlandi (Movies/DublajUz)"
                                    else "Android 10+ kerak. 'Ulashish' orqali saqlang."
                                } catch (e: Exception) {
                                    "Saqlash xatosi: ${e.message}"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }) { Text("Galereyaga saqlash") }
                        }
                    }
                }

                is PipelineState.Error -> Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Xato", fontWeight = FontWeight.Bold)
                        Text(s.message, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { vm.resetState() }) { Text("Yopish") }
                    }
                }

                PipelineState.Idle -> {}
            }
        }
    }
}
