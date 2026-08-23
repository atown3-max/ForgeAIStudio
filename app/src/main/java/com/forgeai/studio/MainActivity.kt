package com.forgeai.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File

enum class Screen { IMAGE, EDIT, VIDEO, HISTORY, SETTINGS }

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
    val replicateClient = remember { ReplicateClient { tokenStore.load() } }
    val runpodClient = remember { RunpodClient { tokenStore.loadRunPod() } }

    var screen by remember {
        mutableStateOf(
            when {
                !tokenStore.load().isNullOrBlank() -> Screen.IMAGE
                !tokenStore.loadRunPod().isNullOrBlank() -> Screen.EDIT
                else -> Screen.SETTINGS
            }
        )
    }
    var historyVersion by remember { mutableIntStateOf(0) }
    var editFile by remember { mutableStateOf<File?>(null) }
    var animateFile by remember { mutableStateOf<File?>(null) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF9EA7FF),
            background = Color(0xFF0B0D12),
            surface = Color(0xFF12151D)
        )
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF10131A)) {
                    listOf(
                        Screen.IMAGE to "Image",
                        Screen.EDIT to "Edit",
                        Screen.VIDEO to "Video",
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
                        client = replicateClient,
                        historyStore = historyStore,
                        onHistoryChanged = { historyVersion++ },
                        onEdit = { file ->
                            editFile = file
                            screen = Screen.EDIT
                        },
                        onAnimate = { file ->
                            animateFile = file
                            screen = Screen.VIDEO
                        },
                        openSettings = { screen = Screen.SETTINGS }
                    )

                    Screen.EDIT -> EditStudio(
                        runpodClient = runpodClient,
                        replicateClient = replicateClient,
                        historyStore = historyStore,
                        initialFile = editFile,
                        consumeInitialFile = { editFile = null },
                        onAnimate = { file ->
                            animateFile = file
                            screen = Screen.VIDEO
                        },
                        onHistoryChanged = { historyVersion++ },
                        openSettings = { screen = Screen.SETTINGS }
                    )

                    Screen.VIDEO -> VideoStudio(
                        client = replicateClient,
                        runpodClient = runpodClient,
                        historyStore = historyStore,
                        initialFile = animateFile,
                        consumeInitialFile = { animateFile = null },
                        onHistoryChanged = { historyVersion++ },
                        openSettings = { screen = Screen.SETTINGS }
                    )

                    Screen.HISTORY -> HistoryScreen(
                        historyStore = historyStore,
                        refreshKey = historyVersion,
                        onEdit = { file ->
                            editFile = file
                            screen = Screen.EDIT
                        },
                        onAnimate = { file ->
                            animateFile = file
                            screen = Screen.VIDEO
                        },
                        onChanged = { historyVersion++ }
                    )

                    Screen.SETTINGS -> SettingsScreen(
                        tokenStore = tokenStore,
                        client = replicateClient,
                        runpodClient = runpodClient
                    ) {
                        screen = if (!tokenStore.load().isNullOrBlank()) Screen.IMAGE else Screen.EDIT
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
