package uz.dublaj.translate

import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.text.Normalizer
import kotlin.math.min

/**
 * NLLB (SentencePiece Unigram) tokenizeri: tokenizer.json dagi vocab [piece, score] massivi
 * ustida Viterbi (eng yuqori ball) bo'yicha bo'laklaydi.
 * (SentencePiece'ning NFKC normalizatsiyasi taxminan NFKC bilan almashtirilgan.)
 */
class NllbTokenizer private constructor(
    private val pieces: Array<String>,
    private val scores: DoubleArray,
    private val piece2id: HashMap<String, Int>,
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
                val sc = best[i] + scores[id]
                if (sc > best[i + l]) {
                    best[i + l] = sc
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

    companion object {
        fun fromFile(tokenizerJson: File): NllbTokenizer {
            val root = JSONObject(tokenizerJson.readText())
            val model = root.optJSONObject("model") ?: throw IllegalStateException("tokenizer.json: model yo'q")
            val vocab = model.opt("vocab")
            if (vocab !is JSONArray) {
                throw IllegalStateException(
                    "tokenizer.json Unigram formatida emas (vocab massiv emas). " +
                        "Xenova/nllb-200-distilled-600M tokenizer.json ishlating."
                )
            }
            val n = vocab.length()
            val pieces = Array(n) { "" }
            val scores = DoubleArray(n)
            val piece2id = HashMap<String, Int>(n * 2)
            var maxLen = 1
            for (i in 0 until n) {
                val e = vocab.getJSONArray(i)
                val p = e.getString(0)
                pieces[i] = p
                scores[i] = e.getDouble(1)
                if (!piece2id.containsKey(p)) piece2id[p] = i
                if (p.length > maxLen) maxLen = p.length
            }

            val special = HashMap<String, Int>()
            val specialIds = HashSet<Int>()
            val added = root.optJSONArray("added_tokens")
            if (added != null) {
                for (i in 0 until added.length()) {
                    val t = added.getJSONObject(i)
                    val content = t.getString("content")
                    val id = t.getInt("id")
                    special[content] = id
                    if (t.optBoolean("special", true)) specialIds.add(id)
                }
            }
            // Asosiy maxsus tokenlar (0..3) har doim chiqarishdan o'chiriladi
            for (name in listOf("<s>", "<pad>", "</s>", "<unk>")) {
                (special[name] ?: piece2id[name])?.let { specialIds.add(it) }
            }
            val unk = special["<unk>"] ?: piece2id["<unk>"] ?: 3
            Timber.i("Tokenizer: ${piece2id.size} pieces, ${special.size} added, maxLen=$maxLen")
            return NllbTokenizer(pieces, scores, piece2id, special, specialIds, unk, min(maxLen, 40))
        }
    }
}
