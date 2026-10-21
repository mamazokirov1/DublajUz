package uz.dublaj.util

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GitHub Releases orqali yangilanishni tekshiradi.
 * Reliz teg'i "build-<raqam>" ko'rinishida, raqam ilovaning versionCode'i bilan solishtiriladi.
 * Repo ochiq (public) bo'lishi kerak.
 */
object UpdateChecker {
    private const val REPO = "mamazokirov1/DublajUz"
    private const val PREFS = "dublaj_update"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    sealed class Result {
        data class Available(val build: Int, val versionName: String, val url: String, val notes: String) : Result()
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
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 404) {
                    return Result.Failed("Reliz topilmadi (hali reliz chiqmagan yoki repo yopiq)")
                }
                if (!resp.isSuccessful) return Result.Failed("GitHub javobi: ${resp.code}")
                val json = JSONObject(resp.body?.string() ?: return Result.Failed("Bo'sh javob"))
                val tag = json.optString("tag_name")
                val build = Regex("(\\d+)$").find(tag)?.value?.toIntOrNull()
                    ?: return Result.Failed("Versiya o'qilmadi: $tag")
                var url = json.optString("html_url")
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) {
                            url = a.optString("browser_download_url")
                            break
                        }
                    }
                }
                if (build > currentBuild(context)) {
                    Result.Available(build, json.optString("name").ifBlank { tag }, url, json.optString("body"))
                } else {
                    Result.UpToDate
                }
            }
        } catch (e: Exception) {
            Result.Failed("Internet yo'q yoki GitHub'ga ulanib bo'lmadi")
        }
    }
}
