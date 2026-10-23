package uz.dublaj.text

import uz.dublaj.data.TranscriptUnit

/**
 * Whisper segmentlarini TO'LIQ GAPLARGA birlashtiradi (Python `merge_units` porti).
 *
 * Muammo: Whisper vaqt bo'yicha bo'ladi — gap o'rtasida kesadi ("...daryo dengizga quyiladigan joyda" | "Море.").
 * Har bir bo'lak alohida tarjima qilinsa, o'zbekchada so'z tartibi o'zgargani uchun ma'no buziladi.
 * Shuning uchun tarjimadan OLDIN gap tugamaguncha segmentlar qo'shiladi; vaqt = birinchi boshi … oxirgi tugashi.
 */
object SentenceMerger {

    private val WS = Regex("\\s+")

    /** Gap oxiri: . ! ? … (keyin ixtiyoriy yopuvchi qo'shtirnoq/qavs). */
    private val SENT_END = Regex("[.!?…][\"»”')\\]]*\\s*$")

    const val MAX_UNIT_SEC = 15.0
    const val MAX_GAP_SEC = 1.0
    const val MAX_CHARS = 600

    fun merge(
        units: List<TranscriptUnit>,
        maxUnitSec: Double = MAX_UNIT_SEC,
        maxGapSec: Double = MAX_GAP_SEC,
        maxChars: Int = MAX_CHARS
    ): List<TranscriptUnit> {
        val out = ArrayList<TranscriptUnit>()
        var cur: TranscriptUnit? = null
        for (u in units.sortedBy { it.start }) {
            val c = cur
            if (c != null && canJoin(c, u, maxUnitSec, maxGapSec, maxChars)) {
                cur = c.copy(
                    end = maxOf(c.end, u.end),
                    text = (c.text.trimEnd() + " " + u.text.trimStart()).trim()
                )
            } else {
                if (c != null) out.add(c)
                cur = u
            }
        }
        cur?.let { out.add(it) }
        return out.mapIndexed { i, u -> u.copy(id = i + 1) }
    }

    private fun canJoin(
        c: TranscriptUnit, u: TranscriptUnit,
        maxUnitSec: Double, maxGapSec: Double, maxChars: Int
    ): Boolean {
        if (c.language != u.language) return false
        val gap = u.start - c.end
        if (gap > maxGapSec) return false
        if (u.end - c.start > maxUnitSec) return false
        if (c.text.length + u.text.length >= maxChars) return false
        val ended = SENT_END.containsMatchIn(c.text)
        val lower = startsLower(u.text)
        // Gap tugagan bo'lsa: faqat kichik harf bilan davom etsa (Whisper noto'g'ri nuqta qo'ygan)
        if (ended) return lower && gap <= 0.6
        // Gap tugamagan, keyingisi kichik harf bilan boshlansa — aniq davomi
        if (lower) return true
        // Katta harf bilan boshlansa: odatda yangi gap (Whisper nuqtani tushirib qoldirgan).
        // Istisno: qisqa "dum" ("Море.") yoki oldingi bo'lak juda qisqa.
        val uWords = u.text.trim().split(WS).size
        val cWords = c.text.trim().split(WS).size
        return (uWords <= 3 && SENT_END.containsMatchIn(u.text)) || cWords < 4
    }

    private fun startsLower(s: String): Boolean {
        val ch = s.firstOrNull { it.isLetter() } ?: return false
        return ch.isLowerCase()
    }
}
