package uz.dublaj.stt

import android.content.Context
import org.json.JSONObject
import timber.log.Timber
import uz.dublaj.data.TranscriptUnit
import java.io.File

/**
 * STT xatolarini tuzatish (Python stt_fixes.json porti + qo'shimchalar):
 *  - butun so'z bo'yicha almashtirish (katta-kichik harfga sezgir emas, bosh harf saqlanadi);
 *  - bo'sh qiymat ("") so'zni o'chiradi (Whisper to'qigan jumlalar uchun);
 *  - rus matnida lotin "o'xshash" harflar (Mope → Море) kirillga o'tkaziladi.
 */
object SttFixes {

    fun load(context: Context, externalFile: File? = null): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()
        try {
            context.assets.open("stt_fixes.json").bufferedReader().use { reader ->
                parseInto(JSONObject(reader.readText()), result)
            }
        } catch (e: Exception) {
            Timber.w("assets/stt_fixes.json yo'q")
        }
        if (externalFile != null && externalFile.isFile) {
            try {
                parseInto(JSONObject(externalFile.readText()), result)
            } catch (e: Exception) {
                Timber.w(e, "stt_fixes.json o'qish xato")
            }
        }
        return result
    }

    private fun parseInto(obj: JSONObject, out: MutableMap<String, MutableMap<String, String>>) {
        obj.keys().forEach { key ->
            if (key.startsWith("_")) return@forEach
            val sub = obj.optJSONObject(key)
            if (sub != null) {
                val map = out.getOrPut(key) { mutableMapOf() }
                sub.keys().forEach { bad -> map[bad] = sub.getString(bad) }
            } else {
                // umumiy yozuv: "xato": "to'g'ri"
                val v = obj.optString(key, "")
                out.getOrPut("*") { mutableMapOf() }[key] = v
            }
        }
    }

    // ---- lotin → kirill o'xshash harflar
    private val HOMOGLYPH = mapOf(
        'A' to 'А', 'B' to 'В', 'C' to 'С', 'E' to 'Е', 'H' to 'Н', 'K' to 'К', 'M' to 'М',
        'O' to 'О', 'P' to 'Р', 'T' to 'Т', 'X' to 'Х',
        'a' to 'а', 'c' to 'с', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'x' to 'х', 'y' to 'у'
    )
    private val WORD = Regex("[\\p{L}]+")

    private fun isCyr(c: Char) = c in '\u0400'..'\u04FF'
    private fun isLat(c: Char) = c in 'A'..'Z' || c in 'a'..'z'

    /** Rus matnidagi lotin-o'xshash so'zlarni kirillga o'tkazadi. */
    fun fixHomoglyphs(text: String): String = WORD.replace(text) { m ->
        val w = m.value
        val hasCyr = w.any { isCyr(it) }
        val lat = w.filter { isLat(it) }
        when {
            lat.isEmpty() -> w
            // aralash so'z: kirill + lotin → hammasi kirill
            hasCyr -> w.map { HOMOGLYPH[it] ?: it }.joinToString("")
            // to'liq lotin so'z, lekin faqat o'xshash harflardan iborat (Mope)
            w.length >= 2 && lat.all { HOMOGLYPH.containsKey(it) } -> w.map { HOMOGLYPH[it] ?: it }.joinToString("")
            else -> w
        }
    }

    private fun matchCase(found: String, repl: String): String {
        if (repl.isEmpty()) return repl
        if (found.length > 1 && found.all { !it.isLetter() || it.isUpperCase() }) return repl.uppercase()
        if (found.first().isUpperCase()) return repl.replaceFirstChar { it.uppercase() }
        return repl
    }

    private fun applyTable(text: String, table: Map<String, String>): String {
        var t = text
        // uzunroq iboralar birinchi
        for ((bad, good) in table.entries.sortedByDescending { it.key.length }) {
            if (bad.isBlank()) continue
            val re = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(bad) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            t = re.replace(t) { m -> matchCase(m.value, good) }
        }
        return t.replace(Regex("\\s+"), " ").trim()
    }

    fun apply(units: List<TranscriptUnit>, fixes: Map<String, Map<String, String>>): List<TranscriptUnit> {
        val out = ArrayList<TranscriptUnit>()
        for (u in units) {
            var text = u.text
            if (u.language == "ru") text = fixHomoglyphs(text)
            val table = (fixes["*"] ?: emptyMap()) + (fixes[u.language] ?: emptyMap())
            if (table.isNotEmpty()) text = applyTable(text, table)
            if (text.none { it.isLetterOrDigit() }) continue   // bo'sh qolsa — olib tashlanadi
            out.add(if (text != u.text) u.copy(text = text) else u)
        }
        return out.mapIndexed { i, u -> u.copy(id = i + 1) }
    }
}
