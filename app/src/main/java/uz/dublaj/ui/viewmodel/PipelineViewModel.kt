package uz.dublaj.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import uz.dublaj.data.AppConfig
import uz.dublaj.data.ModelsInfo
import uz.dublaj.data.PipelineState
import uz.dublaj.data.TranslationUnit
import uz.dublaj.data.UserCancelled
import uz.dublaj.pipeline.DubbingPipeline
import uz.dublaj.service.PipelineService
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

    // ------------------------------------------------------------------ holat

    fun restore(context: Context) {
        if (restored) return
        restored = true
        val app = context.applicationContext
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { pipeline.restore(app) }
            if (_videoUri.value == null && r.videoFile != null) _videoUri.value = Uri.fromFile(r.videoFile)
            _hasTranscript.value = r.hasTranscript
            _translationUnits.value = r.units
            _approved.value = r.approved
        }
        refreshModels(app)
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
        if (modelChanged && context != null) refreshModels(context)
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
        pipeline.cancel()
    }

    // ------------------------------------------------------------------ bosqichlar

    fun transcribe(context: Context) {
        val uri = _videoUri.value ?: return
        launchTask(context, "Transkript") {
            val cfg = _config.value
            withContext(Dispatchers.IO) {
                pipeline.transcribe(context.applicationContext, uri, cfg) { p, m ->
                    _state.value = PipelineState.Running("Transkript", p, m)
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
        launchTask(context, "Dublyaj") {
            val cfg = _config.value
            val units = _translationUnits.value
            val out: File = withContext(Dispatchers.IO) {
                pipeline.dub(context.applicationContext, units, cfg) { p, m ->
                    _state.value = PipelineState.Running("Dublyaj", p, m)
                }
            }
            _state.value = PipelineState.Success(out.absolutePath)
        }
    }

    /** Transkript + tarjima; keyin foydalanuvchi tahrir qiladi. */
    fun transcribeAndTranslate(context: Context) {
        val uri = _videoUri.value ?: return
        launchTask(context, "Transkript + tarjima") {
            val cfg = _config.value
            val app = context.applicationContext
            withContext(Dispatchers.IO) {
                pipeline.transcribe(app, uri, cfg) { p, m ->
                    _state.value = PipelineState.Running("Transkript", p * 0.5f, m)
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
        }
    }

    // ------------------------------------------------------------------ modellar

    fun downloadWhisper(context: Context) {
        val app = context.applicationContext
        val id = _config.value.whisperModel
        launchModelTask(app, "Whisper yuklanmoqda…") {
            pipeline.downloadWhisper(app, id) { d, t ->
                _modelStatus.value = "Whisper: ${mb(d)} / ${if (t > 0) mb(t) else "?"}"
            }
        }
    }

    fun downloadNllb(context: Context) {
        val app = context.applicationContext
        launchModelTask(app, "NLLB yuklanmoqda…") {
            pipeline.downloadNllb(app) { f, i, n, b, t ->
                _modelStatus.value = "NLLB [$i/$n] ${f.substringAfterLast('/')}: ${mb(b)} / ${if (t > 0) mb(t) else "?"}"
            }
        }
    }

    fun importWhisper(context: Context, uri: Uri) {
        val app = context.applicationContext
        val id = _config.value.whisperModel
        launchModelTask(app, "Whisper nusxalanmoqda…") { pipeline.importWhisper(app, uri, id) }
    }

    fun importVitsModel(context: Context, uri: Uri) {
        val app = context.applicationContext
        launchModelTask(app, "VITS nusxalanmoqda…") { pipeline.importVitsModel(app, uri) }
    }

    fun importVitsConfig(context: Context, uri: Uri) {
        val app = context.applicationContext
        launchModelTask(app, "TTS config nusxalanmoqda…") { pipeline.importVitsConfig(app, uri) }
    }

    fun importNllb(context: Context, treeUri: Uri) {
        val app = context.applicationContext
        launchModelTask(app, "NLLB nusxalanmoqda…") {
            val n = pipeline.importNllb(app, treeUri) { _modelStatus.value = it }
            if (n < 5) throw IllegalStateException("Papkada kerakli fayllar topilmadi ($n/5 topildi)")
        }
    }

    fun importNllbFiles(context: Context, uris: List<Uri>) {
        val app = context.applicationContext
        launchModelTask(app, "NLLB fayllari nusxalanmoqda…") {
            val n = pipeline.importNllbFiles(app, uris) { _modelStatus.value = it }
            if (n == 0) throw IllegalStateException(
                "Tanlangan fayllar orasida kerakli NLLB fayli yo'q. Kerak: config.json, tokenizer.json, " +
                    "tokenizer_config.json, encoder_model_quantized.onnx, decoder_model_merged_quantized.onnx"
            )
        }
    }

    fun clearWork(context: Context) {
        if (isBusy) return
        val app = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            pipeline.clearWork(app)
            _videoUri.value = null
            _hasTranscript.value = false
            _translationUnits.value = emptyList()
            _approved.value = false
            _state.value = PipelineState.Idle
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
        _state.value = PipelineState.Running(label, 0f, "Boshlanmoqda…")
        job = viewModelScope.launch {
            PipelineService.start(app, "$label ishlamoqda…")
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserCancelled) {
                _state.value = PipelineState.Idle
            } catch (e: OutOfMemoryError) {
                Timber.e(e, "OOM")
                _state.value = PipelineState.Error("Xotira yetmadi. Kichikroq video yoki kichikroq Whisper modelini tanlang.")
            } catch (e: Exception) {
                Timber.e(e, "Pipeline xatosi")
                _state.value = PipelineState.Error(e.message ?: e.toString())
            } finally {
                PipelineService.stop(app)
            }
        }
    }

    private fun launchModelTask(app: Context, label: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        pipeline.resetCancel()
        _modelStatus.value = label
        job = viewModelScope.launch {
            PipelineService.start(app, label)
            try {
                withContext(Dispatchers.IO) { block() }
                _modelStatus.value = "Tayyor ✓"
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserCancelled) {
                _modelStatus.value = "Bekor qilindi"
            } catch (e: Exception) {
                Timber.e(e, "Model xatosi")
                _modelStatus.value = "Xato: ${e.message}"
            } finally {
                PipelineService.stop(app)
                _models.value = withContext(Dispatchers.IO) { pipeline.modelsInfo(app, _config.value) }
            }
        }
    }
}
