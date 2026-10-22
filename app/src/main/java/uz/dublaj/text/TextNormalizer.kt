package uz.dublaj.text

import android.content.Context
import org.json.JSONObject
import timber.log.Timber
import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern

/**
 * Python `src/text_normalizer.py` porti.
 * TTS uchun matnni tayyorlaydi: raqamlar, qisqartmalar, kirill→lotin.
 */
class TextNormalizer(
    private val lexicon: Map<String, String> = emptyMap(),
    private val acronyms: String = "auto" // auto | spell | word
) {
    // Lug'at qoidasi (lexicon.json):
    //  - butunlay kichik harfli kalit ("json", "go mod") -> har qanday registrda ishlaydi
    //  - tarkibida katta harf bor kalit ("JSON", "Google", "LoRa") -> faqat aynan shunday yozilganda
    // Shu sababli "JSON" va "json" alohida yozuv bo'la oladi.
    private val caseSensitive: Map<String, String> =
        lexicon.filter { it.key != it.key.lowercase(Locale.ROOT) }
    private val caseInsensitive: Map<String, String> =
        lexicon.filter { it.key == it.key.lowercase(Locale.ROOT) }

    companion object {
        private val ONES = listOf(
            "nol", "bir", "ikki", "uch", "to'rt", "besh", "olti", "yetti", "sakkiz", "to'qqiz"
        )
        private val TENS = listOf(
            "", "o'n", "yigirma", "o'ttiz", "qirq", "ellik", "oltmish", "yetmish", "sakson", "to'qson"
        )
        private val GROUPS = listOf(
            1_000_000_000_000L to "trillion",
            1_000_000_000L to "milliard",
            1_000_000L to "million",
            1_000L to "ming"
        )
        private val LETTER_NAMES = mapOf(
            'A' to "a", 'B' to "be", 'C' to "se", 'D' to "de", 'E' to "e", 'F' to "ef",
            'G' to "ge", 'H' to "ha", 'I' to "i", 'J' to "je", 'K' to "ka", 'L' to "el",
            'M' to "em", 'N' to "en", 'O' to "o", 'P' to "pe", 'Q' to "qe", 'R' to "er",
            'S' to "es", 'T' to "te", 'U' to "u", 'V' to "ve", 'W' to "dubl ve",
            'X' to "xa", 'Y' to "ye", 'Z' to "ze"
        )
        private val CURRENCY = mapOf(
            '$' to "dollar", '€' to "yevro", '£' to "funt", '₽' to "rubl", '₸' to "tenge"
        )
        private val THOUSANDS_COMMA = Regex("""(?<![\d,])\d{1,3}(?:,\d{3})+(?![\d,])""")
        private val THOUSANDS_SPACE = Regex("""(?<![\d])\d{1,3}(?:[ \u00A0]\d{3})+(?![\d])""")
        private val CYR_VOWELS = "аеёиоуыэюяъь"
        private val APOSTROPHES = "'’ʻʼ‘`"
        private val CYR = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo",
            'ж' to "j", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
            'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
            'ф' to "f", 'х' to "x", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sh",
            'ъ' to "'", 'ы' to "i", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
            'ў' to "o'", 'қ' to "q", 'ғ' to "g'", 'ҳ' to "h"
        )

        @Volatile private var cached: TextNormalizer? = null

        fun fromAssets(context: Context, assetName: String = "lexicon.json"): TextNormalizer {
            cached?.let { return it }
            return synchronized(this) {
                cached ?: try {
                    val json = context.applicationContext.assets.open(assetName)
                        .bufferedReader(Charsets.UTF_8).use { it.readText() }
                        .removePrefix("\uFEFF")
                    val obj = JSONObject(json)
                    val map = LinkedHashMap<String, String>()
                    obj.keys().forEach { k ->
                        if (!k.startsWith("_")) map[k] = obj.getString(k)
                    }
                    Timber.i("lexicon yuklandi: ${map.size} ta yozuv")
                    TextNormalizer(map).also { cached = it }
                } catch (e: Exception) {
                    // keshlamaymiz — keyingi chaqiruvda qayta urinadi
                    Timber.e(e, "lexicon YUKLANMADI, bo'sh lug'at ishlatilmoqda")
                    TextNormalizer()
                }
            }
        }
    }

    fun normalize(text: String): String {
        var t = text.trim()
        if (t.isEmpty()) return t
        t = transliterate(t)
        t = applyLexicon(t)
        t = expandNumbers(t)
        t = expandAcronyms(t)
        // ortiqcha bo'shliqlarni tozalash
        return t.replace(Regex("\\s+"), " ").trim()
    }

    // ---------- raqamlar ----------
    private fun under1000(n: Int): String {
        val words = mutableListOf<String>()
        val hundreds = n / 100
        val rest = n % 100
        if (hundreds > 0) {
            words += ONES[hundreds]
            words += "yuz"
        }
        val tens = rest / 10
        val ones = rest % 10
        if (tens > 0) words += TENS[tens]
        if (ones > 0 || words.isEmpty()) words += ONES[ones]
        return words.joinToString(" ")
    }

    private fun numberToWords(n: Long): String {
        if (n == 0L) return "nol"
        if (n < 0) return "minus ${numberToWords(-n)}"
        val parts = mutableListOf<String>()
        var rem = n
        for ((value, name) in GROUPS) {
            if (rem >= value) {
                val count = (rem / value).toInt()
                parts += under1000(count)
                parts += name
                rem %= value
            }
        }
        if (rem > 0) parts += under1000(rem.toInt())
        return parts.joinToString(" ")
    }

    private fun expandNumbers(text: String): String {
        // 1,500,000 (inglizcha minglik vergul)
        var t = THOUSANDS_COMMA.replace(text) { m -> m.value.replace(",", "") }
        // 1 500 000 (probel bilan minglik): faqat 1-3 xonali boshlanish
        t = THOUSANDS_SPACE.replace(t) { m -> m.value.replace(" ", "").replace("\u00A0", "") }
        // foiz: 16%
        t = t.replace(Regex("""(\d+(?:[.,]\d+)?)\s*%""")) { m ->
            val num = m.groupValues[1].replace(',', '.')
            val words = decimalToWords(num)
            "$words foiz"
        }
        // valyuta: $50, 50$
        t = t.replace(Regex("""([$€£₽₸])\s*(\d+(?:[.,]\d+)?)""")) { m ->
            val cur = CURRENCY[m.groupValues[1][0]] ?: m.groupValues[1]
            "${decimalToWords(m.groupValues[2].replace(',', '.'))} $cur"
        }
        t = t.replace(Regex("""(\d+(?:[.,]\d+)?)\s*([$€£₽₸])""")) { m ->
            val cur = CURRENCY[m.groupValues[2][0]] ?: m.groupValues[2]
            "${decimalToWords(m.groupValues[1].replace(',', '.'))} $cur"
        }
        // tartib: 5-chi, 16-chi
        t = t.replace(Regex("""(\d+)\s*-\s*chi\b""", RegexOption.IGNORE_CASE)) { m ->
            ordinal(m.groupValues[1].toLong())
        }
        // oddiy butun / kasr
        t = t.replace(Regex("""\b(\d+)([.,](\d+))?\b""")) { m ->
            val whole = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            val frac = m.groupValues[3]
            if (frac.isNotEmpty()) {
                val wholeW = numberToWords(whole)
                val fracW = frac.map { ONES[it - '0'] }.joinToString(" ")
                "$wholeW butun $fracW"
            } else {
                numberToWords(whole)
            }
        }
        return t
    }

    private fun decimalToWords(s: String): String {
        val parts = s.split('.')
        val whole = parts[0].toLongOrNull() ?: return s
        return if (parts.size > 1 && parts[1].isNotEmpty()) {
            val frac = parts[1].map { ONES[it.digitToInt()] }.joinToString(" ")
            "${numberToWords(whole)} butun $frac"
        } else numberToWords(whole)
    }

    private fun ordinal(n: Long): String {
        // soddalashtirilgan: "beshinchi" uslubi
        val base = numberToWords(n)
        return when {
            base.endsWith("bir") -> base.dropLast(3) + "birinchi"
            base.endsWith("ikki") -> base.dropLast(4) + "ikkinchi"
            base.endsWith("uch") -> base.dropLast(3) + "uchinchi"
            base.endsWith("to'rt") -> base.dropLast(5) + "to'rtinchi"
            base.endsWith("besh") -> base.dropLast(4) + "beshinchi"
            base.endsWith("olti") -> base.dropLast(4) + "oltinchi"
            base.endsWith("yetti") -> base.dropLast(5) + "yettinchi"
            base.endsWith("sakkiz") -> base.dropLast(6) + "sakkizinchi"
            base.endsWith("to'qqiz") -> base.dropLast(7) + "to'qqizinchi"
            base.endsWith("o'n") -> base + "inchi"
            else -> base + "inchi"
        }
    }

    // ---------- lug'at ----------
    // Barcha kalitlar BITTA regex'da, uzunlari birinchi: "5g sa" -> "5G" dan oldin,
    // "Massive MIMO" -> "MIMO" dan oldin. Almashtirilgan matn qayta almashtirilmaydi.
    private val lexPattern: Regex? by lazy {
        val all = caseSensitive.keys.map { it to false } + caseInsensitive.keys.map { it to true }
        if (all.isEmpty()) {
            null
        } else {
            val body = all.sortedByDescending { it.first.length }   // barqaror: teng uzunlikda aniq-registr oldin
                .joinToString("|") { (k, ci) ->
                    val q = Pattern.quote(k)
                    if (ci) "(?i:$q)" else q
                }
            Regex("(?<![\\p{L}\\p{N}])(?:$body)(?![\\p{L}\\p{N}])")
        }
    }

    private fun applyLexicon(text: String): String {
        val re = lexPattern ?: return text
        return re.replace(text) { m ->
            caseSensitive[m.value]
                ?: caseInsensitive[m.value.lowercase(Locale.ROOT)]
                ?: m.value
        }
    }

    // ---------- qisqartmalar ----------
    private fun expandAcronyms(text: String): String {
        if (acronyms == "word") return text
        return text.replace(Regex("""\b([A-Z]{2,6})\b""")) { m ->
            val acr = m.groupValues[1]
            if (caseSensitive.containsKey(acr) || caseInsensitive.containsKey(acr.lowercase())) {
                m.value // lug'atda bor
            } else if (acronyms == "spell" || (acronyms == "auto" && !hasVowel(acr))) {
                spell(acr)
            } else {
                m.value
            }
        }
    }

    private fun hasVowel(s: String) = s.any { it.lowercaseChar() in "aeiouo'" }

    private fun spell(letters: String): String =
        letters.mapNotNull { LETTER_NAMES[it.uppercaseChar()] }.joinToString(" ")

    // ---------- kirill ----------
    fun transliterate(text: String): String {
        val sb = StringBuilder()
        var prev = ' '
        for (ch in text) {
            val lower = ch.lowercaseChar()
            if (CYR.containsKey(lower)) {
                var mapped = CYR[lower]!!
                if (lower == 'е' && (!prev.isLetter() || prev.lowercaseChar() in CYR_VOWELS)) mapped = "ye"
                sb.append(if (ch.isUpperCase() && mapped.isNotEmpty()) mapped.replaceFirstChar { it.uppercase() } else mapped)
            } else if (ch.code < 128 || ch in APOSTROPHES) {
                sb.append(ch)
            } else if (Character.isLetter(ch)) {
                // diakritik olib tashlash
                val norm = Normalizer.normalize(ch.toString(), Normalizer.Form.NFKD)
                sb.append(norm.filter { it.code < 128 })
            } else {
                sb.append(ch)
            }
            prev = ch
        }
        return sb.toString()
    }
}
