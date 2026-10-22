package uz.dublaj.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uz.dublaj.data.PipelineState
import uz.dublaj.ui.components.CollectUiEvents
import uz.dublaj.ui.components.HelpSlider
import uz.dublaj.ui.components.RunningBanner
import uz.dublaj.ui.viewmodel.PipelineViewModel
import uz.dublaj.util.ExportUtils
import uz.dublaj.util.OutputUtils
import uz.dublaj.util.WorkDir
import java.io.File
import java.util.Locale

/** "Matn va ovoz": videodan oddiy matn olish va istalgan matnni o'zbekcha ovozga aylantirish. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextToolsScreen(vm: PipelineViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val state by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    val cancelling by vm.cancelling.collectAsState()
    val models by vm.models.collectAsState()
    val config by vm.config.collectAsState()
    val videoUri by vm.videoUri.collectAsState()
    val units by vm.translationUnits.collectAsState()
    val hasTranscript by vm.hasTranscript.collectAsState()
    val original by vm.plainOriginal.collectAsState()
    val audio by vm.audioResult.collectAsState()
    val host = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var typed by rememberSaveable { mutableStateOf("") }

    CollectUiEvents(vm, host)
    LaunchedEffect(hasTranscript) { if (hasTranscript) vm.refreshPlainTexts(context) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        u?.let { vm.setVideo(it) }
    }
    val uzText = remember(units) { vm.plainUzbek() }

    Scaffold(
        containerColor = cs.background,
        snackbarHost = { SnackbarHost(host) },
        topBar = {
            TopAppBar(
                title = { Text("Matn va ovoz") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Orqaga")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Videodan matn") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Matn → ovoz") })
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (val s = state) {
                    is PipelineState.Running -> RunningBanner(s, cancelling, onCancel = { vm.cancel() })
                    is PipelineState.Error -> Card(
                        colors = CardDefaults.cardColors(containerColor = cs.errorContainer),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(s.message, color = cs.onErrorContainer)
                            TextButton(onClick = { vm.resetState() }) { Text("Yopish") }
                        }
                    }
                    else -> {}
                }

                Crossfade(targetState = tab, label = "tab") { t ->
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (t == 0) {
                            Text(
                                "Videodagi gaplarni vaqt belgilarisiz, bitta oddiy matn qilib oladi. " +
                                    "Uni nusxalash, ulashish yoki o'zbekcha ovozga aylantirish mumkin.",
                                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant
                            )
                            if (videoUri == null) {
                                Button(
                                    onClick = { picker.launch("video/*") }, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().height(52.dp)
                                ) {
                                    Icon(Icons.Default.VideoLibrary, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Video tanlash")
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { vm.transcribeAndTranslate(context) },
                                        enabled = !busy && models.whisper && models.nllb,
                                        modifier = Modifier.weight(1f).height(52.dp)
                                    ) {
                                        Icon(Icons.Default.TextSnippet, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Matnni ajratib olish")
                                    }
                                    OutlinedButton(
                                        onClick = { picker.launch("video/*") }, enabled = !busy,
                                        modifier = Modifier.height(52.dp)
                                    ) { Text("Boshqa video") }
                                }
                                if (!models.whisper || !models.nllb) {
                                    Text(
                                        "Avval Sozlamalardan matn aniqlash va tarjima komponentlarini yuklab oling.",
                                        style = MaterialTheme.typography.bodySmall, color = cs.tertiary
                                    )
                                }
                            }
                            if (original.isNotBlank()) {
                                TextBlock("Asl matn (videodagi til)", original, "asl_matn", null)
                            }
                            if (uzText.isNotBlank()) {
                                TextBlock(
                                    "O'zbekcha matn", uzText, "ozbekcha_matn",
                                    onSpeak = if (busy) null else ({ vm.speak(context, uzText) })
                                )
                            }
                            if (original.isBlank() && uzText.isBlank() && videoUri != null && !busy) {
                                Text(
                                    "Matn hali ajratilmagan. Yuqoridagi tugmani bosing.",
                                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                                )
                            }
                        } else {
                            OutlinedTextField(
                                value = typed,
                                onValueChange = { typed = it },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 6,
                                label = { Text("O'zbekcha matnni kiriting yoki qo'ying") },
                                supportingText = { Text("${typed.length} belgi") }
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { vm.speak(context, typed) },
                                    enabled = !busy && typed.isNotBlank(),
                                    modifier = Modifier.weight(1f).height(52.dp)
                                ) {
                                    Icon(Icons.Default.RecordVoiceOver, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Ovozga aylantirish")
                                }
                                OutlinedButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }
                                            ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                        if (t.isBlank()) vm.toast("Buferda matn yo'q") else typed = t
                                    },
                                    modifier = Modifier.height(52.dp)
                                ) { Icon(Icons.Default.ContentPaste, contentDescription = "Qo'yish") }
                            }
                            if (!models.vits) {
                                Text(
                                    "O'zbekcha ovoz fayllari qo'shilmagan: Sozlamalar bo'limidan qo'shing.",
                                    style = MaterialTheme.typography.bodySmall, color = cs.tertiary
                                )
                            }
                            // tezkor ovoz sozlamalari
                            Card(
                                shape = MaterialTheme.shapes.large,
                                colors = CardDefaults.cardColors(containerColor = cs.surface)
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Ovoz sozlamalari", style = MaterialTheme.typography.titleMedium)
                                    HelpSlider(
                                        title = "Gapirish tezligi",
                                        valueText = String.format(Locale.US, "%.2f×", config.voiceSpeed),
                                        help = "1.0 — oddiy. Kichik son sekinroq, katta son tezroq gapiradi.",
                                        value = config.voiceSpeed, range = 0.6f..1.5f
                                    ) { vm.setConfig(config.copy(voiceSpeed = it), context) }
                                    HelpSlider(
                                        title = "Ovoz tembri",
                                        valueText = if (config.voicePitch == 0f) "Asl"
                                        else String.format(Locale.US, "%+.1f", config.voicePitch),
                                        help = "Manfiy qiymat — qalinroq (erkakcha), musbat — ingichkaroq ovoz. 0 — o'zgarishsiz.",
                                        value = config.voicePitch, range = -4f..4f
                                    ) { vm.setConfig(config.copy(voicePitch = Math.round(it * 2f) / 2f), context) }
                                    HelpSlider(
                                        title = "Abzaslar orasidagi pauza",
                                        valueText = String.format(Locale.US, "%.2f s", config.voicePause),
                                        help = "Gaplar va abzaslar orasida qancha to'xtash bo'lishi.",
                                        value = config.voicePause, range = 0.1f..1.0f
                                    ) { vm.setConfig(config.copy(voicePause = it), context) }
                                }
                            }
                        }

                        audio?.let { f -> AudioCard(f, vm) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextBlock(title: String, text: String, fileBase: String, onSpeak: (() -> Unit)?) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    "${text.length} belgi",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant
                )
            }
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 8,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (text.length > 400) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Yig'ish" else "To'liq ko'rish") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("matn", text))
                        Toast.makeText(context, "Nusxalandi ✓", Toast.LENGTH_SHORT).show()
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Nusxa")
                }
                FilledTonalButton(
                    onClick = {
                        try {
                            val dir = WorkDir(context.applicationContext).output.apply { mkdirs() }
                            val f = File(dir, "${fileBase}_${System.currentTimeMillis()}.txt")
                            f.writeText(text)
                            context.startActivity(ExportUtils.shareIntent(context, f, "text/plain", "Matnni saqlash/ulashish"))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Fayl yozilmadi: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(Icons.Default.Description, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("TXT")
                }
                FilledTonalButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, "Ulashish"))
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Ulashish")
                }
            }
            if (onSpeak != null) {
                Button(onClick = onSpeak, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(Icons.Default.RecordVoiceOver, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Audioga aylantirish")
                }
            }
        }
    }
}

@Composable
private fun AudioCard(file: File, vm: PipelineViewModel) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var player by remember(file) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(file) { mutableStateOf(false) }
    DisposableEffect(file) {
        onDispose {
            try { player?.release() } catch (_: Exception) { }
            player = null
        }
    }
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer),
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AudioFile, null, tint = cs.onSecondaryContainer)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Audio tayyor", style = MaterialTheme.typography.titleMedium, color = cs.onSecondaryContainer)
                    Text(
                        "${file.length() / 1024} KB • WAV",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer.copy(alpha = 0.8f)
                    )
                }
                IconButton(onClick = { vm.clearAudioResult() }) {
                    Icon(Icons.Default.Close, contentDescription = "Yopish", tint = cs.onSecondaryContainer)
                }
            }
            Button(
                onClick = {
                    try {
                        if (playing) {
                            player?.pause()
                            playing = false
                        } else {
                            val p = player ?: MediaPlayer().apply {
                                setDataSource(file.absolutePath)
                                setOnCompletionListener { playing = false }
                                prepare()
                            }.also { player = it }
                            p.start()
                            playing = true
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "Ijro etilmadi: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(if (playing) "To'xtatish" else "Eshitish")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { context.startActivity(ExportUtils.shareIntent(context, file, "audio/wav", "Audio ulashish")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ulashish")
                }
                FilledTonalButton(
                    onClick = {
                        val msg = try {
                            if (OutputUtils.saveAudioToMusic(context, file) != null) "Musiqa papkasiga saqlandi (Music/DublajUz) ✓"
                            else "Android 10+ kerak. 'Ulashish' orqali saqlang."
                        } catch (e: Exception) { "Saqlash xatosi: ${e.message}" }
                        vm.toast(msg)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.SaveAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Saqlash")
                }
            }
        }
    }
}
