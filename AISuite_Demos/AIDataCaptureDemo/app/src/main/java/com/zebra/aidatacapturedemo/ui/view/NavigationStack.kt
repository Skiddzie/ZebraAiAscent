package com.zebra.aidatacapturedemo.ui.view

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.zebra.aidatacapturedemo.viewmodel.AIDataCaptureDemoViewModel
import com.zebra.aidatacapturedemo.indexcreator.IndexCreatorScreen
import com.zebra.aidatacapturedemo.model.FileUtils
import java.io.File
import java.nio.file.Paths
import androidx.core.net.toUri

sealed class Screen(val route: String) {
    object Start : Screen("start_screen")
    object DemoStart : Screen("demo_start_screen")
    object DemoSetting : Screen("demo_setting_screen")
    object DemoSettingMore : Screen("demo_setting_more_screen")
    object AdvancedOCRSettings : Screen("advanced_ocr_setting_screen")
    object Preview : Screen("preview_screen")
    object Capture : Screen("capture_screen")
    object IndexCreator : Screen("index_creator_screen")
}

@Composable
fun NavigationStack(
    navController: NavHostController,
    viewModel: AIDataCaptureDemoViewModel,
    activityInnerPadding: PaddingValues,
    innerPadding: PaddingValues,
    context: Context,
    activityLifecycle: Lifecycle
) {

    NavHost(navController = navController, startDestination = Screen.Start.route) {
        composable(route = Screen.Start.route) {
            viewModel.updateActiveScreenData(activeScreen = Screen.Start)
            AIDataCaptureStartScreen(viewModel, navController = navController, innerPadding)
        }
        composable(route = Screen.DemoStart.route) {
            viewModel.updateActiveScreenData(activeScreen = Screen.DemoStart)
            DemoStartScreen(viewModel, navController = navController, innerPadding, context = context)
        }
        composable(route = Screen.DemoSetting.route) {
            viewModel.updateActiveScreenData(activeScreen = Screen.DemoSetting)
            DemoSettingsScreen(viewModel, navController = navController, innerPadding)
        }
        composable(route = Screen.AdvancedOCRSettings.route) {
            viewModel.updateActiveScreenData(activeScreen = Screen.AdvancedOCRSettings)
            AdvancedOCRSettingsScreen(
                viewModel,
                navController = navController,
                innerPadding,
                context = context
            )
        }
        composable(route = Screen.Preview.route) {
            viewModel.updateActiveScreenData(activeScreen = Screen.Preview)
            CameraPreviewScreen(
                viewModel,
                navController = navController,
                context,
                activityInnerPadding,
                activityLifecycle
            )
        }
        composable(route = Screen.Capture.route) {
            viewModel.updateActiveScreenData(activeScreen = Screen.Capture)
            CameraCapturedImageScreen(
                viewModel,
                navController = navController,
                innerPadding,
                context = context
            )
        }
        composable(route = Screen.IndexCreator.route) {
            viewModel.updateActiveScreenData(activeScreen = Screen.IndexCreator)
            IndexCreatorScreen(
                onNavigateUp = { navController.popBackStack() },
                onIndexReady = { dbPath ->
                    val dbFile = java.io.File(dbPath)
                    viewModel.loadProductIndex(dbFile.toUri())
                }
            )
        }
    }
}

