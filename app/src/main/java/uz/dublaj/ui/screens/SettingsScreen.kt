package uz.dublaj.ui.screens

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import uz.dublaj.ui.components.CollectUiEvents

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
import uz.dublaj.util.UpdateChecker
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
    val updateMessage by vm.updateMessage.collectAsState()
    val availableUpdate by vm.update.collectAsState()
    val updateProgress by vm.updateProgress.collectAsState()
    val busy by vm.busy.collectAsState()
    val cancelling by vm.cancelling.collectAsState()
    val workVersion by vm.workVersion.collectAsState()
    val host = remember { SnackbarHostState() }
    CollectUiEvents(vm, host)
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val expert = config.expertMode

    var usedMb by remember { mutableStateOf(0L) }
    LaunchedEffect(status, workVersion) {
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
        snackbarHost = { SnackbarHost(host) },
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
                            label = { Text("Ilg'or") }
                        )
                    }
                    Text(
                        if (expert) "Hamma sozlamalar ko'rinadi."
                        else "Faqat eng kerakli sozlamalar. Ko'proq nazorat uchun Ilg'or rejimni yoqing.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                    )
                }
            }

            // ---------------- modellar
            val allReady = models.whisper && models.nllb && models.vits
            SectionCard(
                icon = Icons.Default.Storage,
                title = "Yuklab olinadigan komponentlar",
                subtitle = if (allReady) "Hammasi tayyor ✓" else "Ba'zi komponentlar yetishmaydi",
                initiallyExpanded = !allReady
            ) {
                Text(
                    "Ilova internetsiz ishlashi uchun uchta komponent kerak. Ularni bir marta yuklab olasiz yoki telefondan qo'shasiz.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill("Matn aniqlash", models.whisper)
                    StatusPill("Tarjima", models.nllb)
                    StatusPill("Ovoz", models.vits)
                }
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = cs.primary)
                    if (!it.startsWith("Tayyor") && !it.startsWith("Xato") && !it.startsWith("Bekor")) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { vm.cancel() }, enabled = !cancelling) {
                            Text(if (cancelling) "Bekor qilinmoqda…" else "Bekor qilish")
                        }
                    }
                }
                Button(
                    onClick = { startAutoImport() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Yuklangan fayllarni avtomatik topish")
                }
                Text(
                    "Telefoningizdagi Yuklanmalar (Download) va boshqa papkalardan kerakli fayllarni o'zi topib qo'shadi. " +
                        "Birinchi marta \"Barcha fayllarga kirish\" ruxsatini yoqish kerak bo'ladi.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )

                HorizontalDivider()
                Text("Nutqni matnga aylantirish", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Videodagi gapni eshitib, yozib oladi. \"Tez\" kichik va tez, \"Aniq\" katta va aniqroq (sekinroq).",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WhisperRecognizer.MODEL_OPTIONS.forEach { m ->
                        FilterChip(
                            selected = config.whisperModel == m.id,
                            onClick = { vm.setConfig(config.copy(whisperModel = m.id), context) },
                            label = { Text("${friendlyModelName(m.id)} • ${m.sizeMb} MB") }
                        )
                    }
                }
                Text(WhisperRecognizer.info(config.whisperModel).note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.downloadWhisper(context) }, enabled = !busy) { Text("Yuklash") }
                    OutlinedButton(onClick = { whisperPicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Fayldan qo'shish") }
                }

                HorizontalDivider()
                Text("Tarjimon (~1 GB)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Matnni o'zbek tiliga o'giradi. Bir marta yuklab olinadi, keyin internetsiz ishlaydi. Fayl katta, shuning uchun Wi-Fi tavsiya etiladi.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.downloadNllb(context) }, enabled = !busy) { Text("Yuklash") }
                    OutlinedButton(onClick = { nllbPicker.launch(null) }, enabled = !busy) { Text("Papkadan qo'shish") }
                }
                OutlinedButton(onClick = { nllbFilesPicker.launch(arrayOf("*/*")) }, enabled = !busy) {
                    Text("Fayllarni tanlab qo'shish (5 ta)")
                }
                Text(
                    "Fayllarni o'zingiz yuklagan bo'lsangiz, 5 tasini birdan belgilang: config.json, tokenizer.json, " +
                        "tokenizer_config.json va ikkita .onnx fayl (encoder, decoder).",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )

                HorizontalDivider()
                Text("O'zbekcha ovoz", style = MaterialTheme.typography.titleMedium)
                Text("Tarjimani o'zbekcha o'qiydigan ikki fayl: ovoz fayli (.onnx) va uning sozlama fayli (.json).", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vitsPicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Ovoz fayli (.onnx)") }
                    OutlinedButton(onClick = { vitsCfgPicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Sozlama fayli (.json)") }
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

                HelpSlider(
                    "Ovoz tembri", if (config.voicePitch == 0f) "Asl" else "%+.1f".format(config.voicePitch),
                    "Manfiy qiymat ovozni qalinroq (erkakcha), musbat qiymat ingichkaroq qiladi. 0 — o'zgarishsiz. Gapirish tezligiga ta'sir qilmaydi.",
                    config.voicePitch, -4f..4f, steps = 15
                ) { vm.setConfig(config.copy(voicePitch = it)) }

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
                        enabled = models.vits && !busy,
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
                    Text("Tinglash uchun avval o'zbekcha ovoz fayllarini qo'shing.", style = MaterialTheme.typography.bodySmall, color = cs.tertiary)
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
                        "Ishlash tezligi (protsessor yadrolari)", "${config.threads}",
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
                    "Ilova to'xtab qolsa yoki taklifingiz bo'lsa, bizga yuboring. Hisobotga faqat telefon va sozlamalar haqida " +
                        "qisqa ma'lumot kiradi. Videolaringiz va matnlaringiz yuborilmaydi.",
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
                    Text("Yuborish")
                }
                Text(
                    "Pochta ilovasi ochiladi, hammasi tayyor turadi — faqat \"Yuborish\" ni bosasiz.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
            }

            // ---------------- xotira
            val shownMb by animateIntAsState(usedMb.toInt(), animationSpec = tween(700), label = "usedMb")
            SectionCard(
                icon = Icons.Default.DeleteSweep,
                title = "Xotira",
                subtitle = "Vaqtinchalik fayllar: $shownMb MB"
            ) {
                Text(
                    "Video nusxalari va oraliq audio shu yerda turadi. Modellar va tayyor videolar o'chirilmaydi.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
                SwitchRow(
                    "Galereyaga avtomatik saqlash",
                    "Dublyaj tayyor bo'lganda video Movies/DublajUz papkasiga ham saqlanadi. " +
                        "Ilovani o'chirsangiz ham yo'qolmaydi (Android 10+).",
                    config.autoSaveGallery
                ) { vm.setConfig(config.copy(autoSaveGallery = it)) }
                OutlinedButton(
                    onClick = { vm.clearWork(context) },
                    enabled = !busy,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.DeleteSweep, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Vaqtinchalik fayllarni tozalash")
                }
                if (busy) {
                    Text(
                        "Jarayon ketayotganda tozalab bo'lmaydi. Tugashini kuting yoki bekor qiling.",
                        style = MaterialTheme.typography.bodySmall, color = cs.tertiary
                    )
                }
            }

            // ---------------- ilova haqida
            SectionCard(
                icon = Icons.Default.Info,
                title = "Ilova haqida",
                subtitle = "Versiya ${UpdateChecker.currentVersionName(context)}"
            ) {
                Button(onClick = { vm.checkUpdate(context, manual = true) }, shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Default.SystemUpdate, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Yangilanishni tekshirish")
                }
                updateMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.primary) }
                availableUpdate?.let { u ->
                    Button(
                        onClick = { vm.downloadUpdate(context) },
                        enabled = updateProgress == null,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Yuklab olish va o'rnatish (${u.versionName})")
                    }
                }
                updateProgress?.let { p ->
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    "Yangi versiya chiqsa, ilova ochilganda kuniga bir marta o'zi xabar beradi.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
            }

            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }
}

private fun friendlyModelName(id: String): String = when (id) {
    "tiny" -> "Tez"
    "base" -> "Oddiy"
    "small" -> "Aniq"
    else -> id
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
