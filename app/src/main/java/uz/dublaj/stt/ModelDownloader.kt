package uz.dublaj.stt

import timber.log.Timber
import uz.dublaj.util.Downloader
import java.io.File

/**
 * HuggingFace dan ggml Whisper modellarini yuklaydi (uzilsa davom ettiradi).
 * Birinchi marta internet kerak; keyin offline.
 */
object ModelDownloader {

    private fun hfUrl(fileName: String): String =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"

    fun download(
        dest: File,
        fileName: String = dest.name,
        minBytes: Long = 1_000_000,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): File {
        if (isDownloaded(dest, minBytes)) {
            Timber.i("Model allaqachon bor: ${dest.absolutePath}")
            return dest
        }
        Timber.i("Yuklanmoqda: ${hfUrl(fileName)} → $dest")
        Downloader.download(hfUrl(fileName), dest, minBytes, onProgress)
        Timber.i("Model tayyor: ${dest.length() / 1024 / 1024} MB")
        return dest
    }

    fun isDownloaded(dest: File, minBytes: Long = 1_000_000): Boolean =
        dest.isFile && dest.length() >= minBytes
}
