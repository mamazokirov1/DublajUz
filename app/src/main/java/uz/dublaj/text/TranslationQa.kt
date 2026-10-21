package uz.dublaj.text

/**
 * Tarjima sifatini tekshirish. null = muammo yo'q, aks holda sabab (foydalanuvchi tahrir ekranida
 * "tekshirilsin" belgisini ko'radi).
 */
object TranslationQa {

    private val DIGITS = Regex("\\d+")
    private val WS = Regex("\\s+")

    fun issue(src: String, uz: String): String? {
        // 1) raqamlar yo'qolmasligi kerak (Python number_check)
        val a = DIGITS.findAll(src).map { it.value }.sorted().toList()
        val b = DIGITS.findAll(uz).map { it.value }.sorted().toList()
        if (a != b && a.isNotEmpty()) return "Raqamlar farq qiladi: $a / $b"

        // 2) uzunlik nisbati: kesilib qolgan yoki to'qib chiqarilgan tarjima
        val ls = src.count { it.isLetter() }
        val lu = uz.count { it.isLetter() }
        if (ls >= 20) {
            val r = lu.toDouble() / ls
            if (r < 0.4 || r > 2.5) return "Uzunlik nomuvofiq (%.2f)".format(r)
        }

        // 3) bir so'z qayta-qayta takrorlansa (NLLB tsikli)
        val words = uz.lowercase().split(WS).filter { it.length > 3 }
        if (words.size >= 6) {
            val top = words.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
            if (top >= 4) return "Takror so'zlar"
        }

        // 4) tarjima qilinmay qolgan (kirill) matn
        val cyr = uz.count { it in '\u0400'..'\u04FF' }
        if (uz.isNotEmpty() && cyr > uz.length / 3) return "Tarjima qilinmagan"
        return null
    }
}
