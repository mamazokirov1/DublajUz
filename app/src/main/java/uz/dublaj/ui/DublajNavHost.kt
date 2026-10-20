package uz.dublaj.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import uz.dublaj.ui.screens.EditTranslationScreen
import uz.dublaj.ui.screens.HomeScreen
import uz.dublaj.ui.screens.SettingsScreen
import uz.dublaj.ui.viewmodel.PipelineViewModel

object Routes {
    const val HOME = "home"
    const val EDIT = "edit"
    const val SETTINGS = "settings"
}

@Composable
fun DublajNavHost(incomingUri: Uri? = null, onIncomingConsumed: () -> Unit = {}) {
    val navController = rememberNavController()
    // NavHost'dan TASHQARIDA yaratiladi -> Activity'ga bog'langan, barcha ekranlar bitta nusxani ulashadi
    val vm: PipelineViewModel = viewModel()

    LaunchedEffect(incomingUri) {
        if (incomingUri != null) {
            vm.setVideo(incomingUri)
            onIncomingConsumed()
        }
    }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                vm = vm,
                onEditTranslation = { navController.navigate(Routes.EDIT) },
                onSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.EDIT) {
            EditTranslationScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(vm = vm, onBack = { navController.popBackStack() })
        }
    }
}
