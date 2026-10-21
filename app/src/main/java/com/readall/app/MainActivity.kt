package com.readall.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.readall.app.playback.ReadingPlaybackService
import com.readall.app.ui.library.LibraryScreen
import com.readall.app.ui.library.LibraryViewModel
import com.readall.app.ui.reader.ReaderScreen
import com.readall.app.ui.reader.ReaderViewModel
import com.readall.app.ui.theme.ReadAllTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val openDocumentId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDocumentId.value = intent.getStringExtra(ReadingPlaybackService.EXTRA_DOCUMENT_ID)
        enableEdgeToEdge()
        setContent {
            ReadAllTheme {
                val documentId by openDocumentId.collectAsStateWithLifecycle()
                ReadAllNav(initialDocumentId = documentId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openDocumentId.value = intent.getStringExtra(ReadingPlaybackService.EXTRA_DOCUMENT_ID)
    }
}

@Composable
private fun ReadAllNav(initialDocumentId: String?) {
    val navController = rememberNavController()
    val application = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application

    LaunchedEffect(initialDocumentId) {
        if (!initialDocumentId.isNullOrBlank()) {
            navController.navigate("reader/$initialDocumentId") {
                launchSingleTop = true
            }
        }
    }

    NavHost(navController = navController, startDestination = "library") {
        composable("library") {
            val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory(application))
            LibraryScreen(
                viewModel = viewModel,
                onOpenDocument = { id -> navController.navigate("reader/$id") }
            )
        }
        composable(
            route = "reader/{documentId}",
            arguments = listOf(navArgument("documentId") { type = NavType.StringType })
        ) { entry ->
            val documentId = entry.arguments?.getString("documentId").orEmpty()
            val viewModel: ReaderViewModel = viewModel(
                factory = ReaderViewModel.Factory(application, documentId)
            )
            ReaderScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
