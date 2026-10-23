package uz.dublaj.data

data class TranscriptUnit(
    val id: Int,
    val start: Double,
    val end: Double,
    val text: String,
    val language: String = "en"
)

data class TranslationUnit(
    val id: Int,
    val start: Double,
    val end: Double,
    val text: String,          // original
    val uzText: String,        // foydalanuvchi tahrir qilgan o'zbekcha matn
    val language: String = "en",
    /** true = tarjima ishlamadi / tekshirish kerak. Foydalanuvchi tahrir qilgach false bo'ladi. */
    val check: Boolean = false,
    /** Tarjima nomzodlari (alts[0] = tanlangan). Dublaj vaqtida sig'masa qisqaroq variant sinab ko'riladi. */
    val alts: List<String> = emptyList()
)

data class AppConfig(
    val originalVolume: Float = 0.25f,
    val uzbekVolume: Float = 1.0f,
    val maxAtempo: Float = 1.3f,
    val whisperModel: String = "small",
    val threads: Int = 4,
    val marginS: Float = 0.1f,
    val gapS: Float = 0.08f,
    /** "auto" yoki Whisper til kodi (en, ru, tr, ...) */
    val sourceLang: String = "auto",
    // ---- ovoz sifati (VITS)
    /** Intonatsiya o'zgaruvchanligi (noise_scale). Yuqori = jonliroq, lekin beqarorroq. */
    val voiceNoise: Float = 0.667f,
    /** Ritm/davomiylik o'zgaruvchanligi (noise_scale_w). */
    val voiceNoiseW: Float = 0.4f,
    /** Nutq tezligi: 1.0 = oddiy, >1 tezroq (length_scale = 1 / voiceSpeed). */
    val voiceSpeed: Float = 1.0f,
    /** Gap ichidagi bo'laklar orasidagi pauza (soniya). */
    val voicePause: Float = 0.25f,
    /** Ovoz tembri, yarim ton (-4 chuqurroq … +4 ingichkaroq). 0 = o'zgarishsiz. */
    val voicePitch: Float = 0f,
    /** Gap vaqtga sig'masa sintez ichida eng kam length_scale (0.8 = 1.25× tezlashish). */
    val fitScale: Float = 0.8f,
    // ---- aralashtirish
    /** Dublaj gapirganda asl ovoz darajasi (1.0 = pasaytirilmaydi). */
    val duckAmount: Float = 0.2f,
    /** Har bir klip balandligini tenglash. */
    val levelClips: Boolean = true,
    // ---- ko'rinish
    /** false = Oddiy (kam sozlama), true = Mutaxassis (hamma sozlama). */
    val expertMode: Boolean = false,
    /** 0 = tizim, 1 = yorug', 2 = qorong'i. */
    val themeMode: Int = 0,
    val dynamicColor: Boolean = false,
    /** Tayyor videoni avtomatik Galereyaga (Movies/DublajUz) ham saqlash. */
    val autoSaveGallery: Boolean = false,
    // ---- onlayn tarjima (Gemini)
    val onlineTranslate: Boolean = false,
    val geminiKey: String = "",
    val geminiModel: String = "gemini-3.8-flash"
) {
    /** Onlayn tarjima yoqilgan va kalit kiritilgan. */
    val onlineReady: Boolean get() = onlineTranslate && geminiKey.isNotBlank()
}

enum class VoicePreset(val label: String) { STANDARD("Standart"), NATURAL("Tabiiy"), CLEAR("Aniq"), FAST("Tez") }

/** Boshlang'ich nuqtalar — quloq bilan sozlang. */
fun AppConfig.applyPreset(p: VoicePreset): AppConfig = when (p) {
    VoicePreset.STANDARD -> copy(voiceNoise = 0.667f, voiceNoiseW = 0.4f, voiceSpeed = 1.0f, voicePause = 0.25f, fitScale = 0.8f, maxAtempo = 1.3f)
    VoicePreset.NATURAL -> copy(voiceNoise = 0.75f, voiceNoiseW = 0.6f, voiceSpeed = 0.95f, voicePause = 0.35f, fitScale = 0.85f, maxAtempo = 1.2f)
    VoicePreset.CLEAR -> copy(voiceNoise = 0.5f, voiceNoiseW = 0.3f, voiceSpeed = 0.9f, voicePause = 0.3f, fitScale = 0.9f, maxAtempo = 1.15f)
    VoicePreset.FAST -> copy(voiceNoise = 0.667f, voiceNoiseW = 0.4f, voiceSpeed = 1.1f, voicePause = 0.15f, fitScale = 0.75f, maxAtempo = 1.4f)
}

data class ModelsInfo(
    val whisper: Boolean = false,
    val nllb: Boolean = false,
    val vits: Boolean = false
)

sealed class PipelineState {
    data object Idle : PipelineState()
    data class Running(val stage: String, val progress: Float = 0f, val message: String = "") : PipelineState()
    data class Success(val outputPath: String) : PipelineState()
    data class Error(val message: String) : PipelineState()
}

/** Foydalanuvchi "Bekor qilish" bosganda tashlanadi. */
class UserCancelled : RuntimeException("Bekor qilindi")

enum class QueueStatus { WAITING, RUNNING, DONE, FAILED, CANCELLED }

/** Navbatdagi video. */
data class QueueItem(
    val id: Long,
    val uri: android.net.Uri,
    val name: String,
    val status: QueueStatus = QueueStatus.WAITING,
    val progress: Float = 0f,
    val message: String = "",
    val outputPath: String? = null
)
