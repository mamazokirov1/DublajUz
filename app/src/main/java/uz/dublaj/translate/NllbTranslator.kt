package uz.dublaj.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import timber.log.Timber
import uz.dublaj.util.WorkDir
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * NLLB-200 distilled 600M (ONNX, quantized) — har qanday qo'llanadigan til → o'zbek (Lotin).
 * Model: Xenova/nllb-200-distilled-600M (encoder + decoder_model_merged, KV-cache bilan).
 * Litsenziya: CC-BY-NC-4.0 (notijorat).
 */
class NllbTranslator private constructor(
    private val env: OrtEnvironment,
    private val opts: OrtSession.SessionOptions,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
    private val tokenizer: NllbTokenizer,
    private val nHeads: Int,
    private val headDim: Int,
    private val eosId: Int,
    private val startId: Int,
    private val targetLangId: Int,
    private val pastNames: List<String>,
    private val hasUseCache: Boolean,
    private val encoderHasMask: Boolean
) {
    private val closed = AtomicBoolean(false)

    fun translate(text: String, srcLang: String): String {
        check(!closed.get()) { "Translator yopilgan" }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""

        val srcLangId = tokenizer.langId(Languages.floresOf(srcLang))
        val out = StringBuilder()
        for (part in splitSentences(trimmed)) {
            if (part.isBlank()) continue
            val uz = translateOne(part, srcLangId)
            if (out.isNotEmpty()) out.append(' ')
            out.append(uz)
        }
        return out.toString().trim()
    }

    private fun longTensor2d(row: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, arrayOf(row))

    private fun emptyPast(): OnnxTensor =
        OnnxTensor.createTensor(
            env, FloatBuffer.allocate(0),
            longArrayOf(1, nHeads.toLong(), 0, headDim.toLong())
        )

    private fun copyTensor(t: OnnxTensor): OnnxTensor {
        val fb = t.floatBuffer.duplicate()
        val arr = FloatArray(fb.remaining())
        fb.get(arr)
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(arr), t.info.shape)
    }

    private fun presentName(pastName: String) = "present." + pastName.removePrefix("past_key_values.")

    private fun translateOne(text: String, srcLangId: Int): String {
        val srcIds = tokenizer.encodeWithLang(text, srcLangId)
        val srcLen = srcIds.size

        // ---- encoder
        val encFeeds = HashMap<String, OnnxTensor>()
        encFeeds["input_ids"] = longTensor2d(srcIds)
        if (encoderHasMask) encFeeds["attention_mask"] = longTensor2d(LongArray(srcLen) { 1L })
        val encOut = try {
            encoder.run(encFeeds)
        } finally {
            encFeeds.values.forEach { it.close() }
        }

        val hidden = encOut.get(0) as OnnxTensor
        val attnMask = longTensor2d(LongArray(srcLen) { 1L })

        val generated = ArrayList<Long>()
        generated.add(startId.toLong())
        generated.add(targetLangId.toLong())

        val encPast = HashMap<String, OnnxTensor>()   // encoder KV (1-qadamda nusxalanadi)
        var lastOut: OrtSession.Result? = null
        val maxNew = min(200, (srcLen * 2.5).toInt() + 10)

        try {
            for (step in 0 until maxNew) {
                val useCache = step > 0
                val owned = ArrayList<OnnxTensor>()
                val feeds = HashMap<String, OnnxTensor>()

                val idsT = longTensor2d(
                    if (useCache) longArrayOf(generated.last()) else generated.toLongArray()
                )
                owned.add(idsT)
                feeds["input_ids"] = idsT
                feeds["encoder_attention_mask"] = attnMask
                feeds["encoder_hidden_states"] = hidden
                if (hasUseCache) {
                    val flag = OnnxTensor.createTensor(env, booleanArrayOf(useCache))
                    owned.add(flag)
                    feeds["use_cache_branch"] = flag
                }
                for (name in pastNames) {
                    feeds[name] = when {
                        !useCache -> emptyPast().also { owned.add(it) }
                        name.contains(".encoder.") -> encPast[name]
                            ?: throw IllegalStateException("Encoder KV yo'q: $name")
                        else -> lastOut!!.get(presentName(name)).get() as OnnxTensor
                    }
                }

                val out = try {
                    decoder.run(feeds)
                } finally {
                    owned.forEach { it.close() }
                }

                val logitsT = out.get("logits").get() as OnnxTensor
                val shape = logitsT.info.shape            // [batch, seq, vocab]
                val vocab = shape[2].toInt()
                val seq = shape[1].toInt()
                val logits = logitsT.floatBuffer
                val base = (seq - 1) * vocab

                val banned = bannedTokens(generated)
                var bestId = -1
                var bestScore = Float.NEGATIVE_INFINITY
                for (i in 0 until vocab) {
                    if (i == tokenizer.padId || i in banned) continue
                    val s = logits.get(base + i)
                    if (s > bestScore) {
                        bestScore = s
                        bestId = i
                    }
                }

                if (step == 0) {
                    for (name in pastNames) {
                        if (name.contains(".encoder.")) {
                            val t = out.get(presentName(name)).get() as OnnxTensor
                            encPast[name] = copyTensor(t)
                        }
                    }
                }
                lastOut?.close()
                lastOut = out

                if (bestId == eosId || bestId < 0) {
                    if (generated.size > 2) break else continue
                }
                generated.add(bestId.toLong())
            }
        } finally {
            lastOut?.close()
            encPast.values.forEach { it.close() }
            attnMask.close()
            encOut.close()
        }

        return tokenizer.decode(generated.drop(2).map { it.toInt() })
    }

    /** no_repeat_ngram_size=3: shu trigramni qaytaradigan tokenlarni taqiqlaydi. */
    private fun bannedTokens(g: List<Long>): Set<Int> {
        val n = g.size
        if (n < 3) return emptySet()
        val a = g[n - 2]
        val b = g[n - 1]
        val out = HashSet<Int>()
        for (j in 0 until n - 2) {
            if (g[j] == a && g[j + 1] == b) out.add(g[j + 2].toInt())
        }
        return out
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                encoder.close()
                decoder.close()
                opts.close()
            } catch (e: Exception) {
                Timber.w(e, "NLLB close")
            }
        }
    }

    companion object {
        val REQUIRED_FILES = listOf(
            "config.json",
            "tokenizer.json",
            "tokenizer_config.json",
            "onnx/encoder_model_quantized.onnx",
            "onnx/decoder_model_merged_quantized.onnx"
        )

        fun modelDir(work: WorkDir): File = File(work.models, "nllb")

        private fun minSize(rel: String) = if (rel.endsWith(".onnx")) 1_000_000L else 100L

        fun missingFiles(work: WorkDir): List<String> {
            val d = modelDir(work)
            return REQUIRED_FILES.filter { !File(d, it).isFile || File(d, it).length() < minSize(it) }
        }

        fun isReady(work: WorkDir): Boolean = missingFiles(work).isEmpty()

        fun load(work: WorkDir, threads: Int = 4): NllbTranslator {
            val d = modelDir(work)
            val miss = missingFiles(work)
            if (miss.isNotEmpty()) {
                throw IllegalStateException(
                    "NLLB fayllari yetishmaydi:\n${miss.joinToString("\n")}\n" +
                        "Sozlamalar → NLLB modelini yuklang yoki papkadan import qiling."
                )
            }

            val cfg = JSONObject(File(d, "config.json").readText())
            val nHeads = cfg.optInt("decoder_attention_heads", 16)
            val hidden = cfg.optInt("d_model", 1024)
            val headDim = hidden / nHeads
            val eosId = cfg.optInt("eos_token_id", 2)
            val startId = cfg.optInt("decoder_start_token_id", 2)

            val tokenizer = NllbTokenizer.fromFile(File(d, "tokenizer.json"))
            val uzId = tokenizer.langId(Languages.TARGET)

            Timber.i("NLLB yuklanmoqda (~1 GB, bir necha soniya)…")
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads.coerceIn(1, 8))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val enc = env.createSession(File(d, "onnx/encoder_model_quantized.onnx").absolutePath, opts)
            val dec = try {
                env.createSession(File(d, "onnx/decoder_model_merged_quantized.onnx").absolutePath, opts)
            } catch (e: Exception) {
                enc.close()
                opts.close()
                throw e
            }

            val decInputs = dec.inputNames
            val pastNames = decInputs.filter { it.startsWith("past_key_values.") }.sorted()
            if (pastNames.isEmpty()) {
                enc.close(); dec.close(); opts.close()
                throw IllegalStateException("Decoder KV-cache kirishlari topilmadi: $decInputs")
            }
            Timber.i("NLLB tayyor (heads=$nHeads headDim=$headDim past=${pastNames.size})")

            return NllbTranslator(
                env, opts, enc, dec, tokenizer, nHeads, headDim, eosId, startId, uzId,
                pastNames, "use_cache_branch" in decInputs, "attention_mask" in enc.inputNames
            )
        }

        private fun splitSentences(text: String): List<String> {
            val parts = text.split(Regex("(?<=[.!?…])\\s+"))
            return if (parts.size <= 1) listOf(text) else parts
        }
    }
}
