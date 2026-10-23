package uz.dublaj.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import uz.dublaj.data.AppConfig
import uz.dublaj.data.ConfigStore
import uz.dublaj.data.VoicePreset
import uz.dublaj.data.applyPreset
import uz.dublaj.tts.VitsSynthesizer
import uz.dublaj.util.AudioLevel
import uz.dublaj.util.PcmPlayer
import uz.dublaj.data.ModelsInfo
import uz.dublaj.data.PipelineState
import uz.dublaj.data.TranslationUnit
import uz.dublaj.data.UserCancelled
import uz.dublaj.pipeline.DubbingPipeline
import uz.dublaj.service.PipelineService
import uz.dublaj.util.FileUtils
import uz.dublaj.util.OutputUtils
import uz.dublaj.util.StorageScanner
import uz.dublaj.util.UpdateChecker
import uz.dublaj.data.QueueItem
import uz.dublaj.data.QueueStatus
import kotlinx.coroutines.flow.update
import java.io.File

/** Yagona (Activity darajasidagi) ViewModel: barcha ekranlar shu nusxani ulashadi. */
class PipelineViewModel : ViewModel() {

    private val pipeline = DubbingPipeline()
    private var job: Job? = null
    private var restored = false

    private val _state = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private val _videoUri = MutableStateFlow<Uri?>(null)
    val videoUri: StateFlow<Uri?> = _videoUri.asStateFlow()

    private val _hasTranscript = MutableStateFlow(false)
    val hasTranscript: StateFlow<Boolean> = _hasTranscript.asStateFlow()

    private val _translationUnits = MutableStateFlow<List<TranslationUnit>>(emptyList())
    val translationUnits: StateFlow<List<TranslationUnit>> = _translationUnits.asStateFlow()

    private val _approved = MutableStateFlow(false)
    val approved: StateFlow<Boolean> = _approved.asStateFlow()

    private val _config = MutableStateFlow(AppConfig())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    private val _models = MutableStateFlow(ModelsInfo())
    val models: StateFlow<ModelsInfo> = _models.asStateFlow()

    /** Model yuklash/import holati (matn); null = hech narsa ketayotgani yo'q. */
    private val _modelStatus = MutableStateFlow<String?>(null)
    val modelStatus: StateFlow<String?> = _modelStatus.asStateFlow()

    val isBusy: Boolean get() = job?.isActive == true

    // ---- ekranlar uchun kuzatiladigan holatlar
    private val _busy = MutableStateFlow(false)
    /** Ish ketyaptimi (Compose'da kuzatiladi; isBusy esa oddiy tekshiruv). */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _cancelling = MutableStateFlow(false)
    /** "Bekor qilish" bosildi, ish hozir to'xtayapti. */
    val cancelling: StateFlow<Boolean> = _cancelling.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Foydalanuvchiga qisqa xabarlar (Snackbar). */
    val uiEvents: SharedFlow<String> = _events.asSharedFlow()
    fun toast(msg: String) { _events.tryEmit(msg) }

    private val _workVersion = MutableStateFlow(0)
    /** Vaqtinchalik fayllar o'zgarganda oshadi — Xotira kartasi qayta o'qiydi. */
    val workVersion: StateFlow<Int> = _workVersion.asStateFlow()

    private val _playerUri = MutableStateFlow<Uri?>(null)
    val playerUri: StateFlow<Uri?> = _playerUri.asStateFlow()
    fun openPlayer(uri: Uri) { _playerUri.value = uri }

    private val _audioResult = MutableStateFlow<File?>(null)
    /** Oxirgi "matn → ovoz" natijasi (WAV). */
    val audioResult: StateFlow<File?> = _audioResult.asStateFlow()
    fun clearAudioResult() { _audioResult.value = null }

    private val _plainOriginal = MutableStateFlow("")
    val plainOriginal: StateFlow<String> = _plainOriginal.asStateFlow()

    /** Videodan ajratilgan matnni (vaqtlarsiz) diskdan o'qiydi. */
    fun refreshPlainTexts(context: Context) {
        val app = context.applicationContext
        viewModelScope.launch {
            _plainOriginal.value = withContext(Dispatchers.IO) { pipeline.plainTranscript(app) }
        }
    }

    /** Tarjima qilingan (tahrir qilingan) o'zbekcha matn, bitta uzun matn sifatida. */
    fun plainUzbek(): String =
        _translationUnits.value.map { it.uzText.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    /** Istalgan o'zbekcha matnni ovozga aylantirib, WAV fayl qiladi. */
    fun speak(context: Context, text: String) {
        val app = context.applicationContext
        if (text.isBlank()) { toast("Avval matn kiriting"); return }
        if (isBusy) { toast("Hozir boshqa jarayon ketyapti. Tugashini kuting yoki bekor qiling."); return }
        if (!_models.value.vits) { toast("Avval Sozlamalarda o'zbekcha ovoz fayllarini qo'shing"); return }
        releasePreview()
        _audioResult.value = null
        launchTask(context, "Matnni ovozlashtirish") {
            val cfg = _config.value
            val f = withContext(Dispatchers.IO) {
                pipeline.synthesizeText(app, text, cfg) { p, m ->
                    _state.value = PipelineState.Running("Matnni ovozlashtirish", p, m)
                }
            }
            _audioResult.value = f
            _state.value = PipelineState.Idle
            toast("Audio tayyor ✓")
            PipelineService.notifyDone(app, "Audio tayyor", "Matn o'zbekcha ovozga aylantirildi")
        }
    }

    // ------------------------------------------------------------------ holat

    fun restore(context: Context) {
        if (restored) return
        restored = true
        val app = context.applicationContext
        appCtx = app
        _config.value = ConfigStore.load(app)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { pipeline.restore(app) }
            if (_videoUri.value == null && r.videoFile != null) _videoUri.value = Uri.fromFile(r.videoFile)
            _hasTranscript.value = r.hasTranscript
            _translationUnits.value = r.units
            _approved.value = r.approved
        }
        // Modellar yetishmasa va ruxsat bor bo'lsa, Download papkasidan jimgina tiklaymiz
        viewModelScope.launch {
            val m = withContext(Dispatchers.IO) { pipeline.modelsInfo(app, _config.value) }
            _models.value = m
            if ((!m.whisper || !m.nllb || !m.vits) && StorageScanner.hasAccess(app)) {
                autoImport(app, quiet = true)
            }
        }
    }

    fun refreshModels(context: Context) {
        val app = context.applicationContext
        viewModelScope.launch {
            _models.value = withContext(Dispatchers.IO) { pipeline.modelsInfo(app, _config.value) }
        }
    }

    fun setConfig(c: AppConfig, context: Context? = null) {
        val modelChanged = c.whisperModel != _config.value.whisperModel
        _config.value = c
        (context?.applicationContext ?: appCtx)?.let { ConfigStore.save(it, c) }
        if (modelChanged && context != null) refreshModels(context)
    }

    fun applyVoicePreset(p: VoicePreset) = setConfig(_config.value.applyPreset(p))

    // ------------------------------------------------------------------ ovozni tinglab ko'rish

    private var appCtx: Context? = null
    private var previewSynth: VitsSynthesizer? = null
    private var previewJob: Job? = null
    private val _preview = MutableStateFlow<String?>(null)
    val previewState: StateFlow<String?> = _preview.asStateFlow()

    /** Joriy sozlamalar bilan matnni sintez qilib chaladi. Dublyaj paytida o'chiq (xotira). */
    fun previewVoice(context: Context, text: String) {
        val app = context.applicationContext
        if (isBusy) {
            _preview.value = "Dublyaj/tarjima ishlayotganda tinglab bo'lmaydi"
            return
        }
        if (text.isBlank()) return
        if (previewJob?.isActive == true && _preview.value?.startsWith("Ovoz tayyorlanmoqda") == true) return
        PcmPlayer.stop()
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            try {
                _preview.value = "Ovoz tayyorlanmoqda…"
                val cfg = _config.value
                val (pcm, sr) = withContext(Dispatchers.Default) {
                    val synth = previewSynth ?: pipeline.loadPreviewSynth(app, cfg).also { previewSynth = it }
                    synth.configure(
                        cfg.voiceNoise, cfg.voiceNoiseW,
                        1f / cfg.voiceSpeed.coerceAtLeast(0.3f), cfg.fitScale, cfg.voicePause, cfg.voicePitch
                    )
                    val seg = synth.synthesize(text, null)
                    (if (cfg.levelClips) AudioLevel.level(seg.pcm) else seg.pcm) to seg.sampleRate
                }
                if (pcm.isEmpty()) {
                    _preview.value = "Matn bo'sh yoki o'qib bo'lmadi"
                    return@launch
                }
                _preview.value = "Ijro etilmoqda…"
                PcmPlayer.play(pcm, sr)
                _preview.value = null
            } catch (e: CancellationException) {
                _preview.value = null
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Preview xato")
                _preview.value = "Xato: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun stopPreview() {
        PcmPlayer.stop()
        if (_preview.value?.startsWith("Ijro") == true) _preview.value = null
    }

    private fun releasePreview() {
        PcmPlayer.stop()
        previewJob?.cancel()
        previewSynth?.close()
        previewSynth = null
        _preview.value = null
    }

    override fun onCleared() {
        releasePreview()
        super.onCleared()
    }

    fun setVideo(uri: Uri) {
        if (isBusy) return
        _videoUri.value = uri
        _hasTranscript.value = false
        _translationUnits.value = emptyList()
        _approved.value = false
        _state.value = PipelineState.Idle
    }

    fun resetState() {
        if (!isBusy) _state.value = PipelineState.Idle
    }

    fun cancel() {
        if (!isBusy) return
        if (_cancelling.value) {
            toast("Bekor qilinmoqda… biroz kuting")
            return
        }
        pipeline.cancel()
        _cancelling.value = true
        _state.update { if (it is PipelineState.Running) it.copy(message = "Bekor qilinmoqda…") else it }
        if (_modelStatus.value != null) _modelStatus.value = "Bekor qilinmoqda…"
        toast("Bekor qilinmoqda… hozirgi qadam tugashi bilan to'xtaydi")
    }

    // ------------------------------------------------------------------ bosqichlar

    fun transcribe(context: Context) {
        val uri = _videoUri.value ?: return
        launchTask(context, "Matn ajratish") {
            val cfg = _config.value
            withContext(Dispatchers.IO) {
                pipeline.transcribe(context.applicationContext, uri, cfg) { p, m ->
                    _state.value = PipelineState.Running("Matn ajratish", p, m)
                }
            }
            _hasTranscript.value = true
            _translationUnits.value = emptyList()
            _approved.value = false
            _state.value = PipelineState.Idle
        }
    }

    fun translate(context: Context) {
        launchTask(context, "Tarjima") {
            val cfg = _config.value
            val units = withContext(Dispatchers.IO) {
                pipeline.translate(context.applicationContext, cfg) { p, m ->
                    _state.value = PipelineState.Running("Tarjima", p, m)
                }
            }
            _translationUnits.value = units
            _approved.value = false
            _state.value = PipelineState.Idle
        }
    }

    fun updateUzText(index: Int, newText: String) {
        val list = _translationUnits.value.toMutableList()
        if (index !in list.indices) return
        list[index] = list[index].copy(uzText = newText, check = false)
        _translationUnits.value = list
        _approved.value = false
    }

    fun saveTranslations(context: Context) {
        val app = context.applicationContext
        val units = _translationUnits.value
        if (units.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                pipeline.saveTranslation(app, units)
                if (!_approved.value) pipeline.clearApproval(app)
            } catch (e: Exception) {
                Timber.w(e, "saveTranslations")
            }
        }
    }

    /** Tasdiqlash: tekshirilmagan (xato) segmentlar qolgan bo'lsa rad etiladi. */
    fun approveTranslation(context: Context) {
        val units = _translationUnits.value
        if (units.isEmpty()) return
        val pending = units.count { it.check }
        if (pending > 0) {
            _state.value = PipelineState.Error(
                "$pending ta segment tarjima qilinmagan (⚠). Tarjimani tahrirlab chiqing, keyin tasdiqlang."
            )
            return
        }
        val app = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            pipeline.saveTranslation(app, units)
            pipeline.markApproved(app)
            _approved.value = true
        }
    }

    fun dub(context: Context) {
        if (!_approved.value) {
            _state.value = PipelineState.Error("Avval tarjimani tasdiqlang")
            return
        }
        releasePreview()
        launchTask(context, "Dublyaj") {
            val cfg = _config.value
            val units = _translationUnits.value
            val app = context.applicationContext
            val title = _videoUri.value?.let { FileUtils.displayName(app, it) } ?: ""
            val out: File = withContext(Dispatchers.IO) {
                pipeline.dub(app, units, cfg, title) { p, m ->
                    _state.value = PipelineState.Running("Dublyaj", p, m)
                }.also { autoSave(app, it, cfg) }
            }
            _state.value = PipelineState.Success(out.absolutePath)
            toast("Dublyaj tayyor ✓")
            PipelineService.notifyDone(app, "Dublyaj tayyor", title.ifBlank { "Video dublyaj qilindi" })
        }
    }

    /** Matn ajratish + tarjima; keyin foydalanuvchi tahrir qiladi. */
    fun transcribeAndTranslate(context: Context) {
        val uri = _videoUri.value ?: return
        launchTask(context, "Matn va tarjima") {
            val cfg = _config.value
            val app = context.applicationContext
            withContext(Dispatchers.IO) {
                pipeline.transcribe(app, uri, cfg) { p, m ->
                    _state.value = PipelineState.Running("Matn ajratish", p * 0.5f, m)
                }
            }
            _hasTranscript.value = true
            val units = withContext(Dispatchers.IO) {
                pipeline.translate(app, cfg) { p, m ->
                    _state.value = PipelineState.Running("Tarjima", 0.5f + p * 0.5f, m)
                }
            }
            _translationUnits.value = units
            _approved.value = false
            _state.value = PipelineState.Idle
            _plainOriginal.value = withContext(Dispatchers.IO) { pipeline.plainTranscript(app) }
            toast("Matn va tarjima tayyor ✓")
            PipelineService.notifyDone(app, "Tarjima tayyor", "${units.size} ta gap tekshirishni kutmoqda")
        }
    }

    // ------------------------------------------------------------------ modellar

    fun downloadWhisper(context: Context) {
        val app = context.applicationContext
        val id = _config.value.whisperModel
        launchModelTask(app, "Matn aniqlash komponenti yuklanmoqda…") {
            pipeline.downloadWhisper(app, id) { d, t ->
                _modelStatus.value = "Matn aniqlash: ${mb(d)} / ${if (t > 0) mb(t) else "?"}"
            }
        }
    }

    /** Sozlamalardagi "Kalitni tekshirish" tugmasi. */
    fun testGemini() {
        val cfg = _config.value
        if (cfg.geminiKey.isBlank()) {
            _modelStatus.value = "Avval Gemini kalitini kiriting"
            return
        }
        viewModelScope.launch {
            _modelStatus.value = "Gemini tekshirilmoqda…"
            val msg = withContext(Dispatchers.IO) {
                uz.dublaj.translate.GeminiTranslator(
                    cfg.geminiKey, cfg.geminiModel.ifBlank { uz.dublaj.translate.GeminiTranslator.DEFAULT_MODEL }
                ).test()
            }
            _modelStatus.value = msg
        }
    }

    fun downloadNllb(context: Context) {
        val app = context.applicationContext
        launchModelTask(app, "Tarjima komponenti yuklanmoqda…") {
            pipeline.downloadNllb(app) { f, i, n, b, t ->
                _modelStatus.value = "Tarjima [$i/$n] ${f.substringAfterLast('/')}: ${mb(b)} / ${if (t > 0) mb(t) else "?"}"
            }
        }
    }

    fun importWhisper(context: Context, uri: Uri) {
        val app = context.applicationContext
        val id = _config.value.whisperModel
        launchModelTask(app, "Matn aniqlash fayli qo'shilmoqda…") { pipeline.importWhisper(app, uri, id) }
    }

    fun importVitsModel(context: Context, uri: Uri) {
        val app = context.applicationContext
        launchModelTask(app, "O'zbekcha ovoz fayli qo'shilmoqda…") { pipeline.importVitsModel(app, uri) }
    }

    fun importVitsConfig(context: Context, uri: Uri) {
        val app = context.applicationContext
        launchModelTask(app, "Ovoz sozlama fayli qo'shilmoqda…") { pipeline.importVitsConfig(app, uri) }
    }

    fun importNllb(context: Context, treeUri: Uri) {
        val app = context.applicationContext
        launchModelTask(app, "Tarjima fayllari qo'shilmoqda…") {
            val n = pipeline.importNllb(app, treeUri) { _modelStatus.value = it }
            if (n < 5) throw IllegalStateException("Papkada kerakli fayllar topilmadi ($n/5 topildi)")
        }
    }

    /** Yuklab olingan fayllarni (matn aniqlash, tarjima, ovoz) topib qo'shadi. */
    fun autoImport(context: Context, quiet: Boolean = false) {
        val app = context.applicationContext
        if (isBusy) return
        if (!StorageScanner.hasAccess(app)) {
            if (!quiet) _modelStatus.value = "Xato: fayllarga kirish ruxsati berilmagan"
            return
        }
        launchModelTask(app, "Komponentlar qidirilmoqda…") {
            val rep = pipeline.autoImport(app) { _modelStatus.value = it }
            if (rep.found == 0) {
                if (quiet) {
                    quietNoop = true
                } else {
                    throw IllegalStateException(
                        "Hech narsa topilmadi. Fayllarni Download papkasiga qo'ying: " +
                            rep.notFound.joinToString("; ")
                    )
                }
            }
        }
    }

    fun importNllbFiles(context: Context, uris: List<Uri>) {
        val app = context.applicationContext
        launchModelTask(app, "Tarjima fayllari qo'shilmoqda…") {
            val n = pipeline.importNllbFiles(app, uris) { _modelStatus.value = it }
            if (n == 0) throw IllegalStateException(
                "Tanlangan fayllar orasida kerakli tarjima fayli yo'q. Kerak: config.json, tokenizer.json, " +
                    "tokenizer_config.json, encoder_model_quantized.onnx, decoder_model_merged_quantized.onnx"
            )
        }
    }

    fun clearWork(context: Context) {
        if (isBusy) {
            toast("Hozir jarayon ketyapti. Avval uni tugating yoki bekor qiling.")
            return
        }
        val app = context.applicationContext
        viewModelScope.launch {
            val before = withContext(Dispatchers.IO) {
                val b = pipeline.usedBytes(app)
                pipeline.clearWork(app)
                b
            }
            _videoUri.value = null
            _hasTranscript.value = false
            _translationUnits.value = emptyList()
            _approved.value = false
            _plainOriginal.value = ""
            _state.value = PipelineState.Idle
            _workVersion.update { it + 1 }
            toast(if (before > 0) "Tozalandi ✓ ${mb(before)} bo'shatildi" else "Tozalanadigan fayl yo'q ✓")
        }
    }

    suspend fun usedBytes(context: Context): Long =
        withContext(Dispatchers.IO) { pipeline.usedBytes(context.applicationContext) }

    // ------------------------------------------------------------------ ichki

    private fun mb(b: Long) = "${b / (1024 * 1024)} MB"

    private fun launchTask(context: Context, label: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        pipeline.resetCancel()
        _cancelling.value = false
        _busy.value = true
        _state.value = PipelineState.Running(label, 0f, "Boshlanmoqda…")
        job = viewModelScope.launch {
            PipelineService.start(app, "$label ishlamoqda…")
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserCancelled) {
                _state.value = PipelineState.Idle
                toast("Bekor qilindi ✓")
            } catch (e: OutOfMemoryError) {
                Timber.e(e, "OOM")
                _state.value = PipelineState.Error("Xotira yetmadi. Kichikroq video tanlang yoki Sozlamalarda matn aniqlashni \"Tez\" rejimga o'tkazing.")
            } catch (e: Exception) {
                Timber.e(e, "Pipeline xatosi")
                _state.value = PipelineState.Error(e.message ?: e.toString())
            } finally {
                PipelineService.stop(app)
                _cancelling.value = false
                _busy.value = false
            }
        }
    }

    // ------------------------------------------------------------------ yangilanish

    private val _update = MutableStateFlow<UpdateChecker.Result.Available?>(null)
    val update: StateFlow<UpdateChecker.Result.Available?> = _update.asStateFlow()
    private val _updateMessage = MutableStateFlow<String?>(null)
    val updateMessage: StateFlow<String?> = _updateMessage.asStateFlow()

    /** manual = true: foydalanuvchi o'zi bosdi (natija har doim ko'rsatiladi). */
    fun checkUpdate(context: Context, manual: Boolean) {
        val app = context.applicationContext
        if (!manual && !UpdateChecker.shouldAutoCheck(app)) return
        viewModelScope.launch {
            if (manual) _updateMessage.value = "Tekshirilmoqda…"
            val r = withContext(Dispatchers.IO) { UpdateChecker.check(app) }
            if (r !is UpdateChecker.Result.Failed) UpdateChecker.markChecked(app)
            when (r) {
                is UpdateChecker.Result.Available -> {
                    if (manual || !UpdateChecker.isSkipped(app, r.build)) _update.value = r
                    _updateMessage.value = "Yangi versiya mavjud: ${r.versionName}"
                }
                UpdateChecker.Result.UpToDate -> if (manual) _updateMessage.value = "Sizda eng so'nggi versiya ✓"
                is UpdateChecker.Result.Failed -> if (manual) _updateMessage.value = r.message
            }
        }
    }

    private val _updateProgress = MutableStateFlow<Float?>(null)
    /** null = yuklanmayapti; 0..1 = yuklash jarayoni. */
    val updateProgress: StateFlow<Float?> = _updateProgress.asStateFlow()

    /** Yangilanish APK'ni ilova ichida yuklaydi va o'rnatuvchini ochadi. */
    fun downloadUpdate(context: Context) {
        val app = context.applicationContext
        val u = _update.value ?: return
        if (_updateProgress.value != null) return
        viewModelScope.launch {
            _updateProgress.value = 0f
            _updateMessage.value = "Yangilanish yuklanmoqda…"
            try {
                val apk = withContext(Dispatchers.IO) {
                    UpdateChecker.download(app, u) { p -> _updateProgress.value = p }
                }
                _updateProgress.value = null
                val started = UpdateChecker.install(app, apk)
                _updateMessage.value = if (started) {
                    "O'rnatish oynasi ochildi: \"Yangilash\" ni bosing"
                } else {
                    "\"Noma'lum manbalar\" ruxsatini yoqing, so'ng qayta \"Yuklab olish\" ni bosing"
                }
            } catch (e: CancellationException) {
                _updateProgress.value = null
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Yangilanishni yuklash xatosi")
                _updateProgress.value = null
                _updateMessage.value = "Yuklab bo'lmadi: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun dismissUpdate(context: Context, skipThis: Boolean) {
        _update.value?.let { if (skipThis) UpdateChecker.skip(context.applicationContext, it.build) }
        _update.value = null
    }

    // ------------------------------------------------------------------ navbat

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue.asStateFlow()
    private var queueSeq = 0L

    fun addToQueue(context: Context, uris: List<Uri>) {
        val app = context.applicationContext
        val items = uris.map { QueueItem(id = ++queueSeq, uri = it, name = FileUtils.displayName(app, it)) }
        _queue.update { it + items }
    }

    fun removeFromQueue(id: Long) {
        _queue.update { list -> list.filterNot { it.id == id && it.status != QueueStatus.RUNNING } }
    }

    fun clearFinishedQueue() {
        _queue.update { list -> list.filter { it.status == QueueStatus.WAITING || it.status == QueueStatus.RUNNING } }
    }

    private fun updateItem(id: Long, f: (QueueItem) -> QueueItem) {
        _queue.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    /** Navbatdagi videolarni ketma-ket avtomatik dublyaj qiladi (tarjimani tekshirmasdan). */
    fun startQueue(context: Context) {
        if (isBusy) return
        val app = context.applicationContext
        if (_queue.value.none { it.status == QueueStatus.WAITING }) return
        val cfg = _config.value
        if (!_models.value.let { it.whisper && (it.nllb || cfg.onlineReady) && it.vits }) {
            _modelStatus.value = "Xato: navbat uchun uchala komponent (matn aniqlash, tarjima, o'zbekcha ovoz) kerak"
            return
        }
        releasePreview()
        pipeline.resetCancel()
        _cancelling.value = false
        _busy.value = true
        // Navbat umumiy ish papkasidan foydalanadi: joriy bitta-video loyihasi tozalanadi
        _videoUri.value = null
        _hasTranscript.value = false
        _translationUnits.value = emptyList()
        _approved.value = false
        _state.value = PipelineState.Idle

        job = viewModelScope.launch {
            PipelineService.start(app, "Navbat ishlamoqda…")
            try {
                while (true) {
                    val item = _queue.value.firstOrNull { it.status == QueueStatus.WAITING } ?: break
                    updateItem(item.id) { it.copy(status = QueueStatus.RUNNING, progress = 0f, message = "Boshlanmoqda…") }
                    try {
                        var skipped = 0
                        val out = withContext(Dispatchers.IO) {
                            pipeline.transcribe(app, item.uri, cfg) { p, m ->
                                updateItem(item.id) { it.copy(progress = p * 0.25f, message = "Matn: $m") }
                            }
                            val translated = pipeline.translate(app, cfg) { p, m ->
                                updateItem(item.id) { it.copy(progress = 0.25f + p * 0.25f, message = "Tarjima: $m") }
                            }
                            // Tarjima qilinmagan gaplar o'tkazib yuboriladi (avtomatik rejimda tahrir yo'q)
                            skipped = translated.count { it.check && it.uzText == it.text }
                            val units = translated.map { if (it.check && it.uzText == it.text) it.copy(uzText = "", check = false) else it.copy(check = false) }
                            pipeline.saveTranslation(app, units)
                            pipeline.markApproved(app)
                            pipeline.dub(app, units, cfg, item.name) { p, m ->
                                updateItem(item.id) { it.copy(progress = 0.5f + p * 0.5f, message = m) }
                            }.also { autoSave(app, it, cfg) }
                        }
                        updateItem(item.id) {
                            it.copy(
                                status = QueueStatus.DONE, progress = 1f, outputPath = out.absolutePath,
                                message = if (skipped > 0) "Tayyor ✓ • ⚠ $skipped ta gap tarjima qilinmadi, o'tkazildi" else "Tayyor ✓"
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: UserCancelled) {
                        updateItem(item.id) { it.copy(status = QueueStatus.CANCELLED, message = "Bekor qilindi") }
                        break
                    } catch (e: OutOfMemoryError) {
                        updateItem(item.id) { it.copy(status = QueueStatus.FAILED, message = "Xotira yetmadi") }
                    } catch (e: Exception) {
                        Timber.e(e, "Navbat xatosi")
                        updateItem(item.id) { it.copy(status = QueueStatus.FAILED, message = e.message ?: e.toString()) }
                    }
                    withContext(Dispatchers.IO) { pipeline.clearWork(app) }
                }
            } finally {
                PipelineService.stop(app)
                _cancelling.value = false
                _busy.value = false
                val done = _queue.value.count { it.status == QueueStatus.DONE }
                toast("Navbat tugadi: $done ta video tayyor")
                if (done > 0) PipelineService.notifyDone(app, "Navbat tugadi", "$done ta video dublyaj qilindi")
            }
        }
    }

    private fun autoSave(app: Context, out: File, cfg: AppConfig) {
        if (!cfg.autoSaveGallery) return
        try {
            OutputUtils.saveToGallery(app, out)
        } catch (e: Exception) {
            Timber.w(e, "Galereyaga avtomatik saqlanmadi")
        }
    }

    private var quietNoop = false

    private fun launchModelTask(app: Context, label: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        pipeline.resetCancel()
        _cancelling.value = false
        _busy.value = true
        _modelStatus.value = label
        job = viewModelScope.launch {
            PipelineService.start(app, label)
            try {
                withContext(Dispatchers.IO) { block() }
                _modelStatus.value = if (quietNoop) null else "Tayyor ✓"
                quietNoop = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserCancelled) {
                _modelStatus.value = "Bekor qilindi"
                toast("Bekor qilindi ✓")
            } catch (e: Exception) {
                Timber.e(e, "Model xatosi")
                _modelStatus.value = "Xato: ${e.message}"
            } finally {
                PipelineService.stop(app)
                _cancelling.value = false
                _busy.value = false
                _models.value = withContext(Dispatchers.IO) { pipeline.modelsInfo(app, _config.value) }
            }
        }
    }
}
