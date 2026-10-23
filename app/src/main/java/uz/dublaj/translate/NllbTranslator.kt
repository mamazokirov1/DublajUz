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
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
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

    /** Tarjima nomzodi: matn va o'rtacha log-ehtimol (uzunlik bo'yicha normallangan). */
    data class Candidate(val text: String, val score: Double)

    fun translate(text: String, srcLang: String): String =
        translateNBest(text, srcLang, 1).firstOrNull()?.text ?: ""

    /**
     * Beam search (BEAMS ta nur) bilan eng yaxshi [nBest] ta tarjima. Beam ishlamasa — greedy'ga qaytadi.
     * Natija[0] — eng yaxshisi.
     */
    fun translateNBest(text: String, srcLang: String, nBest: Int = N_BEST): List<Candidate> {
        check(!closed.get()) { "Translator yopilgan" }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        val srcLangId = tokenizer.langId(Languages.floresOf(srcLang))
        val perPart = ArrayList<List<Candidate>>()
        for (part in splitSentences(trimmed)) {
            if (part.isBlank()) continue
            val c: List<Candidate> = try {
                beamSearch(part, srcLangId, BEAMS, nBest)
            } catch (e: Exception) {
                Timber.w(e, "Beam search ishlamadi, greedy ishlatiladi")
                emptyList()
            }
            perPart.add(if (c.isNotEmpty()) c else listOf(Candidate(translateOne(part, srcLangId), 0.0)))
        }
        if (perPart.isEmpty()) return emptyList()

        val out = ArrayList<Candidate>()
        for (k in 0 until nBest) {
            val sb = StringBuilder()
            var score = 0.0
            var any = (k == 0)
            for (p in perPart) {
                val c = p.getOrNull(k)
                if (c != null) any = true
                val pick = c ?: p[0]
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(pick.text)
                score += pick.score
            }
            if (!any) break
            val t = sb.toString().trim()
            if (t.isNotEmpty() && out.none { it.text == t }) out.add(Candidate(t, score))
        }
        return out
    }

    private fun longTensor2d(row: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, arrayOf(row))

    private fun emptyPast(batch: Int = 1): OnnxTensor =
        OnnxTensor.createTensor(
            env, FloatBuffer.allocate(0),
            longArrayOf(batch.toLong(), nHeads.toLong(), 0, headDim.toLong())
        )

    /** [t] (batch birinchi o'lchamda) qatorlarini [src] tartibida qayta yig'adi (beam almashinuvi). */
    private fun gatherRows(t: OnnxTensor, src: IntArray): OnnxTensor {
        val shape = t.info.shape
        val fb = t.floatBuffer.duplicate()
        val arr = FloatArray(fb.remaining())
        fb.get(arr)
        val rows = shape[0].toInt()
        val block = arr.size / rows
        var identity = true
        for (i in src.indices) if (src[i] != i) identity = false
        val res = if (identity) {
            arr
        } else {
            val r = FloatArray(arr.size)
            for (i in src.indices) System.arraycopy(arr, src[i] * block, r, i * block, block)
            r
        }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(res), shape)
    }

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
        val maxNew = min(400, (srcLen * 3.0).toInt() + 20)

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


    /** Beam search (KV-cache, batch = beams). Qaytaradi: eng yaxshi nomzodlar (kamayish tartibida). */
    private fun beamSearch(text: String, srcLangId: Int, beams: Int, nBest: Int): List<Candidate> {
        val srcIds = tokenizer.encodeWithLang(text, srcLangId)
        val srcLen = srcIds.size
        val bw = beams
        val kTop = 2 * bw

        // ---- encoder (bir marta)
        val encFeeds = HashMap<String, OnnxTensor>()
        encFeeds["input_ids"] = longTensor2d(srcIds)
        if (encoderHasMask) encFeeds["attention_mask"] = longTensor2d(LongArray(srcLen) { 1L })
        val encOut = try {
            encoder.run(encFeeds)
        } finally {
            encFeeds.values.forEach { it.close() }
        }

        var hiddenT: OnnxTensor? = null
        var maskT: OnnxTensor? = null
        val encPast = HashMap<String, OnnxTensor>()
        var lastOut: OrtSession.Result? = null
        val finished = ArrayList<Pair<List<Long>, Double>>()

        try {
            val h1 = encOut.get(0) as OnnxTensor
            val dModel = h1.info.shape[2].toInt()
            val fb = h1.floatBuffer.duplicate()
            val one = FloatArray(fb.remaining())
            fb.get(one)
            val expanded = FloatArray(one.size * bw)
            for (b in 0 until bw) System.arraycopy(one, 0, expanded, b * one.size, one.size)
            val hid = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(expanded), longArrayOf(bw.toLong(), srcLen.toLong(), dModel.toLong())
            )
            hiddenT = hid
            val msk = OnnxTensor.createTensor(env, Array(bw) { LongArray(srcLen) { 1L } })
            maskT = msk

            var alive = ArrayList<ArrayList<Long>>()
            for (b in 0 until bw) alive.add(arrayListOf(startId.toLong(), targetLangId.toLong()))
            var aliveScores = DoubleArray(bw)
            var srcRow = IntArray(bw) { it }
            val maxNew = min(400, (srcLen * 3.0).toInt() + 20)

            for (step in 0 until maxNew) {
                val useCache = step > 0
                val owned = ArrayList<OnnxTensor>()
                val feeds = HashMap<String, OnnxTensor>()

                val ids2d: Array<LongArray> =
                    if (useCache) Array(bw) { longArrayOf(alive[it].last()) }
                    else Array(bw) { alive[it].toLongArray() }
                val idsT = OnnxTensor.createTensor(env, ids2d)
                owned.add(idsT)
                feeds["input_ids"] = idsT
                feeds["encoder_attention_mask"] = msk
                feeds["encoder_hidden_states"] = hid
                if (hasUseCache) {
                    val flag = OnnxTensor.createTensor(env, booleanArrayOf(useCache))
                    owned.add(flag)
                    feeds["use_cache_branch"] = flag
                }
                for (name in pastNames) {
                    feeds[name] = when {
                        !useCache -> emptyPast(bw).also { owned.add(it) }
                        name.contains(".encoder.") -> encPast[name]
                            ?: throw IllegalStateException("Encoder KV yo'q: $name")
                        else -> gatherRows(lastOut!!.get(presentName(name)).get() as OnnxTensor, srcRow)
                            .also { owned.add(it) }
                    }
                }

                val out = try {
                    decoder.run(feeds)
                } finally {
                    owned.forEach { it.close() }
                }

                val logitsT = out.get("logits").get() as OnnxTensor
                val shape = logitsT.info.shape
                val seq = shape[1].toInt()
                val vocab = shape[2].toInt()
                val logits = logitsT.floatBuffer

                // ---- nomzodlar: har nurdan kTop ta eng yaxshi token
                val candBeam = IntArray(bw * kTop)
                val candTok = IntArray(bw * kTop)
                val candScore = DoubleArray(bw * kTop)
                var nc = 0
                val rows = if (step == 0) 1 else bw
                for (b in 0 until rows) {
                    if (aliveScores[b] < -1e8) continue
                    val base = (b * seq + seq - 1) * vocab
                    var mx = Float.NEGATIVE_INFINITY
                    for (i in 0 until vocab) {
                        val v = logits.get(base + i)
                        if (v > mx) mx = v
                    }
                    var sum = 0.0
                    val cut = mx - 18f
                    for (i in 0 until vocab) {
                        val v = logits.get(base + i)
                        if (v > cut) sum += exp((v - mx).toDouble())
                    }
                    val lse = mx.toDouble() + ln(sum)
                    val banned = bannedTokens(alive[b])
                    val topTok = IntArray(kTop) { -1 }
                    val topVal = DoubleArray(kTop) { Double.NEGATIVE_INFINITY }
                    for (i in 0 until vocab) {
                        if (i == tokenizer.padId) continue
                        if (banned.isNotEmpty() && i in banned) continue
                        val lp = logits.get(base + i).toDouble() - lse
                        if (lp > topVal[kTop - 1]) {
                            var p = kTop - 1
                            while (p > 0 && lp > topVal[p - 1]) {
                                topVal[p] = topVal[p - 1]
                                topTok[p] = topTok[p - 1]
                                p--
                            }
                            topVal[p] = lp
                            topTok[p] = i
                        }
                    }
                    for (k in 0 until kTop) {
                        if (topTok[k] < 0) continue
                        candBeam[nc] = b
                        candTok[nc] = topTok[k]
                        candScore[nc] = aliveScores[b] + topVal[k]
                        nc++
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

                val order = (0 until nc).sortedByDescending { candScore[it] }
                val nextAlive = ArrayList<ArrayList<Long>>()
                val nextScores = ArrayList<Double>()
                val nextSrc = ArrayList<Int>()
                var rank = 0
                for (ci in order) {
                    val tok = candTok[ci]
                    val b = candBeam[ci]
                    val sc = candScore[ci]
                    if (tok == eosId) {
                        if (rank < bw && alive[b].size > 2) {
                            val gen = ArrayList(alive[b].subList(2, alive[b].size))
                            finished.add(Pair(gen, sc / max(1, gen.size).toDouble()))
                        }
                    } else if (nextAlive.size < bw) {
                        val t = ArrayList(alive[b])
                        t.add(tok.toLong())
                        nextAlive.add(t)
                        nextScores.add(sc)
                        nextSrc.add(b)
                    }
                    rank++
                    if (nextAlive.size >= bw) break
                }
                if (nextAlive.isEmpty()) break
                while (nextAlive.size < bw) {
                    nextAlive.add(ArrayList(nextAlive[0]))
                    nextScores.add(-1e9)
                    nextSrc.add(nextSrc[0])
                }
                alive = nextAlive
                aliveScores = DoubleArray(bw) { nextScores[it] }
                srcRow = IntArray(bw) { nextSrc[it] }

                if (finished.size >= bw) break
            }

            if (finished.size < bw) {
                for (b in 0 until bw) {
                    if (aliveScores[b] < -1e8 || alive[b].size <= 2) continue
                    val gen = ArrayList(alive[b].subList(2, alive[b].size))
                    finished.add(Pair(gen, aliveScores[b] / max(1, gen.size).toDouble()))
                }
            }
        } finally {
            lastOut?.close()
            encPast.values.forEach { it.close() }
            hiddenT?.close()
            maskT?.close()
            encOut.close()
        }

        val result = ArrayList<Candidate>()
        for ((gen, sc) in finished.sortedByDescending { it.second }) {
            val t = tokenizer.decode(gen.map { it.toInt() })
            if (t.isNotBlank() && result.none { it.text == t }) result.add(Candidate(t, sc))
            if (result.size >= nBest) break
        }
        return result
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
                    "Tarjima komponenti fayllari yetishmaydi:\n${miss.joinToString("\n")}\n" +
                        "Sozlamalar bo'limida tarjima komponentini yuklab oling."
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

        private const val BEAMS = 4
        private const val N_BEST = 3

        private fun splitSentences(text: String): List<String> {
            val parts = text.split(Regex("(?<=[.!?…])\\s+")).filter { it.isNotBlank() }
            val out = ArrayList<String>()
            for (p in parts) {
                if (p.length <= 260) out.add(p) else out.addAll(splitLong(p))
            }
            return if (out.isEmpty()) listOf(text) else out
        }

        /** Juda uzun gapni vergul/nuqtali vergul/ikki nuqta bo'yicha ~200 belgili bo'laklarga bo'ladi. */
        private fun splitLong(p: String): List<String> {
            val pieces = p.split(Regex("(?<=[,;:])\\s+"))
            val out = ArrayList<String>()
            var cur = StringBuilder()
            for (piece in pieces) {
                if (cur.isNotEmpty() && cur.length + piece.length + 1 > 200) {
                    out.add(cur.toString())
                    cur = StringBuilder()
                }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(piece)
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
            return out
        }
    }
}
