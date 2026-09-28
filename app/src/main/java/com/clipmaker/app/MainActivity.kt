package com.clipmaker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.clipmaker.app.editor.ProjectSession
import com.clipmaker.app.ui.camera.CameraScreen
import com.clipmaker.app.ui.editor.EditorScreen
import com.clipmaker.app.ui.export.ExportScreen
import com.clipmaker.app.ui.home.HomeScreen
import com.clipmaker.app.ui.recorder.RecorderScreen
import com.clipmaker.app.ui.theme.ClipMakerTheme
import com.clipmaker.app.ui.theme.Palette

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ClipMakerApp).container
        setContent {
            ClipMakerTheme {
                val nav = rememberNavController()
                NavHost(
                    navController = nav,
                    startDestination = "home",
                    modifier = Modifier.fillMaxSize().background(Palette.Background),
                ) {
                    composable("home") {
                        HomeScreen(container, onOpen = { id -> nav.navigate("editor/$id") })
                    }
                    composable("editor/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                        val id = entry.arguments?.getString("id")!!
                        WithSession(container, id) { session ->
                            EditorScreen(
                                container = container,
                                session = session,
                                onBack = { nav.popBackStack() },
                                onCamera = { nav.navigate("camera/$id") },
                                onRecorder = { nav.navigate("recorder/$id") },
                                onExport = { nav.navigate("export/$id") },
                            )
                        }
                    }
                    composable("camera/{id}") { entry ->
                        WithSession(container, entry.arguments?.getString("id")!!) { session ->
                            CameraScreen(container, session, onDone = { nav.popBackStack() })
                        }
                    }
                    composable("recorder/{id}") { entry ->
                        WithSession(container, entry.arguments?.getString("id")!!) { session ->
                            RecorderScreen(container, session, onDone = { nav.popBackStack() })
                        }
                    }
                    composable("export/{id}") { entry ->
                        WithSession(container, entry.arguments?.getString("id")!!) { session ->
                            ExportScreen(container, session, onDone = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WithSession(container: AppContainer, id: String, content: @Composable (ProjectSession) -> Unit) {
    var session by remember(id) { mutableStateOf<ProjectSession?>(null) }
    LaunchedEffect(id) { session = container.session(id) }
    val s = session
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        content(s)
    }
}
