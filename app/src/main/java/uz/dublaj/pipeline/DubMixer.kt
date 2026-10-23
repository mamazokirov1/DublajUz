package uz.dublaj.pipeline

import uz.dublaj.util.RawPcm
import uz.dublaj.util.WavFile
import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Asl audio (past ovozda) + dublyaj klip'larini bloklab aralashtiradi.
 * Xotirada faqat joriy blokka tegishli klip'lar turadi.
 */
class DubMixer(
    private val sr: Int,
    private val original: WavFile?,
    clips: List<SegmentPlacer.Clip>,
    private val originalVol: Float,
    private val dubVol: Float,
    /** Dublaj gapirganda asl ovoz ko'paytmasi (1 = pasaytirmaslik). */
    private val duckGain: Float = 1f,
    /** Asl nutq oralig'i (sample, [sr] chastotada): shu vaqtda ham asl ovoz pasaytiriladi. */
    origSpeech: List<Pair<Long, Long>> = emptyList()
) : Closeable {

    private val rStart: LongArray
    private val rEnd: LongArray
    private var rangeIdx = 0

    init {
        val ms = ArrayList<Long>()
        val me = ArrayList<Long>()
        for ((s, e) in origSpeech.sortedBy { it.first }) {
            if (e <= s) continue
            if (me.isNotEmpty() && s <= me[me.lastIndex]) {
                if (e > me[me.lastIndex]) me[me.lastIndex] = e
            } else {
                ms.add(s)
                me.add(e)
            }
        }
        rStart = ms.toLongArray()
        rEnd = me.toLongArray()
    }

    private class Active(val clip: SegmentPlacer.Clip, val data: ShortArray)

    private val sorted = clips.sortedBy { it.placeAtSample }
    private var nextClip = 0
    private val active = ArrayList<Active>()
    private var gain = 1f
    private val attack = (1.0 - Math.exp(-1.0 / (sr * 0.03))).toFloat()
    private val release = (1.0 - Math.exp(-1.0 / (sr * 0.25))).toFloat()

    fun mix(startSample: Long, count: Int): ShortArray {
        val out = FloatArray(count)
        val speech = BooleanArray(count)
        val end = startSample + count

        // ---- dublyaj klip'lari (+ gapirish belgisi)
        while (nextClip < sorted.size && sorted[nextClip].placeAtSample < end) {
            val c = sorted[nextClip++]
            active.add(Active(c, RawPcm.read(c.file)))
        }
        val it = active.iterator()
        while (it.hasNext()) {
            val a = it.next()
            val clipStart = a.clip.placeAtSample
            val clipEnd = clipStart + a.data.size
            if (clipEnd <= startSample) {
                it.remove()
                continue
            }
            val from = max(startSample, clipStart)
            val to = min(end, clipEnd)
            var t = from
            while (t < to) {
                val i = (t - startSample).toInt()
                out[i] += a.data[(t - clipStart).toInt()] / 32768f * dubVol
                speech[i] = true
                t++
            }
        }

        // ---- asl nutq oralig'ini ham "gapirish" deb belgilaymiz (dublaj kechiksa ham ikki ovoz bo'lmasin)
        var ri = rangeIdx
        while (ri < rEnd.size && rEnd[ri] <= startSample) ri++
        rangeIdx = ri
        var rj = ri
        while (rj < rStart.size && rStart[rj] < end) {
            val f0 = max(startSample, rStart[rj])
            val t0 = min(end, rEnd[rj])
            var q = f0
            while (q < t0) {
                speech[(q - startSample).toInt()] = true
                q++
            }
            rj++
        }

        // ---- asl ovoz (gapirganda silliq pasaytiriladi)
        val orig = original
        if (orig != null && originalVol > 0f) {
            val ratio = orig.sampleRate.toDouble() / sr
            val s0 = floor(startSample * ratio).toLong()
            val need = ceil(count * ratio).toInt() + 2
            val src = orig.read(s0, need)
            val ducking = duckGain < 0.999f
            for (i in 0 until count) {
                if (ducking) {
                    val target = if (speech[i]) duckGain else 1f
                    gain += (target - gain) * (if (target < gain) attack else release)
                }
                val p = (startSample + i) * ratio - s0
                val idx = p.toInt().coerceIn(0, src.lastIndex)
                val f = p - idx
                val a = src[idx].toInt()
                val b = src[min(idx + 1, src.lastIndex)].toInt()
                out[i] += (a + (b - a) * f).toFloat() / 32768f * originalVol * gain
            }
        }

        return ShortArray(count) { i ->
            (out[i].coerceIn(-0.95f, 0.95f) * 32767f).toInt().toShort()
        }
    }

    override fun close() {
        original?.close()
        active.clear()
    }
}
