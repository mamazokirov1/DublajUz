package uz.dublaj.translate

import timber.log.Timber
import uz.dublaj.util.Downloader
import uz.dublaj.util.WorkDir
import java.io.File

/**
 * Xenova/nllb-200-distilled-600M fayllarini HuggingFace dan yuklaydi (~900 MB–1.2 GB).
 * Litsenziya: CC-BY-NC-4.0 (notijorat).
 */
object NllbModelDownloader {

    private const val HF =
        "https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/main"

    private val FILES = listOf(
        "config.json",
        "generation_config.json",
        "tokenizer.json",
        "tokenizer_config.json",
        "special_tokens_map.json",
        "sentencepiece.bpe.model",
        "onnx/encoder_model_quantized.onnx",
        "onnx/decoder_model_merged_quantized.onnx"
    )

    fun downloadAll(
        work: WorkDir,
        onProgress: (file: String, fileIndex: Int, fileCount: Int, bytes: Long, total: Long) -> Unit
    ): File {
        val dir = NllbTranslator.modelDir(work)
        dir.mkdirs()
        File(dir, "onnx").mkdirs()

        FILES.forEachIndexed { idx, rel ->
            val dest = File(dir, rel)
            if (dest.isFile && dest.length() > 100) {
                onProgress(rel, idx + 1, FILES.size, dest.length(), dest.length())
                return@forEachIndexed
            }
            Timber.i("NLLB yuklanmoqda: $rel")
            Downloader.download("$HF/$rel", dest, minBytes = 100) { bytes, total ->
                onProgress(rel, idx + 1, FILES.size, bytes, total)
            }
        }
        return dir
    }
}
