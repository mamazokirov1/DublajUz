package uz.dublaj.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import uz.dublaj.ui.components.fmtTime
import uz.dublaj.ui.viewmodel.PipelineViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTranslationScreen(
    vm: PipelineViewModel,
    onBack: () -> Unit
) {
    val units by vm.translationUnits.collectAsState()
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val pending = units.count { it.check }

    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Tarjimani tahrirlash")
                        Text(
                            if (pending > 0) "${units.size} gap • ⚠ $pending ta tekshirilsin" else "${units.size} gap",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Orqaga")
                    }
                },
                actions = {
                    FilledTonalButton(onClick = { vm.saveTranslations(context) }) {
                        Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Saqlash")
                    }
                    Spacer(Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(units, key = { _, u -> u.id }) { index, unit ->
                var text by remember(unit.id) { mutableStateOf(unit.uzText) }
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = if (unit.check) cs.errorContainer.copy(alpha = 0.6f) else cs.surface
                    )
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = MaterialTheme.shapes.small, color = cs.primaryContainer) {
                                Text(
                                    "${fmtTime(unit.start)} – ${fmtTime(unit.end)}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = cs.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "#${unit.id}  ${unit.language}",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            if (unit.check) {
                                Icon(Icons.Default.Warning, null, tint = cs.error, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            FilledTonalIconButton(onClick = { vm.previewVoice(context, text) }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Tinglab ko'rish")
                            }
                        }
                        if (unit.check) {
                            Text(
                                "Tarjima qilinmadi, o'zbekcha matnni o'zingiz yozing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.error
                            )
                        }
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = cs.surfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                unit.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                        OutlinedTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                vm.updateUzText(index, it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            label = { Text("O'zbekcha") }
                        )
                    }
                }
            }
        }
    }
}
