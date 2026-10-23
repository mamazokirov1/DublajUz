package uz.dublaj.translate

import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google Gemini orqali kontekstli tarjima (internet kerak). NLLB'dan ancha aniq:
 * jargon, og'zaki nutq va ASR xatolarini kontekstga qarab tushunadi.
 * Faqat HttpURLConnection + org.json ishlatiladi (qo'shimcha kutubxona kerak emas).
 */
class GeminiTranslator(
    private val apiKey: String,
    private val model: String
) {
    data class Item(val id: Int, val text: String, val maxChars: Int)
    data class Result(val uz: String, val short: String?)

    /** [fatal] = true: kalit noto'g'ri / ruxsat yo'q — qayta urinishdan foyda yo'q. */
    class GeminiException(message: String, val fatal: Boolean) : Exception(message)

    private fun endpoint() =
        "https://generativelanguage.googleapis.com/v1beta/models/${model.trim()}:generateContent"

    /** Sozlamalardagi "Kalitni tekshirish" tugmasi uchun. */
    fun test(): String = try {
        val body = JSONObject().put(
            "contents", JSONArray().put(
                JSONObject().put(
                    "parts", JSONArray().put(JSONObject().put("text", "Reply with one word: OK"))
                )
            )
        )
        val text = extractText(post(body, retries = 1))
        if (text.isNotBlank()) "Gemini ishlayapti ✓ (${model.trim()})" else "Gemini javob berdi, lekin matn bo'sh"
    } catch (e: GeminiException) {
        "Gemini xato: ${e.message}"
    } catch (e: Exception) {
        "Gemini bilan aloqa yo'q: ${e.message}"
    }

    /**
     * Bir nechta ketma-ket gapni tarjima qiladi.
     * @param context oldingi gaplar (manba, tarjima) — faqat ma'no uchun
     * @param nextHint keyingi gapning manba matni (ixtiyoriy)
     */
    fun translateChunk(
        srcLang: String,
        context: List<Pair<String, String>>,
        items: List<Item>,
        nextHint: String?
    ): Map<Int, Result> {
        if (items.isEmpty()) return emptyMap()

        val user = StringBuilder()
        user.append("Source language: ").append(langName(srcLang)).append("\n\n")
        if (context.isNotEmpty()) {
            user.append("Previously translated segments (context only, do NOT output them):\n")
            for ((s, t) in context) user.append("- SRC: ").append(s).append("\n  UZ: ").append(t).append('\n')
            user.append('\n')
        }
        val arr = JSONArray()
        for (item in items) {
            arr.put(JSONObject().put("id", item.id).put("maxChars", item.maxChars).put("text", item.text))
        }
        user.append("Segments to translate:\n").append(arr.toString()).append('\n')
        if (!nextHint.isNullOrBlank()) {
            user.append("\nNext segment after these (context only, do NOT output): ").append(nextHint).append('\n')
        }

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            .put(
                "contents", JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", user.toString())))
                )
            )
            .put(
                "generationConfig", JSONObject()
                    .put("temperature", 0.3)
                    .put("responseMimeType", "application/json")
            )

        val text = extractText(post(body, retries = 3))
        return parse(text)
    }

    // ------------------------------------------------------------------ ichki

    private fun parse(raw: String): Map<Int, Result> {
        var t = raw.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
            t = t.removeSuffix("```").trim()
        }
        val arr: JSONArray = try {
            if (t.startsWith("[")) JSONArray(t) else {
                val o = JSONObject(t)
                var found: JSONArray? = null
                val keys = o.keys()
                while (keys.hasNext() && found == null) found = o.optJSONArray(keys.next())
                found ?: throw GeminiException("Javob formati noto'g'ri", false)
            }
        } catch (e: GeminiException) {
            throw e
        } catch (e: Exception) {
            throw GeminiException("Javobni o'qib bo'lmadi (${e.message})", false)
        }
        val out = HashMap<Int, Result>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.has("id")) continue
            val id = o.optInt("id", Int.MIN_VALUE)
            val uz = o.optString("uz", "").trim()
            if (id == Int.MIN_VALUE || uz.isEmpty()) continue
            val short = o.optString("short", "").trim().ifEmpty { null }
            out[id] = Result(uz, short)
        }
        return out
    }

    private fun extractText(resp: JSONObject): String {
        val block = resp.optJSONObject("promptFeedback")?.optString("blockReason", "") ?: ""
        if (block.isNotEmpty()) throw GeminiException("So'rov bloklandi: $block", false)
        val cands = resp.optJSONArray("candidates") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until cands.length()) {
            val parts = cands.optJSONObject(i)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val p = parts.optJSONObject(j) ?: continue
                if (p.optBoolean("thought", false)) continue
                sb.append(p.optString("text", ""))
            }
            if (sb.isNotEmpty()) break
        }
        return sb.toString()
    }

    private fun post(body: JSONObject, retries: Int): JSONObject {
        var lastErr: GeminiException? = null
        val delays = longArrayOf(2_000, 5_000, 12_000)
        for (attempt in 0 until retries) {
            try {
                return postOnce(body)
            } catch (e: GeminiException) {
                if (e.fatal) throw e
                lastErr = e
                Timber.w("Gemini urinish ${attempt + 1}/$retries: ${e.message}")
                if (attempt < retries - 1) Thread.sleep(delays[min2(attempt, delays.size - 1)])
            }
        }
        throw lastErr ?: GeminiException("Noma'lum xato", false)
    }

    private fun min2(a: Int, b: Int) = if (a < b) a else b

    private fun postOnce(body: JSONObject): JSONObject {
        val conn = try {
            URL(endpoint()).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw GeminiException("Manzil noto'g'ri: ${e.message}", true)
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", apiKey.trim())
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code in 200..299) {
                return try {
                    JSONObject(text)
                } catch (e: Exception) {
                    throw GeminiException("Javob JSON emas", false)
                }
            }
            val msgFull: String = try {
                JSONObject(text).optJSONObject("error")?.optString("message", "") ?: ""
            } catch (e: Exception) {
                ""
            }
            val msg = msgFull.take(200)
            val fatal = code == 400 || code == 401 || code == 403 || code == 404
            val hint = when (code) {
                400 -> "so'rov yoki kalit noto'g'ri"
                401, 403 -> "kalit noto'g'ri yoki ruxsat yo'q"
                404 -> "model topilmadi (model nomini tekshiring)"
                429 -> "limit tugadi, biroz kutish kerak"
                else -> "server xatosi"
            }
            throw GeminiException("HTTP $code — $hint${if (msg.isNotEmpty()) ": $msg" else ""}", fatal)
        } catch (e: GeminiException) {
            throw e
        } catch (e: java.io.IOException) {
            throw GeminiException("Internet aloqasi: ${e.message}", false)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        const val KEY_URL = "https://aistudio.google.com/apikey"

        /** Taxminiy TTS tezligi: soniyasiga shuncha belgi (uzunlik cheklovi uchun). */
        const val CHARS_PER_SEC = 16

        fun langName(code: String): String = when (code.lowercase()) {
            "en" -> "English"; "ru" -> "Russian"; "tr" -> "Turkish"; "ar" -> "Arabic"
            "de" -> "German"; "fr" -> "French"; "es" -> "Spanish"; "it" -> "Italian"
            "pt" -> "Portuguese"; "zh" -> "Chinese"; "ja" -> "Japanese"; "ko" -> "Korean"
            "hi" -> "Hindi"; "fa" -> "Persian"; "uk" -> "Ukrainian"; "kk" -> "Kazakh"
            "tg" -> "Tajik"; "pl" -> "Polish"; "nl" -> "Dutch"; "id" -> "Indonesian"
            "vi" -> "Vietnamese"; "th" -> "Thai"; "az" -> "Azerbaijani"
            else -> "language code \"$code\""
        }

        private val SYSTEM_PROMPT = """
You are an expert audiovisual translator who dubs videos into Uzbek (Latin script, modern standard Uzbek as spoken in Uzbekistan).
Translate every numbered segment into natural SPOKEN Uzbek that sounds like a native voice-over.

Rules:
1. Meaning first. Be faithful: never invent, omit or soften content.
2. The segments come from automatic speech recognition. If a word is obviously misrecognized, infer the intended word from the context.
3. Idioms, slang, jokes and colloquial speech: translate the intended meaning into natural Uzbek, never word for word.
4. Keep proper names, brands and places; use standard Uzbek Latin spelling where one exists.
5. Write numbers as digits.
6. Use the plain ASCII apostrophe for o', g' and a'lo (never typographic quotes).
7. Each segment has "maxChars". Keep "uz" at or below it by choosing concise phrasing, but never at the cost of changing the meaning.
8. Also give "short": a more concise version of the same translation (about 75% of the length) that keeps the key meaning. It is used only when the audio does not fit.
9. Segments are consecutive parts of one video: keep terminology, tone and speaker address (sen/siz) consistent. Context lines are for reference only.
10. No explanations, notes or extra quotation marks.

Output ONLY a JSON array with exactly one object per segment to translate:
[{"id": <int>, "uz": "<translation>", "short": "<concise translation>"}]
""".trimIndent()
    }
}
