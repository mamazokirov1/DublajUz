package uz.dublaj.tts

import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Chatterbox (o'zbekcha fine-tune) serveriga HTTP mijoz. Model telefonda ishlamaydi —
 * server (Colab/GPU) alohida ishga tushiriladi. Qaytgan audio [VitsSynthesizer.SegmentAudio]
 * ko'rinishida beriladi, shuning uchun qolgan pipeline (joylashtirish, aralashtirish) o'zgarmaydi.
 */
class ChatterboxClient(baseUrl: String, private val token: String) {

    class ChatterboxException(message: String, val fatal: Boolean = false) : Exception(message)

    private val base: String = run {
        var u = baseUrl.trim().trimEnd('/')
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        u
    }

    fun health(): String = try {
        val conn = open("/health", "GET", 15_000, 20_000)
        try {
            conn.setRequestProperty("X-Api-Key", token.trim())
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            when {
                code in 200..299 -> {
                    val dev = Regex("\"device\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: "?"
                    "Server ishlayapti ✓ ($dev)"
                }
                code == 401 || code == 403 -> "Token noto'g'ri (HTTP $code)"
                else -> "Server javobi: HTTP $code"
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        "Serverga ulanib bo'lmadi: ${e.message}"
    }

    /** [ref] — klonlanadigan namuna ovoz (null bo'lsa serverdagi standart ovoz). */
    fun synthesize(text: String, ref: File?, exaggeration: Float = 0.5f): VitsSynthesizer.SegmentAudio {
        var last: ChatterboxException? = null
        for (attempt in 1..2) {
            try {
                val bytes = postTts(text, ref, exaggeration)
                val (pcm, sr) = parseWavPcm16(bytes)
                if (pcm.isEmpty()) throw ChatterboxException("Server bo'sh audio qaytardi")
                return VitsSynthesizer.SegmentAudio(pcm, sr, 1f)
            } catch (e: ChatterboxException) {
                if (e.fatal) throw e
                last = e
                Timber.w("Chatterbox urinish $attempt/2: ${e.message}")
                if (attempt < 2) Thread.sleep(1_500)
            }
        }
        throw last ?: ChatterboxException("Noma'lum xato")
    }

    // ------------------------------------------------------------------ ichki

    private fun open(path: String, method: String, connectMs: Int, readMs: Int): HttpURLConnection {
        val conn = try {
            URL(base + path).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw ChatterboxException("Server manzili noto'g'ri: ${e.message}", true)
        }
        conn.requestMethod = method
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        return conn
    }

    private fun postTts(text: String, ref: File?, exaggeration: Float): ByteArray {
        val boundary = "----dublaj" + System.nanoTime()
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
            body.write(value.toByteArray(Charsets.UTF_8))
            body.write("\r\n".toByteArray(Charsets.UTF_8))
        }
        field("text", text)
        field("exaggeration", exaggeration.toString())
        if (ref != null && ref.isFile) {
            body.write(
                ("--$boundary\r\nContent-Disposition: form-data; name=\"ref\"; filename=\"${ref.name}\"\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n").toByteArray(Charsets.UTF_8)
            )
            body.write(ref.readBytes())
            body.write("\r\n".toByteArray(Charsets.UTF_8))
        }
        body.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))

        val conn = open("/tts", "POST", 20_000, 240_000)
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("X-Api-Key", token.trim())
            conn.setFixedLengthStreamingMode(body.size())
            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            if (code in 200..299) {
                return conn.inputStream.use { it.readBytes() }
            }
            val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.take(200) ?: ""
            val fatal = code == 401 || code == 403 || code == 404
            val hint = when (code) {
                401, 403 -> "token noto'g'ri"
                404 -> "manzil noto'g'ri"
                413 -> "namuna ovoz juda katta"
                else -> "server xatosi"
            }
            throw ChatterboxException("HTTP $code — $hint${if (err.isNotEmpty()) ": $err" else ""}", fatal)
        } catch (e: ChatterboxException) {
            throw e
        } catch (e: IOException) {
            throw ChatterboxException("Aloqa xatosi: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** PCM16 WAV ni o'qiydi (RIFF chunklarini aylanib o'tadi). Ko'p kanalli bo'lsa birinchi kanalni oladi. */
        fun parseWavPcm16(b: ByteArray): Pair<ShortArray, Int> {
            if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") {
                throw ChatterboxException("Server WAV qaytarmadi")
            }
            fun le16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
            fun le32(o: Int) = le16(o) or (le16(o + 2) shl 16)
            var pos = 12
            var channels = 1
            var sr = 24000
            var bits = 16
            while (pos + 8 <= b.size) {
                val id = String(b, pos, 4, Charsets.US_ASCII)
                var size = le32(pos + 4)
                val dataStart = pos + 8
                if (id == "fmt ") {
                    channels = le16(dataStart + 2).coerceAtLeast(1)
                    sr = le32(dataStart + 4)
                    bits = le16(dataStart + 14)
                } else if (id == "data") {
                    if (bits != 16) throw ChatterboxException("WAV 16-bit emas ($bits)")
                    if (size < 0 || dataStart + size > b.size) size = b.size - dataStart
                    val frames = size / (2 * channels)
                    val out = ShortArray(frames)
                    for (i in 0 until frames) {
                        val o = dataStart + i * 2 * channels
                        out[i] = le16(o).toShort()
                    }
                    return Pair(out, sr)
                }
                pos = dataStart + size + (size and 1)
            }
            throw ChatterboxException("WAV ichida audio topilmadi")
        }
    }
}
