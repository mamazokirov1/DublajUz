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
    val check: Boolean = false
)

data class AppConfig(
    val originalVolume: Float = 0.12f,
    val uzbekVolume: Float = 1.0f,
    val maxAtempo: Float = 1.3f,
    val whisperModel: String = "base",
    val threads: Int = 4,
    val marginS: Float = 0.1f,
    val gapS: Float = 0.08f,
    /** "auto" yoki Whisper til kodi (en, ru, tr, ...) */
    val sourceLang: String = "auto"
)

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
