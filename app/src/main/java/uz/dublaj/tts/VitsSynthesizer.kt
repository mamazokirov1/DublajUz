package uz.dublaj.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import timber.log.Timber
import uz.dublaj.pipeline.PitchShift
import uz.dublaj.text.TextNormalizer
import uz.dublaj.util.WorkDir
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * O'zbek VITS TTS (ONNX) — Python `speech_synthesizer.py` / `speak.py` porti.
 *
 * Kutiladigan fayllar (Python loyiha bilan bir xil):
 *   models/vits_uzbek.onnx
 *   models/uz_tts_base.json
 *
 * Telefoningizda allaqachon bor bo'lsa:
 *   adb push models/vits_uzbek.onnx /sdcard/Android/data/uz.dublaj/files/dublaj/models/
 *   yoki Sozlamalardan nusxa yo'lini ko'rsating.
 */
class VitsSynthesizer(
    private val session: OrtSession,
    private val env: OrtEnvironment,
    private val sampleRate: Int,
    private val addBlank: Boolean,
    private var noiseScale: Float,
    private var noiseScaleW: Float,
    private var minLengthScale: Float,
    private val maxChars: Int,
    private var pauseS: Float,
    private val normalizer: TextNormalizer
) {
    private val closed = AtomicBoolean(false)

    /** length_scale boshlang'ich qiymati (1 / nutq tezligi). */
    @Volatile private var baseScale = 1.0f

    /** Ovoz tembri (yarim ton). */
    @Volatile var pitchSemitones = 0f

    @Volatile private var userPron: List<Pair<Regex, String>> = emptyList()

    /** Talaffuz lug'ati: har qatorda "so'z = qanday o'qilsin". */
    fun setUserPronunciations(raw: String) {
        val list = ArrayList<Pair<Regex, String>>()
        for (line in raw.lines()) {
            val parts = line.trim().split(Regex("\\s*(=>|->|=)\\s*"), limit = 2)
            if (parts.size != 2) continue
            val key = parts[0].trim()
            val value = parts[1].trim()
            if (key.isEmpty() || value.isEmpty()) continue
            list.add(
                Pair(
                    Regex("(?<![\\p{L}'])" + Regex.escape(key) + "(?![\\p{L}'])", RegexOption.IGNORE_CASE),
                    value
                )
            )
        }
        userPron = list
    }

    private fun applyUserPron(text: String): String {
        var t = text
        for ((re, v) in userPron) t = t.replace(re) { v }
        return t
    }

    /** Chunk boshi/oxiridagi jimlikni kesadi (VITS ~0.1–0.3 s bo'sh joy qoldiradi): vaqt hisobi aniqroq bo'ladi. */
    private fun trimSilence(a: ShortArray): ShortArray {
        if (a.size < sampleRate / 10) return a
        var peak = 0
        for (v in a) {
            val x = if (v < 0) -v.toInt() else v.toInt()
            if (x > peak) peak = x
        }
        if (peak < 300) return a
        val thr = maxOf(120, (peak * 0.012f).toInt())
        val win = maxOf(1, sampleRate / 200) // 5 ms
        fun loud(start: Int): Boolean {
            val end = minOf(a.size, start + win)
            if (end <= start) return false
            var s = 0L
            for (i in start until end) s += if (a[i] < 0) -a[i].toInt() else a[i].toInt()
            return s / (end - start) > thr
        }
        var first = 0
        while (first < a.size && !loud(first)) first += win
        var last = a.size
        while (last > 0 && !loud(maxOf(0, last - win))) last -= win
        if (first >= last) return a
        val from = maxOf(0, first - sampleRate * 20 / 1000)
        val to = minOf(a.size, last + sampleRate * 50 / 1000)
        return a.copyOfRange(from, to)
    }

    /** Modelni qayta yuklamasdan sintez parametrlarini almashtirish (tinglab ko'rish uchun). */
    fun configure(noise: Float, noiseW: Float, baseLength: Float, minLength: Float, pause: Float, pitch: Float = 0f) {
        pitchSemitones = pitch
        noiseScale = noise
        noiseScaleW = noiseW
        baseScale = baseLength
        minLengthScale = minLength
        pauseS = pause
    }

    data class SegmentAudio(
        val pcm: ShortArray,
        val sampleRate: Int,
        val lengthScale: Float
    ) {
        val durationSec: Double get() = pcm.size.toDouble() / sampleRate
    }

    fun synthesize(text: String, slotSec: Double? = null): SegmentAudio {
        check(!closed.get())
        var prepared = normalizer.normalize(applyUserPron(text))
        prepared = prepared.replace(Regex("\\s*[–—―]\\s*|\\s+-\\s+"), ", ")
        if (prepared.isBlank()) {
            return SegmentAudio(ShortArray(0), sampleRate, 1f)
        }

        var scale = baseScale
        var audio = synthInternal(prepared, scale)

        if (slotSec != null && slotSec > 0 && minLengthScale < scale) {
            val dur = audio.size.toDouble() / sampleRate
            if (dur > slotSec) {
                scale = maxOf(minLengthScale, (scale * (slotSec / dur) * 0.97).toFloat())
                audio = synthInternal(prepared, scale)
            }
        }
        audio = fade(PitchShift.shift(audio, sampleRate, pitchSemitones))
        return SegmentAudio(audio, sampleRate, scale)
    }

    /** Boshi va oxiridagi "chiq" ovozini yo'qotish uchun 6 ms yumshoq kirish/chiqish. */
    private fun fade(a: ShortArray): ShortArray {
        val n = minOf(a.size / 2, (sampleRate * 0.006).toInt())
        for (i in 0 until n) {
            val g = i.toFloat() / n
            a[i] = (a[i] * g).toInt().toShort()
            a[a.size - 1 - i] = (a[a.size - 1 - i] * g).toInt().toShort()
        }
        return a
    }

    private fun synthInternal(text: String, lengthScale: Float): ShortArray {
        val chunks = splitText(text, maxChars)
        val parts = mutableListOf<ShortArray>()
        val pause = ShortArray((sampleRate * pauseS).toInt())
        val pauseShort = ShortArray((sampleRate * pauseS * 0.5f).toInt())

        chunks.forEachIndexed { i, chunk ->
            val ids = textToIds(chunk, addBlank)
            if (ids.isEmpty()) return@forEachIndexed

            val x = Array(1) { ids }
            val xLen = longArrayOf(ids.size.toLong())

            val inputs = mapOf(
                "x" to OnnxTensor.createTensor(env, x),
                "x_lengths" to OnnxTensor.createTensor(env, xLen),
                "noise_scale" to OnnxTensor.createTensor(env, floatArrayOf(noiseScale)),
                "noise_scale_w" to OnnxTensor.createTensor(env, floatArrayOf(noiseScaleW)),
                "length_scale" to OnnxTensor.createTensor(env, floatArrayOf(lengthScale))
            )
            try {
                session.run(inputs).use { result ->
                    val tensor = result[0] as OnnxTensor
                    val buf = tensor.floatBuffer.duplicate()
                    val floats = FloatArray(buf.remaining())
                    buf.get(floats)
                    val pcm = ShortArray(floats.size) { i ->
                        (floats[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    }
                    parts += trimSilence(pcm)
                }
            } finally {
                inputs.values.forEach { it.close() }
            }
            if (i < chunks.lastIndex) {
                val endCh = chunk.trimEnd().lastOrNull()
                val sentenceEnd = endCh != null && ".!?…".indexOf(endCh) >= 0
                parts += if (sentenceEnd) pause else pauseShort
            }
        }

        if (parts.isEmpty()) return ShortArray(0)
        val total = parts.sumOf { it.size }
        val out = ShortArray(total)
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, pos, p.size)
            pos += p.size
        }
        return out
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                session.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        // text/symbols.py bilan bir xil
        private val SYMBOLS: List<Char> = buildList {
            add('_')
            " ;:,.!?¡¿—…\"«»“” ".forEach { add(it) }
            ('a'..'z').forEach { add(it) }
            ('A'..'Z').forEach { add(it) }
            add('\'')
        }
        private val SYMBOL_TO_ID: Map<Char, Int> =
            SYMBOLS.withIndex().associate { (i, c) -> c to i }

        fun modelFiles(work: WorkDir): Pair<File, File> =
            File(work.models, "vits_uzbek.onnx") to File(work.models, "uz_tts_base.json")

        fun isReady(work: WorkDir): Boolean {
            val (m, c) = modelFiles(work)
            return m.isFile && m.length() > 100_000 && c.isFile
        }

        fun load(
            work: WorkDir,
            normalizer: TextNormalizer,
            noiseScale: Float = 0.667f,
            noiseScaleW: Float = 0.4f,
            minLengthScale: Float = 0.8f,
            maxChars: Int = 220,
            pauseS: Float = 0.25f,
            threads: Int = 4,
            baseLengthScale: Float = 1.0f,
            pitchSemitones: Float = 0f,
            userPron: String = ""
        ): VitsSynthesizer {
            val (model, configFile) = modelFiles(work)
            if (!isReady(work)) {
                throw IllegalStateException(
                    "VITS model topilmadi.\n" +
                        "Kutiladi:\n  ${model.absolutePath}\n  ${configFile.absolutePath}\n" +
                        "Python loyihadagi models/ dan nusxa ko'chiring."
                )
            }
            val cfg = JSONObject(configFile.readText())
            val data = cfg.getJSONObject("data")
            val sr = data.getInt("sampling_rate")
            val addBlank = data.optBoolean("add_blank", true)

            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads.coerceIn(1, 8))
            }
            Timber.i("VITS yuklanmoqda: ${model.name} (${model.length() / 1024 / 1024} MB)")
            val session = env.createSession(model.absolutePath, opts)
            val names = session.inputNames
            val required = setOf("x", "x_lengths", "noise_scale", "noise_scale_w", "length_scale")
            if (!names.containsAll(required)) {
                throw IllegalStateException("VITS kirishlari mos emas: $names")
            }
            Timber.i("VITS tayyor, sr=$sr")
            return VitsSynthesizer(
                session, env, sr, addBlank, noiseScale, noiseScaleW,
                minLengthScale, maxChars, pauseS, normalizer
            ).also {
                it.baseScale = baseLengthScale
                it.pitchSemitones = pitchSemitones
                it.setUserPronunciations(userPron)
            }
        }

        private fun cleanUzbek(text: String): String {
            var t = text
            mapOf(
                'ʻ' to '\'', 'ʼ' to '\'', '‘' to '\'', '’' to '\'',
                '`' to '\'', '–' to '-', '—' to '-', '−' to '-'
            ).forEach { (a, b) -> t = t.replace(a, b) }
            return t.lowercase().replace(Regex("\\s+"), " ").trim()
        }

        fun textToIds(text: String, addBlank: Boolean): LongArray {
            val cleaned = cleanUzbek(text)
            val ids = mutableListOf<Int>()
            for (ch in cleaned) {
                val id = SYMBOL_TO_ID[ch]
                if (id != null) ids += id
            }
            if (ids.isEmpty()) return longArrayOf()
            if (addBlank) {
                // intersperse blanks (0)
                val result = IntArray(ids.size * 2 + 1)
                for (i in ids.indices) result[i * 2 + 1] = ids[i]
                return LongArray(result.size) { result[it].toLong() }
            }
            return LongArray(ids.size) { ids[it].toLong() }
        }

        fun splitText(text: String, maxChars: Int): List<String> {
            val t = text.replace(Regex("\\s+"), " ").trim()
            if (t.length <= maxChars) return listOf(t)
            val sentences = t.split(Regex("(?<=[.!?])\\s+"))
            val chunks = mutableListOf<String>()
            var cur = ""
            for (s in sentences) {
                var sentence = s
                while (sentence.length > maxChars) {
                    var cut = -1
                    val window = sentence.substring(0, minOf(sentence.length, maxChars))
                    val clause = Regex("[,;:]\\s").findAll(window).lastOrNull()
                    if (clause != null && clause.range.first >= maxChars * 4 / 10) cut = clause.range.first + 1
                    if (cut <= 0) {
                        cut = sentence.lastIndexOf(' ', maxChars)
                        if (cut <= 0) cut = maxChars
                    }
                    val piece = sentence.substring(0, cut).trim()
                    if (piece.isNotEmpty()) {
                        if (cur.isNotEmpty()) {
                            chunks += cur
                            cur = ""
                        }
                        chunks += piece
                    }
                    sentence = sentence.substring(cut).trim()
                }
                if (sentence.isEmpty()) continue
                if (cur.isNotEmpty() && cur.length + sentence.length + 1 > maxChars) {
                    chunks += cur
                    cur = sentence
                } else {
                    cur = if (cur.isEmpty()) sentence else "$cur $sentence"
                }
            }
            if (cur.isNotEmpty()) chunks += cur
            return chunks.ifEmpty { listOf(t) }
        }
    }
}
