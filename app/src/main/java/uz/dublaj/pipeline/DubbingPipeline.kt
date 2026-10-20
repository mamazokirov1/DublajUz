package uz.dublaj.pipeline

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import uz.dublaj.data.AppConfig
import uz.dublaj.data.ModelsInfo
import uz.dublaj.data.TranscriptUnit
import uz.dublaj.data.TranslationUnit
import uz.dublaj.data.UserCancelled
import uz.dublaj.stt.ModelDownloader
import uz.dublaj.stt.SttFixes
import uz.dublaj.stt.WhisperRecognizer
import uz.dublaj.text.TextNormalizer
import uz.dublaj.translate.NllbModelDownloader
import uz.dublaj.translate.NllbTranslator
import uz.dublaj.tts.VitsSynthesizer
import uz.dublaj.util.FileUtils
import uz.dublaj.util.ModelImporter
import uz.dublaj.util.StreamResampler
import uz.dublaj.util.WavFile
import uz.dublaj.util.WorkDir
import java.io.File

/**
 * Butun zanjir: video → audio → Whisper → NLLB → (tahrir) → VITS → joylash → mux.
 * Har bir bosqich oxirida og'ir resurslar (model) bo'shatiladi.
 */
class DubbingPipeline {

    companion object {
        private const val OUT_SR = 22050
    }

    @Volatile private var cancelled = false
    private var localVideo: File? = null
    private var workDir: WorkDir? = null

    fun cancel() { cancelled = true }
    fun resetCancel() { cancelled = false }

    private fun checkCancelled() {
        if (cancelled) throw UserCancelled()
    }

    /** Har progress chaqiruvida bekor qilishni tekshiradi (blokli sikllar shu orqali to'xtaydi). */
    private fun wrap(cb: (Float, String) -> Unit): (Float, String) -> Unit = { p, m ->
        checkCancelled()
        cb(p, m)
    }

    private fun ensure(context: Context): WorkDir =
        workDir ?: WorkDir(context.applicationContext).also { workDir = it }

    fun work(context: Context): WorkDir = ensure(context)

    // ------------------------------------------------------------------ modellar

    fun modelsInfo(context: Context, config: AppConfig): ModelsInfo {
        val dir = ensure(context)
        val wi = WhisperRecognizer.info(config.whisperModel)
        return ModelsInfo(
            whisper = ModelDownloader.isDownloaded(WhisperRecognizer.defaultModelFile(dir, wi.id), wi.minBytes),
            nllb = NllbTranslator.isReady(dir),
            vits = VitsSynthesizer.isReady(dir)
        )
    }

    fun downloadWhisper(context: Context, modelId: String, onProgress: (Long, Long) -> Unit) {
        val dir = ensure(context)
        val wi = WhisperRecognizer.info(modelId)
        ModelDownloader.download(
            WhisperRecognizer.defaultModelFile(dir, wi.id), wi.fileName, wi.minBytes
        ) { d, t -> checkCancelled(); onProgress(d, t) }
    }

    fun downloadNllb(context: Context, onProgress: (String, Int, Int, Long, Long) -> Unit) {
        val dir = ensure(context)
        NllbModelDownloader.downloadAll(dir) { f, i, n, b, t ->
            checkCancelled(); onProgress(f, i, n, b, t)
        }
    }

    fun importWhisper(context: Context, uri: Uri, modelId: String) {
        val dir = ensure(context)
        val wi = WhisperRecognizer.info(modelId)
        ModelImporter.importFile(context, uri, WhisperRecognizer.defaultModelFile(dir, wi.id), wi.minBytes)
    }

    fun importVitsModel(context: Context, uri: Uri) {
        val (m, _) = VitsSynthesizer.modelFiles(ensure(context))
        ModelImporter.importFile(context, uri, m, 100_000)
    }

    fun importVitsConfig(context: Context, uri: Uri) {
        val (_, c) = VitsSynthesizer.modelFiles(ensure(context))
        ModelImporter.importFile(context, uri, c, 10)
    }

    fun importNllb(context: Context, treeUri: Uri, onProgress: (String) -> Unit): Int =
        ModelImporter.importNllbTree(context, treeUri, ensure(context), onProgress)

    fun clearWork(context: Context) {
        ensure(context).clearWork()
        localVideo = null
    }

    fun usedBytes(context: Context): Long = ensure(context).usedBytes()

    // ------------------------------------------------------------------ tiklash

    data class Restored(
        val videoFile: File?,
        val hasTranscript: Boolean,
        val units: List<TranslationUnit>,
        val approved: Boolean
    )

    /** Ilova qayta ochilganda (process o'ldirilgan bo'lsa) holatni diskdan tiklaydi. */
    fun restore(context: Context): Restored {
        val dir = ensure(context)
        var video: File? = null
        val hasTranscript = dir.transcriptJson.isFile
        if (hasTranscript) {
            try {
                val meta = JSONObject(dir.transcriptJson.readText()).optJSONObject("meta")
                val path = meta?.optString("video").orEmpty()
                if (path.isNotEmpty() && File(path).isFile) video = File(path)
            } catch (e: Exception) {
                Timber.w(e, "restore meta")
            }
        }
        localVideo = video
        val units = loadTranslation(context)
        return Restored(video, hasTranscript && video != null, units, dir.approvedFlag.isFile && units.isNotEmpty())
    }

    private fun prepareVideo(context: Context, uri: Uri, dir: WorkDir): File {
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            if (f.isFile && f.parentFile?.canonicalPath == dir.input.canonicalPath) return f
        }
        val dest = File(dir.input, FileUtils.localVideoName(context, uri))
        return FileUtils.copyUriToFile(context, uri, dest)
    }

    // ------------------------------------------------------------------ 1) STT

    suspend fun transcribe(
        context: Context,
        videoUri: Uri,
        config: AppConfig,
        onProgress: (Float, String) -> Unit
    ): List<TranscriptUnit> {
        val progress = wrap(onProgress)
        val dir = ensure(context)

        val wi = WhisperRecognizer.info(config.whisperModel)
        val modelFile = WhisperRecognizer.defaultModelFile(dir, wi.id)
        if (!ModelDownloader.isDownloaded(modelFile, wi.minBytes)) {
            throw IllegalStateException("Whisper (${wi.id}) modeli yo'q. Sozlamalar → Whisper modelini yuklang.")
        }

        progress(0.02f, "Video nusxalanmoqda…")
        val video = prepareVideo(context, videoUri, dir)
        localVideo = video

        // Eski natijalar yaroqsiz
        dir.translationJson.delete()
        dir.approvedFlag.delete()
        dir.mixWav.delete()

        progress(0.1f, "Audio ajratilmoqda…")
        AudioExtractor.extractToWav(video, dir.extractedWav, 16000) { p ->
            progress(0.1f + 0.3f * p, "Audio ajratilmoqda…")
        }

        val recognizer = WhisperRecognizer(context.applicationContext)
        val result = try {
            progress(0.45f, "Whisper modeli yuklanmoqda…")
            recognizer.ensureModel(modelFile)
            recognizer.transcribe(dir.extractedWav, config.sourceLang) { p, m ->
                progress(0.5f + 0.45f * p, m)
            }
        } finally {
            recognizer.release()          // NLLB uchun xotirani bo'shatamiz
        }
        val fixes = SttFixes.load(context, File(dir.root, "stt_fixes.json"))
        val units: List<TranscriptUnit> = SttFixes.apply(result.units, fixes)
        val lang: String = result.language
        dir.extractedWav.delete()

        if (units.isEmpty()) throw IllegalStateException("Videoda nutq topilmadi")

        val meta = JSONObject().apply {
            put("language", lang)
            put("video", video.absolutePath)
            put("count", units.size)
        }
        val arr = JSONArray()
        for (u in units) {
            arr.put(JSONObject().apply {
                put("id", u.id); put("start", u.start); put("end", u.end)
                put("text", u.text); put("language", u.language)
            })
        }
        dir.transcriptJson.writeText(JSONObject().put("meta", meta).put("units", arr).toString(2))
        progress(1f, "Transkript tayyor: ${units.size} segment")
        return units
    }

    private fun loadTranscript(context: Context): List<TranscriptUnit> {
        val dir = ensure(context)
        if (!dir.transcriptJson.isFile) throw IllegalStateException("Transkript yo'q — avval 'Transkript' ni bajaring")
        val arr = JSONObject(dir.transcriptJson.readText()).getJSONArray("units")
        return (0 until arr.length()).map { i ->
            val u = arr.getJSONObject(i)
            TranscriptUnit(
                u.getInt("id"), u.getDouble("start"), u.getDouble("end"),
                u.getString("text"), u.optString("language", "en")
            )
        }
    }

    // ------------------------------------------------------------------ 2) tarjima

    fun translate(
        context: Context,
        config: AppConfig,
        onProgress: (Float, String) -> Unit
    ): List<TranslationUnit> {
        val progress = wrap(onProgress)
        val dir = ensure(context)
        val src = loadTranscript(context)
        if (!NllbTranslator.isReady(dir)) {
            throw IllegalStateException(
                "NLLB modeli to'liq emas. Sozlamalar → NLLB ni yuklang yoki import qiling."
            )
        }

        val norm = TextNormalizer.fromAssets(context.applicationContext)
        progress(0.02f, "NLLB modeli yuklanmoqda…")
        val translator = NllbTranslator.load(dir, config.threads)
        val result = ArrayList<TranslationUnit>()
        var failed = 0
        var firstError: String? = null
        try {
            src.forEachIndexed { i, u ->
                progress(0.1f + 0.85f * i / src.size, "Tarjima ${i + 1}/${src.size}")
                try {
                    val uz = norm.normalize(translator.translate(u.text, u.language))
                    if (uz.isBlank()) throw IllegalStateException("Bo'sh tarjima")
                    result.add(TranslationUnit(u.id, u.start, u.end, u.text, uz, u.language, false))
                } catch (e: UserCancelled) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Tarjima xato #${u.id}")
                    failed++
                    if (firstError == null) firstError = e.message
                    // Jim "asl matnni o'zbek TTS ga berish" o'rniga — foydalanuvchi tuzatsin
                    result.add(TranslationUnit(u.id, u.start, u.end, u.text, u.text, u.language, true))
                }
            }
        } finally {
            translator.close()
        }
        if (failed == src.size) {
            throw IllegalStateException("Hech bir segment tarjima qilinmadi: $firstError")
        }

        dir.approvedFlag.delete()
        saveTranslation(context, result)
        progress(1f, if (failed > 0) "Tayyor ($failed ta segment tekshirilsin)" else "Tarjima tayyor")
        return result
    }

    fun saveTranslation(context: Context, units: List<TranslationUnit>) {
        val dir = ensure(context)
        val arr = JSONArray()
        for (u in units) {
            arr.put(JSONObject().apply {
                put("id", u.id); put("start", u.start); put("end", u.end)
                put("text", u.text); put("uzText", u.uzText)
                put("language", u.language); put("check", u.check)
            })
        }
        dir.translationJson.writeText(arr.toString(2))
    }

    fun loadTranslation(context: Context): List<TranslationUnit> {
        val dir = ensure(context)
        if (!dir.translationJson.isFile) return emptyList()
        return try {
            val arr = JSONArray(dir.translationJson.readText())
            (0 until arr.length()).map { i ->
                val u = arr.getJSONObject(i)
                TranslationUnit(
                    u.getInt("id"), u.getDouble("start"), u.getDouble("end"),
                    u.getString("text"), u.getString("uzText"),
                    u.optString("language", "en"), u.optBoolean("check", false)
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "translation.json buzuq")
            emptyList()
        }
    }

    fun markApproved(context: Context) {
        ensure(context).approvedFlag.writeText("approved")
    }

    fun clearApproval(context: Context) {
        ensure(context).approvedFlag.delete()
    }

    fun isApproved(context: Context): Boolean = ensure(context).approvedFlag.isFile

    // ------------------------------------------------------------------ 3) dublyaj

    fun dub(
        context: Context,
        units: List<TranslationUnit>,
        config: AppConfig,
        onProgress: (Float, String) -> Unit
    ): File {
        val progress = wrap(onProgress)
        val dir = ensure(context)
        if (!dir.approvedFlag.isFile) throw IllegalStateException("Tarjima tasdiqlanmagan")
        if (!VitsSynthesizer.isReady(dir)) {
            throw IllegalStateException(
                "VITS (TTS) modeli yo'q. Sozlamalar → VITS modelini (.onnx) va uz_tts_base.json ni import qiling."
            )
        }
        val video = localVideo?.takeIf { it.isFile }
            ?: throw IllegalStateException("Video topilmadi — videoni qayta tanlang")

        val duration = AudioExtractor.probeDuration(video)
        if (duration <= 0.0) throw IllegalStateException("Video davomiyligi aniqlanmadi")

        val active = units.filter { it.uzText.isNotBlank() && it.start < duration }.sortedBy { it.start }
        if (active.isEmpty()) throw IllegalStateException("Dublyaj uchun matn yo'q")

        FileUtils.ensureSpace(dir.temp, (duration * OUT_SR * 2 * 2).toLong() + 100L * 1024 * 1024)
        FileUtils.ensureSpace(dir.output, video.length() + 50L * 1024 * 1024)

        val norm = TextNormalizer.fromAssets(context.applicationContext)
        val placer = SegmentPlacer(
            totalSec = duration, sr = OUT_SR,
            maxSpeed = config.maxAtempo,
            marginSec = config.marginS.toDouble(),
            gapSec = config.gapS.toDouble(),
            clipDir = dir.temp
        )
        val clips = ArrayList<SegmentPlacer.Clip>()

        progress(0.02f, "TTS modeli yuklanmoqda…")
        val synth = VitsSynthesizer.load(dir, norm, threads = config.threads)
        try {
            active.forEachIndexed { i, u ->
                progress(0.05f + 0.55f * i / active.size, "TTS ${i + 1}/${active.size}")
                val next = active.getOrNull(i + 1)?.start
                val slot = (next ?: duration) - u.start
                val seg = synth.synthesize(u.uzText, slot.coerceAtLeast(0.3))
                val pcm = StreamResampler.resample(seg.pcm, seg.sampleRate, OUT_SR)
                placer.place(u.id, u.start, next, pcm)?.let { clips.add(it) }
            }
        } finally {
            synth.close()
        }
        if (clips.isEmpty()) throw IllegalStateException("TTS hech narsa sintez qilmadi")

        progress(0.62f, "Asl audio tayyorlanmoqda…")
        if (!dir.mixWav.isFile) {
            AudioExtractor.extractToWav(video, dir.mixWav, OUT_SR) { p ->
                progress(0.62f + 0.1f * p, "Asl audio tayyorlanmoqda…")
            }
        }

        val outFile = File(dir.output, "dublaj_${System.currentTimeMillis()}.mp4")
        val totalSamples = (duration * OUT_SR).toLong()
        try {
            DubMixer(OUT_SR, WavFile(dir.mixWav), clips, config.originalVolume, config.uzbekVolume).use { mixer ->
                VideoMuxer.mux(video, outFile, OUT_SR, totalSamples, mixer) { p ->
                    progress(0.72f + 0.27f * p, "Video yig'ilmoqda…")
                }
            }
        } finally {
            clips.forEach { it.file.delete() }
            dir.mixWav.delete()
        }
        progress(1f, "Tayyor")
        return outFile
    }
}
