package uz.dublaj.pipeline

import timber.log.Timber
import uz.dublaj.util.RawPcm
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * TTS segmentlarini video vaqtiga joylaydi (Python `render_track` porti).
 * Har segment diskka klip sifatida yoziladi — butun trek xotirada saqlanmaydi.
 * Sig'masa WSOLA bilan (pitch'ni saqlab) maxSpeed gacha tezlashtiriladi.
 */
class SegmentPlacer(
    private val totalSec: Double,
    private val sr: Int,
    private val maxSpeed: Float,
    private val marginSec: Double,
    private val gapSec: Double,
    private val clipDir: File,
    private val fadeMs: Int = 12,
    /** Sig'masa klip asl gap boshidan ko'pi bilan shuncha oldin boshlanishi mumkin. */
    private val maxEarlySec: Double = 0.25
) {
    data class Clip(
        val id: Int,
        val placeAtSample: Long,
        val samples: Int,
        val file: File,
        val speed: Float
    )

    private var cursor = 0.0

    /** @param pcm allaqachon [sr] chastotada */
    fun place(id: Int, start: Double, nextStart: Double?, pcm: ShortArray): Clip? {
        if (pcm.isEmpty()) return null
        var audio = pcm
        var dur = audio.size.toDouble() / sr

        val limit = if (nextStart != null) nextStart - marginSec else totalSec
        val from = max(start, cursor)
        val available = max(0.15, limit - from)

        var speed = 1.0f
        if (dur > available) {
            speed = min(maxSpeed, (dur / available).toFloat())
            if (speed > 1.01f) {
                audio = TimeStretch.stretch(audio, sr, speed)
                dur = audio.size.toDouble() / sr
            }
        }

        var placeAt = from
        if (placeAt + dur > limit) {
            // Oldinga cheklangan siljish; qolgani keyingi gapga "qarz" bo'lib o'tadi (cursor)
            val earliest = max(cursor, start - maxEarlySec)
            placeAt = max(0.0, max(earliest, limit - dur))
        }

        val startSample = (placeAt * sr).roundToInt().toLong().coerceAtLeast(0)
        applyFade(audio)
        val file = File(clipDir, "clip_$id.pcm")
        RawPcm.write(file, audio)

        cursor = placeAt + dur + gapSec
        Timber.d("place #$id @${"%.2f".format(placeAt)}s dur=${"%.2f".format(dur)} speed=$speed")
        return Clip(id, startSample, audio.size, file, speed)
    }

    private fun applyFade(a: ShortArray) {
        val fade = min((sr * fadeMs / 1000.0).roundToInt().coerceAtLeast(1), a.size / 4)
        if (fade <= 0) return
        for (i in 0 until fade) {
            val g = i.toFloat() / fade
            a[i] = (a[i] * g).toInt().toShort()
            val j = a.size - 1 - i
            a[j] = (a[j] * g).toInt().toShort()
        }
    }
}
