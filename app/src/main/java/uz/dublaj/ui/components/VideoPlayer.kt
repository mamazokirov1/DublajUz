package uz.dublaj.ui.components

import android.net.Uri
import android.widget.VideoView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Ilova ichidagi video pleyer: ijro/pauza, surish (seek), vaqt.
 * Dublyaj qilinadigan videoni ham, tayyor natijani ham shu yerda ko'rish mumkin.
 */
@Composable
fun VideoPlayer(uri: Uri, modifier: Modifier = Modifier, autoPlay: Boolean = false) {
    key(uri) { PlayerBody(uri, modifier, autoPlay) }
}

@Composable
private fun PlayerBody(uri: Uri, modifier: Modifier, autoPlay: Boolean) {
    val cs = MaterialTheme.colorScheme
    var view by remember { mutableStateOf<VideoView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(0) }
    var pos by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }

    LaunchedEffect(ready, playing) {
        val v = view ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        try {
            if (playing && !v.isPlaying) v.start()
            else if (!playing && v.isPlaying) v.pause()
        } catch (_: Exception) {
        }
    }
    LaunchedEffect(ready) {
        while (ready) {
            val v = view
            if (v != null && !dragging) {
                try { pos = v.currentPosition } catch (_: Exception) { }
            }
            delay(250)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            try { view?.stopPlayback() } catch (_: Exception) { }
        }
    }

    Column(modifier.clip(MaterialTheme.shapes.medium).background(Color.Black)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(aspect),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setOnPreparedListener { mp ->
                            duration = mp.duration
                            if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                                aspect = (mp.videoWidth.toFloat() / mp.videoHeight).coerceIn(0.5f, 2.4f)
                            }
                            ready = true
                            seekTo(1)                    // birinchi kadr ko'rinib turishi uchun
                            if (autoPlay) playing = true
                        }
                        setOnCompletionListener {
                            playing = false
                            pos = 0
                            seekTo(1)
                        }
                        setOnErrorListener { _, _, _ ->
                            failed = true
                            true
                        }
                        setVideoURI(uri)
                        view = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier.fillMaxSize().clickable(enabled = ready) { playing = !playing },
                contentAlignment = Alignment.Center
            ) {
                val iconAlpha by animateFloatAsState(
                    if (ready && !playing) 1f else 0f, animationSpec = tween(220), label = "playIcon"
                )
                if (iconAlpha > 0.01f) {
                    Surface(
                        shape = CircleShape, color = Color.Black.copy(alpha = 0.55f),
                        modifier = Modifier.graphicsLayer {
                            alpha = iconAlpha
                            scaleX = 0.8f + 0.2f * iconAlpha
                            scaleY = 0.8f + 0.2f * iconAlpha
                        }
                    ) {
                        Icon(
                            Icons.Default.PlayArrow, contentDescription = "Ijro etish", tint = Color.White,
                            modifier = Modifier.padding(12.dp).size(44.dp)
                        )
                    }
                }
            }
            if (!ready && !failed) CircularProgressIndicator(color = Color.White)
            if (failed) {
                Text(
                    "Bu videoni ilova ichida ochib bo'lmadi",
                    color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().background(cs.surfaceVariant).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { playing = !playing }, enabled = ready) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "To'xtatish" else "Ijro etish"
                )
            }
            val maxV = max(duration, 1).toFloat()
            Slider(
                value = pos.toFloat().coerceIn(0f, maxV),
                onValueChange = {
                    dragging = true
                    pos = it.toInt()
                },
                onValueChangeFinished = {
                    try { view?.seekTo(pos) } catch (_: Exception) { }
                    dragging = false
                },
                valueRange = 0f..maxV,
                enabled = ready,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${fmtDuration(pos.toLong())} / ${fmtDuration(duration.toLong())}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(end = 10.dp, start = 4.dp)
            )
        }
    }
}
