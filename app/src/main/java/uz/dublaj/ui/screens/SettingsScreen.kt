package uz.dublaj.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import uz.dublaj.data.VoicePreset
import uz.dublaj.stt.WhisperRecognizer
import uz.dublaj.util.BugReport
import uz.dublaj.translate.Languages
import uz.dublaj.ui.viewmodel.PipelineViewModel
import uz.dublaj.util.StorageScanner
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

    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (StorageScanner.hasAccess(context)) vm.autoImport(context)
    }
    val legacyPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.autoImport(context)
    }
    val startAutoImport: () -> Unit = {
        if (StorageScanner.hasAccess(context)) {
            vm.autoImport(context)
        } else if (Build.VERSION.SDK_INT >= 30) {
            try {
                accessLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                )
            } catch (e: Exception) {
                accessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyPermLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
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

                    Button(onClick = { startAutoImport() }, enabled = !vm.isBusy) {
                        Text("Download papkadan avtomatik import")
                    }
                    Text(
                        "Download (va boshqa papkalar)dan ggml-*.bin, nllb papkasi, vits_uzbek.onnx, " +
                            "uz_tts_base.json ni o'zi topib nusxalaydi. Birinchi marta \"Barcha fayllarga kirish\" " +
                            "ruxsatini yoqing.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    HorizontalDivider()
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

            // ---------------- ovoz sifati
            val preview by vm.previewState.collectAsState()
            var sample by remember {
                mutableStateOf("Assalomu alaykum! Bu ovoz sinovi. Tabiiy chiqayotganini tinglab ko'ring.")
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ovoz sifati", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Tayyor sozlamani tanlang, so'ng quyida tinglab ko'rib, slayderlar bilan aniqlashtiring.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VoicePreset.values().forEach { p ->
                            OutlinedButton(
                                onClick = { vm.applyVoicePreset(p) },
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) { Text(p.label) }
                        }
                    }

                    SettingSlider(
                        "Intonatsiya (jonlilik): ${"%.2f".format(config.voiceNoise)}",
                        "Yuqori — ovoz jonliroq, lekin beqaror bo'lishi mumkin. Past — bir tekis, robotroq.",
                        config.voiceNoise, 0.3f..1.0f
                    ) { vm.setConfig(config.copy(voiceNoise = it)) }

                    SettingSlider(
                        "Ritm o'zgaruvchanligi: ${"%.2f".format(config.voiceNoiseW)}",
                        "Bo'g'inlar uzunligi qanchalik xilma-xil bo'lishi. Yuqori — tabiiyroq ritm.",
                        config.voiceNoiseW, 0.1f..1.0f
                    ) { vm.setConfig(config.copy(voiceNoiseW = it)) }

                    SettingSlider(
                        "Nutq tezligi: ${"%.2f".format(config.voiceSpeed)}×",
                        "1.0 — oddiy. Past — sekinroq va aniqroq.",
                        config.voiceSpeed, 0.7f..1.3f
                    ) { vm.setConfig(config.copy(voiceSpeed = it)) }

                    SettingSlider(
                        "Pauza (gap ichida): ${"%.2f".format(config.voicePause)} s",
                        "Uzun gap bo'laklarga bo'linganda orasidagi to'xtash.",
                        config.voicePause, 0f..0.8f
                    ) { vm.setConfig(config.copy(voicePause = it)) }

                    SettingSlider(
                        "Sig'dirish uchun tezlashish: ${"%.2f".format(1f / config.fitScale)}×",
                        "Gap video vaqtiga sig'masa, ovoz shu darajagacha tezlashtiriladi (ovoz sifati saqlanadi). 1.0 — o'chiq.",
                        config.fitScale, 0.7f..1.0f
                    ) { vm.setConfig(config.copy(fitScale = it)) }

                    OutlinedTextField(
                        value = sample,
                        onValueChange = { sample = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Tinglash uchun matn") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.previewVoice(context, sample) },
                            enabled = models.vits && !vm.isBusy
                        ) { Text("▶ Tinglab ko'rish") }
                        OutlinedButton(onClick = { vm.stopPreview() }) { Text("■ To'xtatish") }
                    }
                    if (!models.vits) {
                        Text(
                            "Tinglash uchun avval VITS modelini import qiling.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    preview?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(
                        "Eslatma: haqiqiy dublyajda gaplar video vaqtiga sig'dirilgani uchun ovoz biroz tezroq bo'lishi mumkin.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // ---------------- aralashtirish
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Aralashtirish va tezlik", fontWeight = FontWeight.SemiBold)

                    SettingSlider(
                        "Asl ovoz (jimlikda): ${(config.originalVolume * 100).roundToInt()}%",
                        "Videodagi asl ovoz/musiqa balandligi.",
                        config.originalVolume, 0f..0.6f
                    ) { vm.setConfig(config.copy(originalVolume = it)) }

                    SettingSlider(
                        "Dublyaj gapirganda asl ovoz: ${(config.duckAmount * 100).roundToInt()}%",
                        "Dublyaj gapirayotganda asl ovoz shu darajagacha pasayadi. 100% — pasaytirilmaydi.",
                        config.duckAmount, 0f..1f
                    ) { vm.setConfig(config.copy(duckAmount = it)) }

                    SettingSlider(
                        "O'zbekcha ovoz: ${(config.uzbekVolume * 100).roundToInt()}%",
                        "Dublyaj ovozi balandligi.",
                        config.uzbekVolume, 0.5f..1.5f
                    ) { vm.setConfig(config.copy(uzbekVolume = it)) }

                    SettingSlider(
                        "Maks. tezlashtirish: ${"%.2f".format(config.maxAtempo)}×",
                        "Gap baribir sig'masa, tayyor ovoz shu tezlikkacha tezlashtiriladi. Past — tabiiyroq, lekin gaplar ustma-ust tushishi mumkin.",
                        config.maxAtempo, 1.0f..1.6f
                    ) { vm.setConfig(config.copy(maxAtempo = it)) }

                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Balandlikni tenglash")
                            Text(
                                "Har bir gap bir xil balandlikda eshitiladi.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = config.levelClips,
                            onCheckedChange = { vm.setConfig(config.copy(levelClips = it)) }
                        )
                    }

                    SettingSlider(
                        "CPU oqimlari: ${config.threads}",
                        "Ko'p — tezroq, lekin telefon qizishi va xotira ko'payishi mumkin.",
                        config.threads.toFloat(), 1f..8f, steps = 6
                    ) { vm.setConfig(config.copy(threads = it.roundToInt())) }
                }
            }

            // ---------------- yordam
            var reportNote by remember { mutableStateOf("") }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Muammo yoki taklif yuborish", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Nosozlik, taklif yoki fikringizni yozing. Hisobotga faqat texnik ma'lumot kiradi " +
                            "(qurilma, sozlamalar, oxirgi loglar). Video va matn kirmaydi.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (BugReport.hasCrash(context)) {
                        Text(
                            "⚠ Oldingi safar ilova kutilmaganda to'xtagan. Hisobotni yuborsangiz, sababni topamiz.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    OutlinedTextField(
                        value = reportNote,
                        onValueChange = { reportNote = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Izoh (ixtiyoriy)") }
                    )
                    Button(onClick = {
                        val f = BugReport.build(context, config, models, reportNote)
                        BugReport.share(context, f)
                    }) { Text("Hisobotni yuborish") }
                    Text(
                        "Ochiladigan ro'yxatdan Telegram yoki Gmail ni tanlang.",
                        style = MaterialTheme.typography.bodySmall
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


@Composable
private fun SettingSlider(
    title: String,
    help: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    Column {
        Text(title)
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, steps = steps)
        Text(help, style = MaterialTheme.typography.bodySmall)
    }
}
