package uz.dublaj.util

import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Davom ettiriladigan (Range) yuklovchi: uzilsa qayta urinadi, hajmni tekshiradi.
 * onProgress ichida istisno tashlansa (masalan bekor qilish) yuklash to'xtaydi.
 */
object Downloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    fun download(
        url: String,
        dest: File,
        minBytes: Long = 1000,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): File {
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        var attempt = 0
        while (true) {
            try {
                attemptDownload(url, part, onProgress)
                break
            } catch (e: IOException) {
                attempt++
                Timber.w(e, "Yuklash uzildi ($attempt/4): $url")
                if (attempt >= 4) throw IOException("Yuklash xatosi: ${e.message}", e)
                Thread.sleep(1500L * attempt)
            }
        }
        if (part.length() < minBytes) {
            part.delete()
            throw IllegalStateException("Fayl juda kichik (${part.length()} bayt): $url")
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        return dest
    }

    private fun attemptDownload(url: String, part: File, onProgress: (Long, Long) -> Unit) {
        val existing = if (part.isFile) part.length() else 0L
        val req = Request.Builder().url(url).get().apply {
            header("User-Agent", "DublajUz/0.3")
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()

        client.newCall(req).execute().use { resp ->
            val append: Boolean
            val base: Long
            when (resp.code) {
                206 -> { append = true; base = existing }
                200 -> { append = false; base = 0L }       // server Range'ni qo'llamadi — boshidan
                416 -> { part.delete(); throw IOException("Range rad etildi, qayta urinish") }
                in 400..499 -> throw IllegalStateException("HTTP ${resp.code}: $url")
                else -> throw IOException("HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IOException("Bo'sh javob")
            val len = body.contentLength()
            val total = if (len > 0) base + len else -1L
            var downloaded = base

            body.byteStream().use { input ->
                FileOutputStream(part, append).use { output ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                }
            }
            if (total > 0 && downloaded != total) {
                throw IOException("Fayl to'liq yuklanmadi ($downloaded/$total)")
            }
        }
    }
}
