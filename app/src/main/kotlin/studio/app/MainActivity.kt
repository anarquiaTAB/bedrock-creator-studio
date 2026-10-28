package studio.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import studio.app.ui.EditorScreen
import studio.app.ui.HomeScreen
import studio.app.ui.ProjectScreen
import studio.app.ui.StudioTheme
import android.net.Uri

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = (application as StudioApp).store
        setContent { StudioTheme { AppNav(store) } }
    }
}

@Composable
private fun AppNav(store: studio.core.project.ProjectStore) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home") {
        composable("home") { HomeScreen(store) { id -> nav.navigate("project/${Uri.encode(id)}") } }
        composable("project/{id}") { e ->
            val id = e.arguments?.getString("id").orEmpty()
            ProjectScreen(store, id, onBack = { nav.popBackStack() }) { file -> nav.navigate("edit/${Uri.encode(id)}/${Uri.encode(file)}") }
        }
        composable("edit/{id}/{file}") { e ->
            EditorScreen(store, e.arguments?.getString("id").orEmpty(), e.arguments?.getString("file").orEmpty()) { nav.popBackStack() }
        }
    }
}
