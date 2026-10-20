package uz.dublaj.util

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * APK ichidagi assets/models/ dan modellarni birinchi ishga tushirishda
 * filesDir/dublaj/models/ ga nusxalaydi.
 *
 * Katta fayllar (NLLB ~1 GB) uchun tavsiya etilmaydi — APK hajmi va Play limit.
 * Kichik/o'rtacha: VITS, whisper tiny/base, lexicon — assets ga solish mumkin.
 *
 * Joylashuv APK ichida:
 *   app/src/main/assets/models/vits_uzbek.onnx
 *   app/src/main/assets/models/uz_tts_base.json
 *   app/src/main/assets/models/ggml-base.bin
 *   app/src/main/assets/models/nllb/...   (faqat kerak bo'lsa)
 */
object AssetModelInstaller {

    /**
     * assets/models/** dagi hamma fayllarni WorkDir.models ga nusxalaydi (mavjud bo'lsa o'tkazib yuboradi).
     * @return nusxa qilingan fayllar soni
     */
    fun installIfNeeded(context: Context, work: WorkDir): Int {
        var count = 0
        try {
            count += copyAssetTree(context, "models", work.models)
        } catch (e: Exception) {
            Timber.w(e, "Asset models install")
        }
        return count
    }

    private fun copyAssetTree(context: Context, assetPath: String, destDir: File): Int {
        val am = context.assets
        val list = try {
            am.list(assetPath) ?: return 0
        } catch (_: Exception) {
            return 0
        }
        if (list.isEmpty()) {
            // leaf file? try open
            return 0
        }
        var n = 0
        destDir.mkdirs()
        for (name in list) {
            val childAsset = if (assetPath.isEmpty()) name else "$assetPath/$name"
            val children = am.list(childAsset)
            if (children != null && children.isNotEmpty()) {
                n += copyAssetTree(context, childAsset, File(destDir, name))
            } else {
                val dest = File(destDir, name)
                if (dest.isFile && dest.length() > 1000) continue
                try {
                    am.open(childAsset).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    Timber.i("Asset → ${dest.absolutePath} (${dest.length() / 1024} KB)")
                    n++
                } catch (e: Exception) {
                    Timber.w(e, "copy $childAsset")
                }
            }
        }
        return n
    }
}
