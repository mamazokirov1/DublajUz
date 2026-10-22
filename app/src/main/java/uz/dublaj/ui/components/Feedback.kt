package uz.dublaj.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import uz.dublaj.data.PipelineState
import uz.dublaj.ui.viewmodel.PipelineViewModel

/** ViewModel xabarlarini (tozalandi, bekor qilindi, tayyor…) Snackbar sifatida ko'rsatadi. */
@Composable
fun CollectUiEvents(vm: PipelineViewModel, host: SnackbarHostState) {
    LaunchedEffect(vm, host) {
        vm.uiEvents.collect { msg ->
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }
}

/** Ishlayotgan jarayon kartasi: silliq progress, "Bekor qilinmoqda…" holati bilan. */
@Composable
fun RunningBanner(s: PipelineState.Running, cancelling: Boolean, onCancel: () -> Unit) {
    val progress by animateFloatAsState(s.progress.coerceIn(0f, 1f), animationSpec = tween(400), label = "progress")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(
        initialValue = 1f, targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulseA"
    )
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(
                Brush.linearGradient(
                    if (cancelling) listOf(Color(0xFF6B6B6B), Color(0xFF8A6A4A))
                    else listOf(Color(0xFF4A2FD0), Color(0xFF7B4DFF), Color(0xFF00A6A6))
                )
            )
            .animateContentSize()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(
                    targetState = if (cancelling) "Bekor qilinmoqda" else s.stage,
                    transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
                    modifier = Modifier.weight(1f),
                    label = "stage"
                ) { t ->
                    Text(
                        "$t…", color = Color.White, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.alpha(if (cancelling) a else 1f)
                    )
                }
                if (!cancelling) {
                    Text(
                        "${(progress * 100).toInt()}%", color = Color.White,
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            }
            if (cancelling) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
                    color = Color.White, trackColor = Color.White.copy(alpha = 0.25f)
                )
                Text(
                    "Hozirgi qadam tugashi bilan jarayon to'xtaydi. Bu bir necha soniya olishi mumkin.",
                    color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall
                )
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
                    color = Color.White, trackColor = Color.White.copy(alpha = 0.25f)
                )
                Text(s.message, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                Text(
                    "Ilovani yopmang, u fonda ishlayveradi (bildirishnoma ko'rinadi).",
                    color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onCancel, enabled = !cancelling) {
                Text(if (cancelling) "Bekor qilinmoqda…" else "Bekor qilish", color = Color.White.copy(alpha = if (cancelling) 0.6f else 1f))
            }
        }
    }
}
