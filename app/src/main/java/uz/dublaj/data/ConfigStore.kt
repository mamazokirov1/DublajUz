package uz.dublaj.data

import android.content.Context

/** Sozlamalarni saqlaydi — ilova qayta ochilganda yo'qolmaydi. */
object ConfigStore {
    private const val PREFS = "dublaj_config"

    fun load(context: Context): AppConfig {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = AppConfig()
        return AppConfig(
            originalVolume = sp.getFloat("originalVolume", d.originalVolume),
            uzbekVolume = sp.getFloat("uzbekVolume", d.uzbekVolume),
            maxAtempo = sp.getFloat("maxAtempo", d.maxAtempo),
            whisperModel = sp.getString("whisperModel", d.whisperModel) ?: d.whisperModel,
            threads = sp.getInt("threads", d.threads),
            marginS = sp.getFloat("marginS", d.marginS),
            gapS = sp.getFloat("gapS", d.gapS),
            sourceLang = sp.getString("sourceLang", d.sourceLang) ?: d.sourceLang,
            voiceNoise = sp.getFloat("voiceNoise", d.voiceNoise),
            voiceNoiseW = sp.getFloat("voiceNoiseW", d.voiceNoiseW),
            voiceSpeed = sp.getFloat("voiceSpeed", d.voiceSpeed),
            voicePause = sp.getFloat("voicePause", d.voicePause),
            voicePitch = sp.getFloat("voicePitch", d.voicePitch),
            fitScale = sp.getFloat("fitScale", d.fitScale),
            duckAmount = sp.getFloat("duckAmount", d.duckAmount),
            levelClips = sp.getBoolean("levelClips", d.levelClips),
            expertMode = sp.getBoolean("expertMode", d.expertMode),
            themeMode = sp.getInt("themeMode", d.themeMode),
            dynamicColor = sp.getBoolean("dynamicColor", d.dynamicColor),
            autoSaveGallery = sp.getBoolean("autoSaveGallery", d.autoSaveGallery)
        )
    }

    fun save(context: Context, c: AppConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("originalVolume", c.originalVolume)
            .putFloat("uzbekVolume", c.uzbekVolume)
            .putFloat("maxAtempo", c.maxAtempo)
            .putString("whisperModel", c.whisperModel)
            .putInt("threads", c.threads)
            .putFloat("marginS", c.marginS)
            .putFloat("gapS", c.gapS)
            .putString("sourceLang", c.sourceLang)
            .putFloat("voiceNoise", c.voiceNoise)
            .putFloat("voiceNoiseW", c.voiceNoiseW)
            .putFloat("voiceSpeed", c.voiceSpeed)
            .putFloat("voicePause", c.voicePause)
            .putFloat("voicePitch", c.voicePitch)
            .putFloat("fitScale", c.fitScale)
            .putFloat("duckAmount", c.duckAmount)
            .putBoolean("levelClips", c.levelClips)
            .putBoolean("expertMode", c.expertMode)
            .putInt("themeMode", c.themeMode)
            .putBoolean("dynamicColor", c.dynamicColor)
            .putBoolean("autoSaveGallery", c.autoSaveGallery)
            .apply()
    }
}
