package uz.dublaj.translate

/** Whisper til kodi → NLLB (FLORES-200) kodi. */
object Languages {
    const val TARGET = "uzn_Latn"

    val FLORES: Map<String, String> = mapOf(
        "en" to "eng_Latn", "ru" to "rus_Cyrl", "tr" to "tur_Latn", "ar" to "arb_Arab",
        "de" to "deu_Latn", "fr" to "fra_Latn", "es" to "spa_Latn", "it" to "ita_Latn",
        "pt" to "por_Latn", "zh" to "zho_Hans", "ja" to "jpn_Jpan", "ko" to "kor_Hang",
        "hi" to "hin_Deva", "fa" to "pes_Arab", "uk" to "ukr_Cyrl", "kk" to "kaz_Cyrl",
        "tg" to "tgk_Cyrl", "pl" to "pol_Latn", "nl" to "nld_Latn", "id" to "ind_Latn",
        "vi" to "vie_Latn", "th" to "tha_Thai", "az" to "azj_Latn"
    )

    /** Sozlamalar menyusi uchun: (kod, nom). */
    val UI_OPTIONS: List<Pair<String, String>> = listOf(
        "auto" to "Avto (faqat en/ru farqlanadi)",
        "en" to "Inglizcha", "ru" to "Ruscha", "tr" to "Turkcha", "ar" to "Arabcha",
        "de" to "Nemischa", "fr" to "Fransuzcha", "es" to "Ispancha", "it" to "Italyancha",
        "pt" to "Portugalcha", "zh" to "Xitoycha", "ja" to "Yaponcha", "ko" to "Koreyscha",
        "hi" to "Hindcha", "fa" to "Forscha", "uk" to "Ukraincha", "kk" to "Qozoqcha",
        "tg" to "Tojikcha", "pl" to "Polyakcha", "nl" to "Niderlandcha", "id" to "Indonezcha",
        "vi" to "Vyetnamcha", "th" to "Tayland", "az" to "Ozarbayjoncha"
    )

    fun label(code: String): String = UI_OPTIONS.find { it.first == code }?.second ?: code

    fun floresOf(whisperCode: String): String =
        FLORES[whisperCode.lowercase()]
            ?: throw IllegalArgumentException("Til qo'llab-quvvatlanmaydi: $whisperCode")
}
