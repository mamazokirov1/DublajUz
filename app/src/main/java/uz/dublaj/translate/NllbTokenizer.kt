package uz.dublaj.translate

import android.util.JsonReader
import android.util.JsonToken
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.text.Normalizer
import kotlin.math.min

/**
 * NLLB tokenizeri. tokenizer.json ning ikkala formatini qo'llaydi:
 *  - BPE     : "vocab" = {piece: id}, "merges" = ["a b", ...]  (Xenova/nllb-200-distilled-600M shunday)
 *  - Unigram : "vocab" = [[piece, score], ...]                  (Viterbi bo'yicha)
 * (SentencePiece'ning nmt_nfkc normalizatsiyasi taxminan NFKC bilan almashtirilgan.)
 */
class NllbTokenizer private constructor(
    private val pieces: Array<String>,
    private val piece2id: HashMap<String, Int>,
    private val scores: DoubleArray?,                 // faqat Unigram
    private val mergeRank: HashMap<String, Int>?,     // faqat BPE
    private val special: Map<String, Int>,
    private val specialIds: Set<Int>,
    private val unkId: Int,
    private val maxPieceLen: Int
) {
    val eosId: Int = special["</s>"] ?: 2
    val padId: Int = special["<pad>"] ?: 1

    fun langId(code: String): Int =
        special[code] ?: piece2id[code]
            ?: throw IllegalStateException("Til tokeni topilmadi: $code")

    /** [src_lang] + tokenlar + </s> */
    fun encodeWithLang(text: String, srcLangId: Int): LongArray {
        val ids = ArrayList<Long>()
        ids.add(srcLangId.toLong())
        for (id in tokenize(text)) ids.add(id.toLong())
        ids.add(eosId.toLong())
        return ids.toLongArray()
    }

    fun tokenize(text: String): List<Int> {
        val norm = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), " ").trim()
        if (norm.isEmpty()) return emptyList()
        return if (mergeRank != null) bpeTokenize(norm, mergeRank) else unigramTokenize(norm)
    }

    // ---------------- BPE ----------------
    private fun bpeTokenize(norm: String, ranks: HashMap<String, Int>): List<Int> {
        val out = ArrayList<Int>()
        for (word in norm.split(' ')) {
            if (word.isEmpty()) continue
            val w = "▁$word"
            val syms = ArrayList<String>(w.length)
            var i = 0
            while (i < w.length) {
                val len = Character.charCount(w.codePointAt(i))
                syms.add(w.substring(i, i + len))
                i += len
            }
            while (syms.size > 1) {
                var bestRank = Int.MAX_VALUE
                var bestIdx = -1
                for (k in 0 until syms.size - 1) {
                    val r = ranks[syms[k] + " " + syms[k + 1]] ?: continue
                    if (r < bestRank) {
                        bestRank = r
                        bestIdx = k
                    }
                }
                if (bestIdx < 0) break
                syms[bestIdx] = syms[bestIdx] + syms[bestIdx + 1]
                syms.removeAt(bestIdx + 1)
            }
            var lastUnk = false
            for (s in syms) {
                val id = piece2id[s]
                if (id == null) {
                    if (!lastUnk) out.add(unkId)      // fuse_unk
                    lastUnk = true
                } else {
                    out.add(id)
                    lastUnk = false
                }
            }
        }
        return out
    }

    // ---------------- Unigram ----------------
    private fun unigramTokenize(norm: String): List<Int> {
        val sc = scores ?: return emptyList()
        val s = "▁" + norm.replace(' ', '▁')
        val n = s.length

        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val prevIdx = IntArray(n + 1)
        val prevId = IntArray(n + 1)
        best[0] = 0.0

        for (i in 0 until n) {
            if (best[i] == Double.NEGATIVE_INFINITY) continue
            val maxLen = min(maxPieceLen, n - i)
            for (l in 1..maxLen) {
                val id = piece2id[s.substring(i, i + l)] ?: continue
                val v = best[i] + sc[id]
                if (v > best[i + l]) {
                    best[i + l] = v
                    prevIdx[i + l] = i
                    prevId[i + l] = id
                }
            }
            if (best[i + 1] == Double.NEGATIVE_INFINITY) {
                best[i + 1] = best[i] - 10.0
                prevIdx[i + 1] = i
                prevId[i + 1] = unkId
            }
        }

        val out = ArrayList<Int>()
        var pos = n
        while (pos > 0) {
            out.add(prevId[pos])
            pos = prevIdx[pos]
        }
        out.reverse()
        return out
    }

    fun decode(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id in specialIds) continue
            val p = pieces.getOrNull(id) ?: continue
            sb.append(p)
        }
        return sb.toString().replace('▁', ' ').replace(Regex("\\s+"), " ").trim()
    }

    // ---------------- Yuklash (oqimli JSON — 17 MB faylni xotirada ushlamaslik uchun) ----------------
    private class Parsed {
        var type: String = ""
        var unkId: Int = -1
        var unkToken: String? = null
        var vocabList: ArrayList<String>? = null
        var scoreList: ArrayList<Double>? = null
        var vocabMap: HashMap<String, Int>? = null
        var merges: HashMap<String, Int>? = null
        val added = ArrayList<Triple<String, Int, Boolean>>()
    }

    companion object {
        fun fromFile(tokenizerJson: File): NllbTokenizer {
            val p = Parsed()
            JsonReader(
                BufferedReader(InputStreamReader(FileInputStream(tokenizerJson), Charsets.UTF_8), 1 shl 16)
            ).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "added_tokens" -> readAdded(r, p)
                        "model" -> readModel(r, p)
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            }

            val special = HashMap<String, Int>()
            val specialIds = HashSet<Int>()
            var maxId = -1
            for ((content, id, isSpecial) in p.added) {
                special[content] = id
                if (isSpecial) specialIds.add(id)
                if (id > maxId) maxId = id
            }

            val pieces: Array<String>
            val piece2id: HashMap<String, Int>
            var scores: DoubleArray? = null
            var ranks: HashMap<String, Int>? = null
            var maxLen = 1

            val vm = p.vocabMap
            val vl = p.vocabList
            if (vm != null) {
                // BPE
                val m = p.merges
                if (m == null || m.isEmpty()) {
                    throw IllegalStateException("tokenizer.json: BPE 'merges' topilmadi (type=${p.type})")
                }
                piece2id = vm
                for ((_, id) in vm) if (id > maxId) maxId = id
                pieces = Array(maxId + 1) { "" }
                for ((tok, id) in vm) pieces[id] = tok
                for ((content, id) in special) if (pieces[id].isEmpty()) pieces[id] = content
                ranks = m
            } else if (vl != null) {
                // Unigram
                val n = vl.size
                piece2id = HashMap(n * 2)
                scores = DoubleArray(n) { p.scoreList!![it] }
                pieces = Array(maxOf(n, maxId + 1)) { "" }
                for (i in 0 until n) {
                    val t = vl[i]
                    pieces[i] = t
                    if (!piece2id.containsKey(t)) piece2id[t] = i
                    if (t.length > maxLen) maxLen = t.length
                }
            } else {
                throw IllegalStateException("tokenizer.json: model.vocab topilmadi (type=${p.type})")
            }

            // Asosiy maxsus tokenlar (0..3) har doim chiqarishdan o'chiriladi
            for (name in listOf("<s>", "<pad>", "</s>", "<unk>")) {
                (special[name] ?: piece2id[name])?.let { specialIds.add(it) }
            }
            val unk = (p.unkToken?.let { special[it] ?: piece2id[it] })
                ?: p.unkId.takeIf { it >= 0 }
                ?: special["<unk>"] ?: piece2id["<unk>"] ?: 3

            Timber.i(
                "Tokenizer type=${p.type} (${if (ranks != null) "BPE" else "Unigram"}): " +
                    "${piece2id.size} pieces, ${special.size} added, merges=${ranks?.size ?: 0}"
            )
            return NllbTokenizer(
                pieces, piece2id, scores, ranks, special, specialIds, unk, min(maxLen, 40)
            )
        }

        private fun readAdded(r: JsonReader, p: Parsed) {
            r.beginArray()
            while (r.hasNext()) {
                var content = ""
                var id = -1
                var isSpecial = true
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "content" -> content = r.nextString()
                        "id" -> id = r.nextInt()
                        "special" -> isSpecial = r.nextBoolean()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (id >= 0 && content.isNotEmpty()) p.added.add(Triple(content, id, isSpecial))
            }
            r.endArray()
        }

        private fun readModel(r: JsonReader, p: Parsed) {
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "type" -> p.type = if (r.peek() == JsonToken.NULL) { r.nextNull(); "" } else r.nextString()
                    "unk_id" -> if (r.peek() == JsonToken.NULL) r.nextNull() else p.unkId = r.nextInt()
                    "unk_token" -> if (r.peek() == JsonToken.NULL) r.nextNull() else p.unkToken = r.nextString()
                    "vocab" -> when (r.peek()) {
                        JsonToken.BEGIN_ARRAY -> {          // Unigram: [[piece, score], ...]
                            val v = ArrayList<String>(260_000)
                            val s = ArrayList<Double>(260_000)
                            r.beginArray()
                            while (r.hasNext()) {
                                r.beginArray()
                                v.add(r.nextString())
                                s.add(r.nextDouble())
                                while (r.hasNext()) r.skipValue()
                                r.endArray()
                            }
                            r.endArray()
                            p.vocabList = v
                            p.scoreList = s
                        }
                        JsonToken.BEGIN_OBJECT -> {         // BPE: {piece: id}
                            val m = HashMap<String, Int>(520_000)
                            r.beginObject()
                            while (r.hasNext()) {
                                val name = r.nextName()
                                m[name] = r.nextInt()
                            }
                            r.endObject()
                            p.vocabMap = m
                        }
                        else -> r.skipValue()
                    }
                    "merges" -> if (r.peek() == JsonToken.BEGIN_ARRAY) {
                        val m = HashMap<String, Int>(600_000)
                        var rank = 0
                        r.beginArray()
                        while (r.hasNext()) {
                            val key = when (r.peek()) {
                                JsonToken.STRING -> r.nextString()                // "a b"
                                JsonToken.BEGIN_ARRAY -> {                        // ["a", "b"]
                                    r.beginArray()
                                    val a = r.nextString()
                                    val b = r.nextString()
                                    while (r.hasNext()) r.skipValue()
                                    r.endArray()
                                    "$a $b"
                                }
                                else -> { r.skipValue(); null }
                            }
                            if (key != null && !m.containsKey(key)) m[key] = rank
                            rank++
                        }
                        r.endArray()
                        p.merges = m
                    } else r.skipValue()
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }
    }
}
