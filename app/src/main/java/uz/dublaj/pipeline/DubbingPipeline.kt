package uz.dublaj.pipeline

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
import uz.dublaj.text.SentenceMerger
import uz.dublaj.text.TextNormalizer
import uz.dublaj.text.TranslationQa
import uz.dublaj.translate.GeminiTranslator
import uz.dublaj.translate.NllbModelDownloader
import uz.dublaj.translate.NllbTranslator
import uz.dublaj.tts.VitsSynthesizer
import uz.dublaj.util.FileUtils
import uz.dublaj.util.ModelImporter
import uz.dublaj.util.StreamResampler
import uz.dublaj.util.WavFile
import uz.dublaj.util.WorkDir
import uz.dublaj.util.ExportUtils
import uz.dublaj.util.HistoryStore
import uz.dublaj.util.SrtExporter
import java.io.File

/**
 * Butun zanjir: video → audio → Whisper → NLLB → (tahrir) → VITS → joylash → mux.
 * Har bir bosqich oxirida og'ir resurslar (model) bo'shatiladi.
 */
class DubbingPipeline {

    companion object {
        private const val OUT_SR = 22050
        /** Whisper o'z oqimida ishlaydi; bekor qilinganda natija tashlab yuboriladi, tugagach xotira bo'shatiladi. */
        private val sttScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        @Volatile private var sttBusy = false
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

    /** Ovozni tinglab ko'rish uchun TTS modelini yuklaydi (ViewModel keshlaydi). */
    fun loadPreviewSynth(context: Context, config: AppConfig): VitsSynthesizer {
        val dir = ensure(context)
        if (!VitsSynthesizer.isReady(dir)) {
            throw IllegalStateException("O'zbekcha ovoz komponenti yo'q. Sozlamalarda ovoz fayllarini (.onnx va .json) qo'shing.")
        }
        return VitsSynthesizer.load(
            dir, TextNormalizer.fromAssets(context.applicationContext),
            threads = config.threads, pitchSemitones = config.voicePitch
        )
    }

    fun autoImport(context: Context, onProgress: (String) -> Unit): uz.dublaj.util.StorageScanner.ImportReport =
        uz.dublaj.util.StorageScanner.importAll(context, ensure(context), onProgress)

    fun importNllbFiles(context: Context, uris: List<android.net.Uri>, onProgress: (String) -> Unit): Int =
        ModelImporter.importNllbFiles(context, uris, ensure(context), onProgress)

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
            throw IllegalStateException("Matn aniqlash komponenti yo'q. Sozlamalar bo'limida uni yuklab oling.")
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

        // Oldingi (bekor qilingan) matn aniqlash hali tugamagan bo'lsa, xotira to'qnashmasligi uchun kutamiz
        while (sttBusy) {
            checkCancelled()
            progress(0.42f, "Oldingi amal tugashi kutilmoqda…")
            delay(500)
        }
        val recognizer = WhisperRecognizer(context.applicationContext)
        var detached = false
        val result = try {
            progress(0.45f, "Nutq tanish komponenti yuklanmoqda…")
            recognizer.ensureModel(modelFile)
            sttBusy = true
            val job = sttScope.async {
                try {
                    recognizer.transcribe(dir.extractedWav, config.sourceLang) { p, m ->
                        progress(0.5f + 0.45f * p, m)
                    }
                } finally {
                    sttBusy = false
                }
            }
            while (!job.isCompleted) {
                if (cancelled) {
                    // Native kod to'xtatilmaydi: UI darhol bo'shatiladi, model esa tugagach yopiladi
                    detached = true
                    job.invokeOnCompletion { recognizer.release() }
                    throw UserCancelled()
                }
                delay(150)
            }
            job.await()
        } finally {
            if (!detached) recognizer.release()          // tarjima uchun xotirani bo'shatamiz
        }
        checkCancelled()
        val fixes = SttFixes.load(context, File(dir.root, "stt_fixes.json"))
        val fixedUnits: List<TranscriptUnit> = SttFixes.apply(result.units, fixes)
        val units: List<TranscriptUnit> = try {
            SpeechSnap.refine(dir.extractedWav, fixedUnits)
        } catch (e: Exception) {
            Timber.w(e, "SpeechSnap ishlamadi")
            fixedUnits
        }
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
        if (!dir.transcriptJson.isFile) throw IllegalStateException("Matn hali ajratilmagan — avval \"Matn ajratish\" ni bajaring")
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
        val raw = loadTranscript(context)
        // Whisper bo'laklarini TO'LIQ GAPLARGA birlashtiramiz: tarjima gap bo'yicha bo'ladi
        val src = SentenceMerger.merge(raw)
        Timber.i("Gaplar: ${raw.size} segment → ${src.size} gap")
        val onlineWanted = config.onlineReady
        val nllbReady = NllbTranslator.isReady(dir)
        if (!onlineWanted && !nllbReady) {
            throw IllegalStateException(
                "Tarjima komponenti to'liq emas. Sozlamalar bo'limida uni qaytadan yuklab oling."
            )
        }

        val norm = TextNormalizer.fromAssets(context.applicationContext)
        val result = ArrayList<TranslationUnit>()
        var failed = 0
        var flagged = 0
        var firstError: String? = null

        // ---- 1) Gemini (kontekstli, internet bilan). Muvaffaqiyatsiz gaplar keyin NLLB'ga tushadi.
        val online = HashMap<Int, GeminiTranslator.Result>()
        var onlineNote: String? = null
        if (onlineWanted) {
            progress(0.02f, "Gemini bilan tarjima…")
            val gem = GeminiTranslator(config.geminiKey, config.geminiModel.ifBlank { GeminiTranslator.DEFAULT_MODEL })
            val ctx = ArrayList<Pair<String, String>>()
            var idx = 0
            try {
                while (idx < src.size) {
                    checkCancelled()
                    // bo'lak: ko'pi bilan 18 gap yoki ~3500 belgi
                    val chunk = ArrayList<TranscriptUnit>()
                    var chars = 0
                    while (idx + chunk.size < src.size && chunk.size < 18 && (chunk.isEmpty() || chars < 3500)) {
                        val u = src[idx + chunk.size]
                        chunk.add(u)
                        chars += u.text.length
                    }
                    progress(0.05f + 0.85f * idx / src.size, "Gemini ${idx + 1}–${idx + chunk.size}/${src.size}")
                    val items = chunk.mapIndexed { k, u ->
                        val nextStart = src.getOrNull(idx + k + 1)?.start
                        val slot = maxOf(u.end - u.start, (nextStart ?: (u.end + 1.5)) - u.start)
                        GeminiTranslator.Item(u.id, u.text, (slot * GeminiTranslator.CHARS_PER_SEC).toInt().coerceAtLeast(20))
                    }
                    val lang = chunk.first().language
                    val got: Map<Int, GeminiTranslator.Result> = try {
                        gem.translateChunk(
                            lang, ctx.takeLast(4), items, src.getOrNull(idx + chunk.size)?.text,
                            GeminiTranslator.Style(config.videoContext, config.addressForm, config.tone, config.glossary)
                        )
                    } catch (e: GeminiTranslator.GeminiException) {
                        if (e.fatal) throw e
                        Timber.w("Gemini bo'lak xato: ${e.message}")
                        if (onlineNote == null) onlineNote = e.message
                        emptyMap()
                    }
                    for (u in chunk) {
                        val r = got[u.id] ?: continue
                        online[u.id] = r
                        ctx.add(Pair(u.text, r.uz))
                    }
                    idx += chunk.size
                }
            } catch (e: GeminiTranslator.GeminiException) {
                // kalit/ruxsat xatosi: onlayn qismni to'xtatamiz
                onlineNote = e.message
                Timber.w("Gemini to'xtatildi: ${e.message}")
            }
            if (online.size < src.size && !nllbReady) {
                if (online.isEmpty()) {
                    throw IllegalStateException("Gemini tarjima qilmadi: ${onlineNote ?: "noma'lum xato"}")
                }
            }
        }

        // ---- 2) NLLB (offline) — faqat kerak bo'lsa yuklanadi
        val needNllb = src.any { it.id !in online } && nllbReady
        var translator: NllbTranslator? = null
        try {
            if (needNllb) {
                progress(if (onlineWanted) 0.9f else 0.02f, "Tarjima komponenti yuklanmoqda…")
                translator = NllbTranslator.load(dir, config.threads)
            }
            src.forEachIndexed { i, u ->
                checkCancelled()
                progress(
                    if (onlineWanted) 0.9f + 0.09f * i / src.size else 0.1f + 0.85f * i / src.size,
                    "Tarjima ${i + 1}/${src.size}"
                )
                try {
                    val g = online[u.id]
                    if (g != null) {
                        // Gemini natijasi
                        val uz = norm.normalize(g.uz)
                        if (uz.isBlank()) throw IllegalStateException("Bo'sh tarjima")
                        val qa = TranslationQa.issue(u.text, g.uz)
                        val alts = ArrayList<String>()
                        alts.add(uz)
                        val sh = g.short?.let { norm.normalize(it) }
                        if (!sh.isNullOrBlank() && sh !in alts) alts.add(sh)
                        if (qa != null) {
                            flagged++
                            Timber.w("Tarjima shubhali #${u.id}: $qa")
                        }
                        result.add(TranslationUnit(u.id, u.start, u.end, u.text, uz, u.language, qa != null, alts))
                    } else {
                        val tr = translator ?: throw IllegalStateException(
                            onlineNote?.let { "Gemini: $it" } ?: "Tarjima komponenti yo'q"
                        )
                        val cands = tr.translateNBest(u.text, u.language)
                        if (cands.isEmpty()) throw IllegalStateException("Bo'sh tarjima")
                        // QA normalizatsiyadan OLDIN (aks holda raqamlar so'zga aylanib, hammasi "shubhali" bo'lib qoladi)
                        var chosenRaw = cands[0].text
                        var qa: String? = TranslationQa.issue(u.text, chosenRaw)
                        if (qa != null) {
                            for (c in cands.drop(1)) {
                                if (TranslationQa.issue(u.text, c.text) == null) {
                                    chosenRaw = c.text
                                    qa = null
                                    break
                                }
                            }
                        }
                        val uz = norm.normalize(chosenRaw)
                        if (uz.isBlank()) throw IllegalStateException("Bo'sh tarjima")
                        val alts = ArrayList<String>()
                        alts.add(uz)
                        for (c in cands) {
                            val n = norm.normalize(c.text)
                            if (n.isNotBlank() && n !in alts) alts.add(n)
                        }
                        if (qa != null) {
                            flagged++
                            Timber.w("Tarjima shubhali #${u.id}: $qa")
                        }
                        result.add(TranslationUnit(u.id, u.start, u.end, u.text, uz, u.language, qa != null, alts))
                    }
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
            translator?.close()
        }
        if (failed == src.size) {
            throw IllegalStateException("Hech bir segment tarjima qilinmadi: $firstError")
        }

        dir.approvedFlag.delete()
        saveTranslation(context, result)
        val onlineCount = online.size
        val done = if (onlineWanted) "Tarjima tayyor (Gemini: $onlineCount/${src.size})" else "Tarjima tayyor"
        progress(1f, if (failed + flagged > 0) "Tayyor (${failed + flagged} ta gap tekshirilsin)" else done)
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
                    u.optString("language", "en"), u.optBoolean("check", false),
                    u.optJSONArray("alts")?.let { arr -> (0 until arr.length()).map { k -> arr.getString(k) } } ?: emptyList()
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
        title: String = "",
        onProgress: (Float, String) -> Unit
    ): File {
        val progress = wrap(onProgress)
        val dir = ensure(context)
        if (!dir.approvedFlag.isFile) throw IllegalStateException("Tarjima tasdiqlanmagan")
        if (!VitsSynthesizer.isReady(dir)) {
            throw IllegalStateException(
                "O'zbekcha ovoz komponenti yo'q. Sozlamalarda ovoz fayllarini (.onnx va .json) qo'shing."
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
        val usedText = HashMap<Int, String>()

        progress(0.02f, "Ovoz komponenti yuklanmoqda…")
        val synth = VitsSynthesizer.load(
            dir, norm,
            noiseScale = config.voiceNoise,
            noiseScaleW = config.voiceNoiseW,
            minLengthScale = config.fitScale,
            pauseS = config.voicePause,
            threads = config.threads,
            baseLengthScale = 1f / config.voiceSpeed.coerceAtLeast(0.3f),
            pitchSemitones = config.voicePitch
        )
        try {
            active.forEachIndexed { i, u ->
                progress(0.05f + 0.55f * i / active.size, "Ovozlashtirish ${i + 1}/${active.size}")
                val next = active.getOrNull(i + 1)?.start
                val slot = (next ?: duration) - u.start
                val availSec = (slot - config.marginS).coerceAtLeast(0.3)
                var seg = synth.synthesize(u.uzText, slot.coerceAtLeast(0.3))
                // Sig'masa: qisqaroq tarjima nomzodini sinaymiz (faqat foydalanuvchi matnni o'zgartirmagan bo'lsa)
                if (seg.durationSec > availSec * 1.12 && u.alts.size > 1 && u.alts[0] == u.uzText) {
                    for (alt in u.alts.drop(1).take(3)) {
                        val s2 = synth.synthesize(alt, slot.coerceAtLeast(0.3))
                        if (s2.durationSec <= availSec * 1.12) {
                            seg = s2
                            usedText[u.id] = alt
                            Timber.i("Gap #${u.id}: qisqaroq variant tanlandi")
                            break
                        }
                    }
                }
                val resampled = StreamResampler.resample(seg.pcm, seg.sampleRate, OUT_SR)
                val pcm = if (config.levelClips) uz.dublaj.util.AudioLevel.level(resampled) else resampled
                placer.place(u.id, u.start, next, pcm)?.let { clips.add(it) }
            }
        } finally {
            synth.close()
        }
        if (clips.isEmpty()) throw IllegalStateException("Ovoz hosil bo'lmadi (matn bo'sh yoki o'qib bo'lmadi)")

        progress(0.62f, "Asl audio tayyorlanmoqda…")
        if (!dir.mixWav.isFile) {
            AudioExtractor.extractToWav(video, dir.mixWav, OUT_SR) { p ->
                progress(0.62f + 0.1f * p, "Asl audio tayyorlanmoqda…")
            }
        }

        val outFile = File(dir.output, "dublaj_${System.currentTimeMillis()}.mp4")
        val totalSamples = (duration * OUT_SR).toLong()
        try {
            val origRanges = active.map { a ->
                Pair((maxOf(0.0, a.start - 0.05) * OUT_SR).toLong(), (a.end * OUT_SR).toLong())
            }
            DubMixer(OUT_SR, WavFile(dir.mixWav), clips, config.originalVolume, config.uzbekVolume, config.duckAmount, origRanges).use { mixer ->
                VideoMuxer.mux(video, outFile, OUT_SR, totalSamples, mixer) { p ->
                    progress(0.72f + 0.27f * p, "Video yig'ilmoqda…")
                }
            }
        } finally {
            clips.forEach { it.file.delete() }
            dir.mixWav.delete()
        }
        // Tarix uchun: metama'lumot va subtitrlar videoning yonida saqlanadi
        try {
            HistoryStore.writeMeta(outFile, title, duration, active.size)
            val finalUnits = if (usedText.isEmpty()) units else units.map { x ->
                val t = usedText[x.id]
                if (t != null) x.copy(uzText = t) else x
            }
            SrtExporter.write(ExportUtils.srtFile(outFile, true), finalUnits, true)
            SrtExporter.write(ExportUtils.srtFile(outFile, false), finalUnits, false)
        } catch (e: Exception) {
            Timber.w(e, "Tarix/SRT yozilmadi")
        }
        val fast = clips.count { it.speed > 1.2f }
        progress(1f, if (fast > 0) "Tayyor ($fast ta gap tezlashtirilgan)" else "Tayyor")
        return outFile
    }

    // ------------------------------------------------------------------ oddiy matn

    /** Videodan ajratilgan asl matn: vaqtlarsiz, bitta uzun matn (gaplar birlashtirilgan). */
    fun plainTranscript(context: Context): String {
        val dir = ensure(context)
        if (!dir.transcriptJson.isFile) return ""
        return try {
            SentenceMerger.merge(loadTranscript(context)).joinToString(" ") { it.text.trim() }.trim()
        } catch (e: Exception) {
            Timber.w(e, "plainTranscript")
            ""
        }
    }

    /** Ixtiyoriy o'zbekcha matnni ovozga aylantirib, WAV fayl qiladi (video vaqtlari yo'q). */
    fun synthesizeText(
        context: Context,
        text: String,
        config: AppConfig,
        onProgress: (Float, String) -> Unit
    ): File {
        val progress = wrap(onProgress)
        val dir = ensure(context)
        if (!VitsSynthesizer.isReady(dir)) {
            throw IllegalStateException("O'zbekcha ovoz komponenti yo'q. Sozlamalarda ovoz fayllarini (.onnx va .json) qo'shing.")
        }
        val pieces = text.split(Regex("\\n+"))
            .flatMap { par -> if (par.isBlank()) emptyList() else VitsSynthesizer.splitText(par, 220) }
            .filter { it.isNotBlank() }
        if (pieces.isEmpty()) throw IllegalStateException("Matn bo'sh")
        FileUtils.ensureSpace(dir.output, 50L * 1024 * 1024)

        progress(0.02f, "Ovoz komponenti yuklanmoqda…")
        val synth = VitsSynthesizer.load(
            dir, TextNormalizer.fromAssets(context.applicationContext),
            noiseScale = config.voiceNoise,
            noiseScaleW = config.voiceNoiseW,
            minLengthScale = config.fitScale,
            pauseS = config.voicePause,
            threads = config.threads,
            baseLengthScale = 1f / config.voiceSpeed.coerceAtLeast(0.3f),
            pitchSemitones = config.voicePitch
        )
        val out = File(dir.output, "ovoz_${System.currentTimeMillis()}.wav")
        try {
            var writer: uz.dublaj.util.WavWriter? = null
            try {
                pieces.forEachIndexed { i, piece ->
                    progress(0.05f + 0.93f * i / pieces.size, "Ovozlashtirish ${i + 1}/${pieces.size}")
                    val seg = synth.synthesize(piece, null)
                    if (seg.pcm.isEmpty()) return@forEachIndexed
                    val pcm = if (config.levelClips) uz.dublaj.util.AudioLevel.level(seg.pcm) else seg.pcm
                    val w = writer ?: uz.dublaj.util.WavWriter(out, seg.sampleRate).also { writer = it }
                    w.write(pcm)
                    w.write(ShortArray((seg.sampleRate * config.voicePause.coerceAtLeast(0.15f)).toInt()))
                }
            } finally {
                writer?.close()
            }
        } catch (e: Throwable) {
            out.delete()
            throw e
        } finally {
            synth.close()
        }
        if (!out.isFile || out.length() < 1000) {
            out.delete()
            throw IllegalStateException("Ovoz hosil bo'lmadi (matn o'qib bo'lmadi)")
        }
        progress(1f, "Tayyor")
        return out
    }
}
