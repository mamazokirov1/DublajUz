package uz.dublaj.stt

import android.content.Context
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import dev.ffmpegkit.whisper.WhisperModel
import timber.log.Timber
import uz.dublaj.data.TranscriptUnit
import uz.dublaj.util.WorkDir
import java.io.File

/**
 * Offline STT: prebuilt whisper.cpp AAR (dev.ffmpegkit-maintained:whisper-android).
 * Bepul versiya faqat oddiy (kvantlanmagan) ggml modellarni qo'llaydi va faqat arm64-v8a.
 */
class WhisperRecognizer(private val context: Context) {

    private var model: WhisperModel? = null
    private var loadedModelPath: String? = null

    data class Result(
        val language: String,
        val units: List<TranscriptUnit>,
        val fullText: String
    )

    suspend fun ensureModel(modelFile: File) {
        if (!modelFile.isFile) {
            throw IllegalStateException("Matn aniqlash komponenti topilmadi")
        }
        val path = modelFile.absolutePath
        if (model != null && loadedModelPath == path) return

        release()
        Timber.i("Whisper model yuklanmoqda: $path (${modelFile.length() / 1024 / 1024} MB)")
        model = Whisper.loadModel(context, path)
        loadedModelPath = path
    }

    /**
     * @param language Whisper kodi ("en", "ru", ...) yoki "auto".
     * Kutubxona aniqlangan tilni qaytarmaydi, shuning uchun "auto" da faqat ru/en farqlanadi
     * (kirill → ru, aks holda en). Boshqa tillar uchun Sozlamalarda tilni tanlang.
     */
    suspend fun transcribe(
        audioFile: File,
        language: String = "auto",
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result {
        val m = model ?: throw IllegalStateException("Model yuklanmagan — ensureModel() chaqiring")
        if (!audioFile.isFile) throw IllegalArgumentException("Audio yo'q: $audioFile")

        val langCode = language.lowercase().ifBlank { "auto" }
        onProgress(0.2f, "STT ishlamoqda (bu biroz vaqt olishi mumkin)…")
        val result = Whisper.transcribe(m, audioFile.absolutePath, WhisperConfig(language = langCode))

        onProgress(0.9f, "Segmentlar tahlil…")
        val detected = if (langCode == "auto") detectLangHint(result.text) else langCode
        val units = cleanUnits(
            result.segments.map { seg ->
                TranscriptUnit(
                    id = 0,
                    start = seg.startMs / 1000.0,
                    end = seg.endMs / 1000.0,
                    text = seg.text.trim(),
                    language = detected
                )
            }
        )

        onProgress(1f, "Tayyor: ${units.size} segment")
        return Result(detected, units, result.text.trim())
    }

    fun release() {
        model?.let { m ->
            try {
                Whisper.releaseModel(m)
            } catch (e: Exception) {
                Timber.w(e, "releaseModel")
            }
        }
        model = null
        loadedModelPath = null
    }


    /**
     * Whisper chiqishini tozalash (Python speech_recognizer porti):
     * nutq bo'lmagan belgilar ([Music], (kulgi), ♪), bir xil gapning 3+ marta takrori (gallyutsinatsiya),
     * ustma-ust vaqtlarni tuzatish va juda qisqa bo'laklarni olib tashlash.
     */
    private fun cleanUnits(raw: List<TranscriptUnit>): List<TranscriptUnit> {
        val out = ArrayList<TranscriptUnit>()
        var prevEnd = 0.0
        for (u in raw.sortedBy { it.start }) {
            val text = u.text.replace(Regex("\\s+"), " ").trim()
            if (text.none { it.isLetterOrDigit() } || NON_SPEECH.matches(text)) continue
            val n = out.size
            if (n >= 2 && out[n - 1].text.equals(text, true) && out[n - 2].text.equals(text, true)) continue
            val start = maxOf(u.start, prevEnd)
            if (u.end - start < 0.05) continue
            out.add(u.copy(start = start, text = text))
            prevEnd = u.end
        }
        return out.mapIndexed { i, u -> u.copy(id = i + 1) }
    }

    private fun detectLangHint(text: String): String {
        val cyr = text.count { it in '\u0400'..'\u04FF' }
        return if (cyr > text.length / 10) "ru" else "en"
    }

    companion object {
        private val NON_SPEECH = Regex("^\\s*(\\[[^\\]]*\\]|\\([^)]*\\)|\\*[^*]*\\*|♪[^♪]*♪?)\\s*$")

        /** Faqat bepul AAR qo'llaydigan (kvantlanmagan) modellar. */
        val MODEL_OPTIONS = listOf(
            ModelInfo("tiny", "ggml-tiny.bin", 75, "Eng tez ishlaydi, lekin aniqligi pastroq"),
            ModelInfo("base", "ggml-base.bin", 142, "Tavsiya etiladi: tez va yetarlicha aniq"),
            ModelInfo("small", "ggml-small.bin", 466, "Eng aniq natija, lekin sekinroq ishlaydi")
        )

        data class ModelInfo(
            val id: String,
            val fileName: String,
            val sizeMb: Int,
            val note: String
        ) {
            /** Yuklangan fayl kamida shu hajmda bo'lishi kerak (to'liq yuklanganini tekshirish). */
            val minBytes: Long get() = (sizeMb * 0.85 * 1024 * 1024).toLong()
        }

        fun info(modelId: String): ModelInfo =
            MODEL_OPTIONS.find { it.id == modelId } ?: MODEL_OPTIONS[1]

        fun defaultModelFile(work: WorkDir, modelId: String = "base"): File =
            File(work.models, info(modelId).fileName)
    }
}
