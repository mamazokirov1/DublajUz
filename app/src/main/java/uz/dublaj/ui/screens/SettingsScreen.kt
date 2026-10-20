package uz.dublaj.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uz.dublaj.stt.WhisperRecognizer
import uz.dublaj.translate.Languages
import uz.dublaj.ui.viewmodel.PipelineViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: PipelineViewModel,
    onBack: () -> Unit
) {
    val config by vm.config.collectAsState()
    val models by vm.models.collectAsState()
    val status by vm.modelStatus.collectAsState()
    val context = LocalContext.current

    var usedMb by remember { mutableStateOf(0L) }
    LaunchedEffect(status) {
        vm.refreshModels(context)
        usedMb = vm.usedBytes(context) / (1024 * 1024)
    }

    val whisperPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.importWhisper(context, it) }
    }
    val vitsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.importVitsModel(context, it) }
    }
    val vitsCfgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.importVitsConfig(context, it) }
    }
    val nllbPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { vm.importNllb(context, it) }
    }

    val nllbFilesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) vm.importNllbFiles(context, uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sozlamalar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Orqaga")
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---------------- modellar
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Modellar", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Whisper ${if (models.whisper) "✓" else "✗"}   NLLB ${if (models.nllb) "✓" else "✗"}   " +
                            "VITS ${if (models.vits) "✓" else "✗"}"
                    )
                    status?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        if (!it.startsWith("Tayyor") && !it.startsWith("Xato") && !it.startsWith("Bekor")) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            OutlinedButton(onClick = { vm.cancel() }) { Text("Bekor qilish") }
                        }
                    }

                    Text("Whisper (STT)", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WhisperRecognizer.MODEL_OPTIONS.forEach { m ->
                            FilterChip(
                                selected = config.whisperModel == m.id,
                                onClick = { vm.setConfig(config.copy(whisperModel = m.id), context) },
                                label = { Text("${m.id} ${m.sizeMb}MB") }
                            )
                        }
                    }
                    Text(
                        WhisperRecognizer.info(config.whisperModel).note,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.downloadWhisper(context) }, enabled = !vm.isBusy) { Text("Yuklash") }
                        OutlinedButton(
                            onClick = { whisperPicker.launch(arrayOf("*/*")) },
                            enabled = !vm.isBusy
                        ) { Text("Fayldan import") }
                    }

                    HorizontalDivider()
                    Text("NLLB (tarjima, ~1 GB)", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Litsenziya: CC-BY-NC (faqat notijorat foydalanish).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.downloadNllb(context) }, enabled = !vm.isBusy) { Text("Yuklash") }
                        OutlinedButton(onClick = { nllbPicker.launch(null) }, enabled = !vm.isBusy) {
                            Text("Papkadan import")
                        }
                    }
                    OutlinedButton(onClick = { nllbFilesPicker.launch(arrayOf("*/*")) }, enabled = !vm.isBusy) {
                        Text("Fayllarni tanlab import (5 ta)")
                    }
                    Text(
                        "config.json, tokenizer.json, tokenizer_config.json, " +
                            "encoder_model_quantized.onnx, decoder_model_merged_quantized.onnx — hammasini birdan belgilang.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    HorizontalDivider()
                    Text("VITS (o'zbekcha TTS)", style = MaterialTheme.typography.labelLarge)
                    Text("vits_uzbek.onnx va uz_tts_base.json fayllarini tanlang.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vitsPicker.launch(arrayOf("*/*")) }, enabled = !vm.isBusy) {
                            Text(".onnx")
                        }
                        OutlinedButton(onClick = { vitsCfgPicker.launch(arrayOf("*/*")) }, enabled = !vm.isBusy) {
                            Text(".json config")
                        }
                    }
                }
            }

            // ---------------- til
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Video tili", fontWeight = FontWeight.SemiBold)
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { expanded = true }) { Text(Languages.label(config.sourceLang)) }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            Languages.UI_OPTIONS.forEach { (code, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        vm.setConfig(config.copy(sourceLang = code))
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                    Text(
                        "Ru/En dan boshqa tillar uchun tilni aniq tanlang.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // ---------------- ovoz
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ovoz va vaqt", fontWeight = FontWeight.SemiBold)

                    Text("Asl ovoz balandligi: ${(config.originalVolume * 100).roundToInt()}%")
                    Slider(
                        value = config.originalVolume,
                        onValueChange = { vm.setConfig(config.copy(originalVolume = it)) },
                        valueRange = 0f..0.5f
                    )

                    Text("O'zbekcha ovoz: ${(config.uzbekVolume * 100).roundToInt()}%")
                    Slider(
                        value = config.uzbekVolume,
                        onValueChange = { vm.setConfig(config.copy(uzbekVolume = it)) },
                        valueRange = 0.5f..1.5f
                    )

                    Text("Maks. tezlashtirish: ${"%.2f".format(config.maxAtempo)}×")
                    Slider(
                        value = config.maxAtempo,
                        onValueChange = { vm.setConfig(config.copy(maxAtempo = it)) },
                        valueRange = 1.0f..1.6f
                    )

                    Text("CPU oqimlari: ${config.threads}")
                    Slider(
                        value = config.threads.toFloat(),
                        onValueChange = { vm.setConfig(config.copy(threads = it.roundToInt())) },
                        valueRange = 1f..8f,
                        steps = 6
                    )
                }
            }

            // ---------------- xotira
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Xotira", fontWeight = FontWeight.SemiBold)
                    Text("Vaqtinchalik fayllar: $usedMb MB (video nusxalari, oraliq audio)")
                    OutlinedButton(onClick = { vm.clearWork(context) }, enabled = !vm.isBusy) {
                        Text("Vaqtinchalik fayllarni tozalash")
                    }
                    Text(
                        "Modellar va tayyor videolar o'chirilmaydi.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
