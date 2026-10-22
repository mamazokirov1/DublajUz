package uz.dublaj.ui

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import uz.dublaj.ui.screens.EditTranslationScreen
import uz.dublaj.ui.screens.HistoryScreen
import uz.dublaj.ui.screens.HomeScreen
import uz.dublaj.ui.screens.PlayerScreen
import uz.dublaj.ui.screens.QueueScreen
import uz.dublaj.ui.screens.TextToolsScreen
import uz.dublaj.ui.screens.SettingsScreen
import uz.dublaj.ui.viewmodel.PipelineViewModel

object Routes {
    const val HOME = "home"
    const val EDIT = "edit"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val QUEUE = "queue"
    const val PLAYER = "player"
    const val TEXT = "text"
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

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = { slideInHorizontally(tween(280)) { it / 6 } + fadeIn(tween(280)) },
        exitTransition = { slideOutHorizontally(tween(280)) { -it / 10 } + fadeOut(tween(200)) },
        popEnterTransition = { slideInHorizontally(tween(280)) { -it / 10 } + fadeIn(tween(280)) },
        popExitTransition = { slideOutHorizontally(tween(280)) { it / 6 } + fadeOut(tween(200)) }
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                vm = vm,
                onEditTranslation = { navController.navigate(Routes.EDIT) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onHistory = { navController.navigate(Routes.HISTORY) },
                onQueue = { navController.navigate(Routes.QUEUE) },
                onTextTools = { navController.navigate(Routes.TEXT) },
                onPlay = { uri ->
                    vm.openPlayer(uri)
                    navController.navigate(Routes.PLAYER)
                }
            )
        }
        composable(Routes.EDIT) {
            EditTranslationScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable(Routes.QUEUE) {
            QueueScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable(Routes.HISTORY) {
            HistoryScreen(
                onBack = { navController.popBackStack() },
                onPlay = { f ->
                    vm.openPlayer(Uri.fromFile(f))
                    navController.navigate(Routes.PLAYER)
                }
            )
        }
        composable(Routes.PLAYER) {
            PlayerScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable(Routes.TEXT) {
            TextToolsScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(vm = vm, onBack = { navController.popBackStack() })
        }
    }
}
