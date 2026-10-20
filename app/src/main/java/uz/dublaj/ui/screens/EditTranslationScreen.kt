package uz.dublaj.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uz.dublaj.ui.viewmodel.PipelineViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTranslationScreen(
    vm: PipelineViewModel,
    onBack: () -> Unit
) {
    val units by vm.translationUnits.collectAsState()
    val context = LocalContext.current

    // Ekrandan chiqishda avtomatik saqlash
    DisposableEffect(Unit) {
        onDispose { vm.saveTranslations(context) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tarjimani tahrirlash") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Orqaga")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.saveTranslations(context) }) {
                        Icon(Icons.Default.Save, contentDescription = "Saqlash")
                    }
                }
            )
        }
    ) { padding ->
        if (units.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(24.dp)) {
                Text("Tarjima hali yo'q. Avval bosh sahifada 'Transkript + tarjima' ni bajaring.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(units, key = { _, u -> u.id }) { index, unit ->
                    var text by remember(unit.id) { mutableStateOf(unit.uzText) }
                    Card(
                        colors = if (unit.check)
                            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        else CardDefaults.cardColors()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "#${unit.id}  ${"%.1f".format(unit.start)}s – ${"%.1f".format(unit.end)}s  [${unit.language}]",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (unit.check) {
                                Text(
                                    "⚠ Tarjima qilinmadi — o'zbekcha matnni o'zingiz yozing",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Text(unit.text, style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(
                                value = text,
                                onValueChange = {
                                    text = it
                                    vm.updateUzText(index, it)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("O'zbekcha") }
                            )
                        }
                    }
                }
            }
        }
    }
}
