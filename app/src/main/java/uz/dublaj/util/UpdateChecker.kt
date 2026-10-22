package uz.dublaj.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import uz.dublaj.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * GitHub Releases orqali yangilanishni tekshiradi, APK'ni ilova ichida yuklaydi va o'rnatishni boshlaydi.
 * Reliz teg'i "build-<raqam>" ko'rinishida, raqam ilovaning versionCode'i bilan solishtiriladi.
 * Repo nomi build vaqtida CI'dan olinadi (BuildConfig.GITHUB_REPO). Repo ochiq (public) bo'lishi kerak.
 */
object UpdateChecker {
    private val REPO: String = BuildConfig.GITHUB_REPO
    private const val PREFS = "dublaj_update"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    sealed class Result {
        data class Available(
            val build: Int,
            val versionName: String,
            val url: String,
            val notes: String,
            /** APK hajmi (bayt), 0 = noma'lum */
            val size: Long = 0L
        ) : Result()
        data object UpToDate : Result()
        data class Failed(val message: String) : Result()
    }

    fun currentBuild(context: Context): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    } catch (_: Exception) {
        0L
    }

    fun currentVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    fun shouldAutoCheck(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last", 0L)
        return System.currentTimeMillis() - last > DAY_MS
    }

    fun markChecked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("last", System.currentTimeMillis()).apply()
    }

    fun isSkipped(context: Context, build: Int) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("skip", 0) == build

    fun skip(context: Context, build: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("skip", build).apply()
    }

    /** Bloklovchi (tarmoq): IO oqimida chaqiring. */
    fun check(context: Context): Result {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "DublajUz-Updater")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 404) {
                    Timber.w("Yangilanish: 404 repo=$REPO")
                    return Result.Failed("Reliz topilmadi: $REPO (reliz chiqmagan yoki repo yopiq/private)")
                }
                if (!resp.isSuccessful) {
                    Timber.w("Yangilanish: HTTP ${resp.code} repo=$REPO")
                    return Result.Failed("GitHub javobi: ${resp.code}")
                }
                val json = JSONObject(resp.body?.string() ?: return Result.Failed("Bo'sh javob"))
                val tag = json.optString("tag_name")
                val build = Regex("(\\d+)$").find(tag)?.value?.toIntOrNull()
                    ?: return Result.Failed("Versiya o'qilmadi: $tag")
                var url = ""
                var size = 0L
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) {
                            url = a.optString("browser_download_url")
                            size = a.optLong("size", 0L)
                            break
                        }
                    }
                }
                val cur = currentBuild(context)
                Timber.i("Yangilanish: repo=$REPO tag=$tag joriy=$cur")
                if (build > cur) {
                    if (url.isBlank()) return Result.Failed("Relizda APK fayl yo'q ($tag)")
                    Result.Available(build, json.optString("name").ifBlank { tag }, url, json.optString("body"), size)
                } else {
                    Result.UpToDate
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Yangilanish tekshiruvi xatosi")
            Result.Failed("Internet yo'q yoki GitHub'ga ulanib bo'lmadi (${e.javaClass.simpleName})")
        }
    }

    // ------------------------------------------------------------------ yuklash va o'rnatish

    private fun updatesDir(context: Context) = File(context.cacheDir, "updates")

    /**
     * APK'ni ilova ichida yuklaydi (uzilsa davom ettiradi). To'liq fayl allaqachon bor bo'lsa, qayta yuklamaydi.
     * Bloklovchi: IO oqimida chaqiring.
     */
    fun download(context: Context, u: Result.Available, onProgress: (Float) -> Unit): File {
        val dir = updatesDir(context)
        val dest = File(dir, "DublajUz-${u.build}.apk")
        // eski versiyalarning qoldiq fayllarini tozalash
        dir.listFiles()?.filter { it.name != dest.name && it.name != dest.name + ".part" }?.forEach { it.delete() }
        if (dest.isFile && u.size > 0 && dest.length() == u.size) return dest
        Downloader.download(u.url, dest, minBytes = 1_000_000L) { done, total ->
            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
        }
        return dest
    }

    /**
     * Tizim o'rnatuvchisini ochadi.
     * @return false = "Noma'lum manbalardan o'rnatish" ruxsati kerak (sozlama ekrani ochildi).
     */
    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
