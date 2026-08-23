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

enum class Screen { IMAGE, EDIT, VIDEO, BANK, MORE }

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
    val profileStore = remember { ProfileStore(context) }
    val replicateClient = remember { ReplicateClient { tokenStore.load() } }
    val runpodClient = remember { RunpodClient { tokenStore.loadRunPod() } }

    val initialScreen = when {
        !tokenStore.load().isNullOrBlank() -> Screen.IMAGE
        !tokenStore.loadRunPod().isNullOrBlank() -> Screen.EDIT
        else -> Screen.MORE
    }
    var screen by remember { mutableStateOf(initialScreen) }
    var moreTab by remember { mutableStateOf(if (initialScreen == Screen.MORE) "Settings" else "Prompt Lab") }
    var historyVersion by remember { mutableIntStateOf(0) }
    var profileVersion by remember { mutableIntStateOf(0) }
    var editFile by remember { mutableStateOf<File?>(null) }
    var animateFile by remember { mutableStateOf<File?>(null) }

    fun openSettings() {
        moreTab = "Settings"
        screen = Screen.MORE
    }

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
                        Screen.BANK to "Bank",
                        Screen.MORE to "More"
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
                        runpodClient = runpodClient,
                        historyStore = historyStore,
                        onHistoryChanged = { historyVersion++ },
                        onEdit = { file -> editFile = file; screen = Screen.EDIT },
                        onAnimate = { file -> animateFile = file; screen = Screen.VIDEO },
                        openSettings = ::openSettings
                    )

                    Screen.EDIT -> EditStudio(
                        runpodClient = runpodClient,
                        replicateClient = replicateClient,
                        historyStore = historyStore,
                        profileStore = profileStore,
                        profileRefreshKey = profileVersion,
                        initialFile = editFile,
                        consumeInitialFile = { editFile = null },
                        onAnimate = { file -> animateFile = file; screen = Screen.VIDEO },
                        onHistoryChanged = { historyVersion++ },
                        openSettings = ::openSettings
                    )

                    Screen.VIDEO -> VideoStudio(
                        client = replicateClient,
                        runpodClient = runpodClient,
                        historyStore = historyStore,
                        profileStore = profileStore,
                        profileRefreshKey = profileVersion,
                        initialFile = animateFile,
                        consumeInitialFile = { animateFile = null },
                        onHistoryChanged = { historyVersion++ },
                        openSettings = ::openSettings
                    )

                    Screen.BANK -> BankScreen(
                        profileStore = profileStore,
                        refreshKey = profileVersion,
                        onChanged = { profileVersion++ }
                    )

                    Screen.MORE -> MoreScreen(
                        initialTab = moreTab,
                        tokenStore = tokenStore,
                        client = replicateClient,
                        runpodClient = runpodClient,
                        historyStore = historyStore,
                        historyRefreshKey = historyVersion,
                        onEdit = { file -> editFile = file; screen = Screen.EDIT },
                        onAnimate = { file -> animateFile = file; screen = Screen.VIDEO },
                        onHistoryChanged = { historyVersion++ },
                        onDoneSettings = {
                            screen = if (!tokenStore.load().isNullOrBlank()) Screen.IMAGE else Screen.EDIT
                        }
                    )
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
