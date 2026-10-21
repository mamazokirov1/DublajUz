package uz.dublaj.ui.screens

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uz.dublaj.data.VoicePreset
import uz.dublaj.data.applyPreset
import uz.dublaj.stt.WhisperRecognizer
import uz.dublaj.translate.Languages
import uz.dublaj.ui.components.*
import uz.dublaj.ui.theme.ThemeSettings
import uz.dublaj.ui.viewmodel.PipelineViewModel
import uz.dublaj.util.BugReport
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
    val preview by vm.previewState.collectAsState()
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val expert = config.expertMode

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
    val startAutoImport = rememberAutoImportAction(vm)

    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = { Text("Sozlamalar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Orqaga")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---------------- rejim
            Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Rejim", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !expert,
                            onClick = { vm.setConfig(config.copy(expertMode = false)) },
                            label = { Text("Oddiy") }
                        )
                        FilterChip(
                            selected = expert,
                            onClick = { vm.setConfig(config.copy(expertMode = true)) },
                            label = { Text("Mutaxassis") }
                        )
                    }
                    Text(
                        if (expert) "Hamma sozlamalar ko'rinadi."
                        else "Faqat eng kerakli sozlamalar. Ko'proq nazorat uchun Mutaxassis rejimini yoqing.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                    )
                }
            }

            // ---------------- modellar
            val allReady = models.whisper && models.nllb && models.vits
            SectionCard(
                icon = Icons.Default.Storage,
                title = "Modellar",
                subtitle = if (allReady) "Hammasi tayyor ✓" else "Ba'zi modellar yetishmaydi",
                initiallyExpanded = !allReady
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill("Whisper", models.whisper)
                    StatusPill("NLLB", models.nllb)
                    StatusPill("VITS", models.vits)
                }
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = cs.primary)
                    if (!it.startsWith("Tayyor") && !it.startsWith("Xato") && !it.startsWith("Bekor")) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { vm.cancel() }) { Text("Bekor qilish") }
                    }
                }
                Button(
                    onClick = { startAutoImport() },
                    enabled = !vm.isBusy,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Download papkadan avtomatik import")
                }
                Text(
                    "Download va boshqa papkalardan ggml-*.bin, nllb papkasi, vits_uzbek.onnx va uz_tts_base.json ni o'zi topib nusxalaydi. " +
                        "Birinchi marta \"Barcha fayllarga kirish\" ruxsatini yoqing.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )

                HorizontalDivider()
                Text("Whisper (ovoz → matn)", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WhisperRecognizer.MODEL_OPTIONS.forEach { m ->
                        FilterChip(
                            selected = config.whisperModel == m.id,
                            onClick = { vm.setConfig(config.copy(whisperModel = m.id), context) },
                            label = { Text("${m.id} ${m.sizeMb}MB") }
                        )
                    }
                }
                Text(WhisperRecognizer.info(config.whisperModel).note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.downloadWhisper(context) }, enabled = !vm.isBusy) { Text("Yuklash") }
                    OutlinedButton(onClick = { whisperPicker.launch(arrayOf("*/*")) }, enabled = !vm.isBusy) { Text("Fayldan import") }
                }

                HorizontalDivider()
                Text("NLLB (tarjima, ~1 GB)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Litsenziya: CC-BY-NC (faqat notijorat foydalanish).",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.downloadNllb(context) }, enabled = !vm.isBusy) { Text("Yuklash") }
                    OutlinedButton(onClick = { nllbPicker.launch(null) }, enabled = !vm.isBusy) { Text("Papkadan import") }
                }
                OutlinedButton(onClick = { nllbFilesPicker.launch(arrayOf("*/*")) }, enabled = !vm.isBusy) {
                    Text("Fayllarni tanlab import (5 ta)")
                }
                Text(
                    "config.json, tokenizer.json, tokenizer_config.json, encoder_model_quantized.onnx, " +
                        "decoder_model_merged_quantized.onnx: hammasini birdan belgilang.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )

                HorizontalDivider()
                Text("VITS (o'zbekcha ovoz)", style = MaterialTheme.typography.titleMedium)
                Text("vits_uzbek.onnx va uz_tts_base.json fayllarini tanlang.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vitsPicker.launch(arrayOf("*/*")) }, enabled = !vm.isBusy) { Text(".onnx") }
                    OutlinedButton(onClick = { vitsCfgPicker.launch(arrayOf("*/*")) }, enabled = !vm.isBusy) { Text(".json config") }
                }
            }

            // ---------------- ovoz sifati
            var sample by remember {
                mutableStateOf("Assalomu alaykum! Bu ovoz sinovi. Tabiiy chiqayotganini tinglab ko'ring.")
            }
            SectionCard(
                icon = Icons.Default.GraphicEq,
                title = "Ovoz sifati",
                subtitle = "Tinglab ko'rib sozlang",
                initiallyExpanded = true
            ) {
                Text("Tayyor sozlama", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoicePreset.values().forEach { p ->
                        FilterChip(
                            selected = config.applyPreset(p) == config,
                            onClick = { vm.applyVoicePreset(p) },
                            label = { Text(p.label) }
                        )
                    }
                }

                HelpSlider(
                    "Nutq tezligi", "${"%.2f".format(config.voiceSpeed)}×",
                    "1.0 oddiy tezlik. Past qiymat sekinroq va aniqroq, baland qiymat tezroq gapiradi.",
                    config.voiceSpeed, 0.7f..1.3f
                ) { vm.setConfig(config.copy(voiceSpeed = it)) }

                if (expert) {
                    HelpSlider(
                        "Intonatsiya (jonlilik)", "%.2f".format(config.voiceNoise),
                        "Yuqori qiymat ovozni jonliroq qiladi, lekin beqaror bo'lishi mumkin. Past qiymat bir tekis, robotroq eshitiladi.",
                        config.voiceNoise, 0.3f..1.0f
                    ) { vm.setConfig(config.copy(voiceNoise = it)) }

                    HelpSlider(
                        "Ritm o'zgaruvchanligi", "%.2f".format(config.voiceNoiseW),
                        "Bo'g'inlar uzunligi qanchalik xilma-xil bo'lishi. Yuqori qiymat tabiiyroq ritm beradi.",
                        config.voiceNoiseW, 0.1f..1.0f
                    ) { vm.setConfig(config.copy(voiceNoiseW = it)) }

                    HelpSlider(
                        "Pauza (gap ichida)", "${"%.2f".format(config.voicePause)} s",
                        "Uzun gap bo'laklarga bo'linganda orasidagi to'xtash.",
                        config.voicePause, 0f..0.8f
                    ) { vm.setConfig(config.copy(voicePause = it)) }

                    HelpSlider(
                        "Sig'dirish uchun tezlashish", "${"%.2f".format(1f / config.fitScale)}×",
                        "Gap video vaqtiga sig'masa, ovoz shu darajagacha tezlashtiriladi (sifat saqlanadi). 1.0 o'chiq degani.",
                        config.fitScale, 0.7f..1.0f
                    ) { vm.setConfig(config.copy(fitScale = it)) }
                }

                OutlinedTextField(
                    value = sample,
                    onValueChange = { sample = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    label = { Text("Tinglash uchun matn") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.previewVoice(context, sample) },
                        enabled = models.vits && !vm.isBusy,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Tinglab ko'rish")
                    }
                    OutlinedButton(onClick = { vm.stopPreview() }, shape = MaterialTheme.shapes.medium) {
                        Icon(Icons.Default.Stop, null)
                        Spacer(Modifier.width(6.dp))
                        Text("To'xtatish")
                    }
                }
                if (!models.vits) {
                    Text("Tinglash uchun avval VITS modelini import qiling.", style = MaterialTheme.typography.bodySmall, color = cs.tertiary)
                }
                preview?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.primary) }
                Text(
                    "Haqiqiy dublyajda gaplar video vaqtiga sig'dirilgani uchun ovoz biroz tezroq bo'lishi mumkin.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
            }

            // ---------------- aralashtirish
            SectionCard(
                icon = Icons.Default.Tune,
                title = "Aralashtirish",
                subtitle = "Asl ovoz va dublyaj balandligi"
            ) {
                HelpSlider(
                    "Asl ovoz (jimlikda)", "${(config.originalVolume * 100).roundToInt()}%",
                    "Videodagi asl ovoz va musiqa balandligi.",
                    config.originalVolume, 0f..0.6f
                ) { vm.setConfig(config.copy(originalVolume = it)) }

                HelpSlider(
                    "O'zbekcha ovoz", "${(config.uzbekVolume * 100).roundToInt()}%",
                    "Dublyaj ovozi balandligi.",
                    config.uzbekVolume, 0.5f..1.5f
                ) { vm.setConfig(config.copy(uzbekVolume = it)) }

                if (expert) {
                    HelpSlider(
                        "Gapirganda asl ovoz", "${(config.duckAmount * 100).roundToInt()}%",
                        "Dublyaj gapirayotganda asl ovoz shu darajagacha pasayadi. 100% degani pasaytirilmaydi.",
                        config.duckAmount, 0f..1f
                    ) { vm.setConfig(config.copy(duckAmount = it)) }

                    HelpSlider(
                        "Maks. tezlashtirish", "${"%.2f".format(config.maxAtempo)}×",
                        "Gap baribir sig'masa, tayyor ovoz shu tezlikkacha tezlashtiriladi. Past qiymat tabiiyroq, lekin gaplar ustma-ust tushishi mumkin.",
                        config.maxAtempo, 1.0f..1.6f
                    ) { vm.setConfig(config.copy(maxAtempo = it)) }

                    SwitchRow(
                        "Balandlikni tenglash", "Har bir gap bir xil balandlikda eshitiladi.",
                        config.levelClips
                    ) { vm.setConfig(config.copy(levelClips = it)) }

                    HelpSlider(
                        "CPU oqimlari", "${config.threads}",
                        "Ko'p oqim tezroq ishlaydi, lekin telefon qizishi va xotira sarfi oshishi mumkin.",
                        config.threads.toFloat(), 1f..8f, steps = 6
                    ) { vm.setConfig(config.copy(threads = it.roundToInt())) }
                }
            }

            // ---------------- til
            SectionCard(
                icon = Icons.Default.Language,
                title = "Video tili",
                subtitle = Languages.label(config.sourceLang)
            ) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }, shape = MaterialTheme.shapes.medium) {
                        Text(Languages.label(config.sourceLang))
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.ExpandMore, null)
                    }
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
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
            }

            // ---------------- ko'rinish
            SectionCard(
                icon = Icons.Default.Palette,
                title = "Ko'rinish",
                subtitle = "Mavzu va ranglar"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Tizim", "Yorug'", "Qorong'i").forEachIndexed { i, label ->
                        FilterChip(
                            selected = config.themeMode == i,
                            onClick = {
                                ThemeSettings.mode = i
                                vm.setConfig(config.copy(themeMode = i))
                            },
                            label = { Text(label) }
                        )
                    }
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    SwitchRow(
                        "Telefon ranglari", "Android 12+ (Material You): ranglar fon rasmingizdan olinadi.",
                        config.dynamicColor
                    ) {
                        ThemeSettings.dynamic = it
                        vm.setConfig(config.copy(dynamicColor = it))
                    }
                }
            }

            // ---------------- yordam
            var reportNote by remember { mutableStateOf("") }
            SectionCard(
                icon = Icons.Default.BugReport,
                title = "Muammo yoki taklif yuborish",
                subtitle = if (BugReport.hasCrash(context)) "⚠ Oldingi safar ilova to'xtagan" else "Hisobot yaratib yuboring"
            ) {
                Text(
                    "Hisobotga faqat texnik ma'lumot kiradi: qurilma, sozlamalar, oxirgi loglar. Video va matn kirmaydi.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
                OutlinedTextField(
                    value = reportNote,
                    onValueChange = { reportNote = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    label = { Text("Izoh yoki taklif (ixtiyoriy)") }
                )
                Button(
                    onClick = {
                        val f = BugReport.build(context, config, models, reportNote)
                        BugReport.share(context, f)
                    },
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.Send, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Hisobotni yuborish")
                }
                Text(
                    "Ochiladigan ro'yxatdan Telegram yoki Gmail ni tanlang.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
            }

            // ---------------- xotira
            SectionCard(
                icon = Icons.Default.DeleteSweep,
                title = "Xotira",
                subtitle = "Vaqtinchalik fayllar: $usedMb MB"
            ) {
                Text(
                    "Video nusxalari va oraliq audio shu yerda turadi. Modellar va tayyor videolar o'chirilmaydi.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { vm.clearWork(context) },
                    enabled = !vm.isBusy,
                    shape = MaterialTheme.shapes.medium
                ) { Text("Vaqtinchalik fayllarni tozalash") }
            }

            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
