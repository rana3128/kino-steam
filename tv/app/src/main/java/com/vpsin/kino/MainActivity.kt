package com.vpsin.kino

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.vpsin.kino.data.ApiClient
import com.vpsin.kino.data.Entry
import com.vpsin.kino.data.ServerRef
import com.vpsin.kino.data.ServerStore
import com.vpsin.kino.ui.browse.BrowseScreen
import com.vpsin.kino.ui.connect.ConnectScreen
import com.vpsin.kino.ui.player.PlayerScreen
import com.vpsin.kino.ui.theme.KinoTheme
import kotlinx.coroutines.flow.first

/**
 * One activity, three screens. Navigation is a small sealed hierarchy rather than
 * navigation-compose: the graph is linear, and the player needs to hold a live
 * ExoPlayer instance that must not be recreated by route recomposition.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A TV has no touch to signal activity, so the screen would dim mid-film.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            KinoTheme {
                // A tv-material Surface (not a bare Box) so LocalContentColor is
                // set from the scheme; otherwise every default-coloured Text
                // renders near-black on the dark background.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    colors = SurfaceDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                    shape = RectangleShape,
                ) {
                    App()
                }
            }
        }
    }
}

private sealed interface Screen {
    data object Connect : Screen
    data class Browse(val server: ServerRef) : Screen
    data class Play(val server: ServerRef, val file: Entry) : Screen
}

@Composable
private fun App() {
    val context = LocalContext.current
    val serverStore = remember { ServerStore(context) }

    var screen by remember { mutableStateOf<Screen?>(null) }

    // Read the saved server exactly once. Collecting it as state would be wrong:
    // DataStore's flow has no value until the file has been read, so the initial
    // null would resolve the start destination to Connect every single launch and
    // the app would never auto-reconnect. `first()` waits for the real value.
    LaunchedEffect(Unit) {
        screen = serverStore.saved.first()?.let { Screen.Browse(it) } ?: Screen.Connect
    }

    when (val current = screen) {
        null -> Unit // brief DataStore read; the dark background is the splash

        is Screen.Connect -> ConnectScreen(
            onConnected = { server ->
                screen = Screen.Browse(server)
            }
        )

        is Screen.Browse -> BrowseScreen(
            server = current.server,
            api = remember(current.server) { ApiClient(current.server.baseUrl) },
            onPlay = { file -> screen = Screen.Play(current.server, file) },
            onChangeServer = { screen = Screen.Connect },
        )

        is Screen.Play -> PlayerScreen(
            api = remember(current.server) { ApiClient(current.server.baseUrl) },
            file = current.file,
            onExit = { screen = Screen.Browse(current.server) },
        )
    }
}
