package uz.dublaj.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Qisqa PCM16 mono audioni (ovoz namunasi) chalish. */
object PcmPlayer {
    @Volatile private var track: AudioTrack? = null

    suspend fun play(pcm: ShortArray, sampleRate: Int) = withContext(Dispatchers.IO) {
        stop()
        if (pcm.isEmpty()) return@withContext
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track = t
        try {
            t.write(pcm, 0, pcm.size)
            t.play()
            while (isActive && t.playState == AudioTrack.PLAYSTATE_PLAYING && t.playbackHeadPosition < pcm.size) {
                delay(50)
            }
        } finally {
            try { t.stop() } catch (_: Exception) {}
            try { t.release() } catch (_: Exception) {}
            if (track === t) track = null
        }
    }

    fun stop() {
        try { track?.stop() } catch (_: Exception) {}
    }
}
