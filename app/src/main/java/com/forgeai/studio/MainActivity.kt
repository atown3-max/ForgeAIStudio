package com.forgeai.studio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

enum class Screen { IMAGE, VIDEO, OPEN, HISTORY, SETTINGS }
enum class ImageModel(val label: String) {
    QWEN2("Qwen Image 2"),
    QWEN2511("Qwen Edit 2511 · Multi-reference")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ForgeApp() }
    }
}

@Composable
fun ForgeApp() {
    val context = LocalContext.current
    val tokenStore = remember { SecureTokenStore(context) }
    val historyStore = remember { HistoryStore(context) }
    val client = remember { ReplicateClient { tokenStore.load() } }
    val runpodClient = remember { RunpodClient { tokenStore.loadRunPod() } }
    var screen by remember {
        mutableStateOf(
            when {
                !tokenStore.load().isNullOrBlank() -> Screen.IMAGE
                !tokenStore.loadRunPod().isNullOrBlank() -> Screen.OPEN
                else -> Screen.SETTINGS
            }
        )
    }
    var historyVersion by remember { mutableIntStateOf(0) }
    var animateFile by remember { mutableStateOf<File?>(null) }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF9EA7FF), background = Color(0xFF0B0D12), surface = Color(0xFF12151D))) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF10131A)) {
                    listOf(
                        Screen.IMAGE to "Image",
                        Screen.VIDEO to "Video",
                        Screen.OPEN to "Open",
                        Screen.HISTORY to "History",
                        Screen.SETTINGS to "Settings"
                    ).forEach { (target, label) ->
                        NavigationBarItem(
                            selected = screen == target,
                            onClick = { screen = target },
                            icon = { Text(label.take(1), fontWeight = FontWeight.Bold) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (screen) {
                    Screen.IMAGE -> ImageStudio(
                        client = client,
                        historyStore = historyStore,
                        onHistoryChanged = { historyVersion++ },
                        onAnimate = { file -> animateFile = file; screen = Screen.VIDEO },
                        openSettings = { screen = Screen.SETTINGS }
                    )
                    Screen.VIDEO -> VideoStudio(
                        client = client,
                        historyStore = historyStore,
                        initialFile = animateFile,
                        consumeInitialFile = { animateFile = null },
                        onHistoryChanged = { historyVersion++ },
                        openSettings = { screen = Screen.SETTINGS }
                    )
                    Screen.OPEN -> OpenStudio(
                        client = runpodClient,
                        historyStore = historyStore,
                        onHistoryChanged = { historyVersion++ },
                        openSettings = { screen = Screen.SETTINGS }
                    )
                    Screen.HISTORY -> HistoryScreen(
                        historyStore = historyStore,
                        refreshKey = historyVersion,
                        onAnimate = { file -> animateFile = file; screen = Screen.VIDEO },
                        onChanged = { historyVersion++ }
                    )
                    Screen.SETTINGS -> SettingsScreen(tokenStore, client, runpodClient) {
                        screen = if (!tokenStore.load().isNullOrBlank()) Screen.IMAGE else Screen.OPEN
                    }
                }
            }
        }
    }
}

@Composable
fun StudioHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
