package com.forgeai.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun MoreScreen(
    initialTab: String,
    tokenStore: SecureTokenStore,
    client: ReplicateClient,
    runpodClient: RunpodClient,
    historyStore: HistoryStore,
    historyRefreshKey: Int,
    onEdit: (File) -> Unit,
    onAnimate: (File) -> Unit,
    onHistoryChanged: () -> Unit,
    onDoneSettings: () -> Unit
) {
    var tab by remember { mutableStateOf(initialTab) }
    LaunchedEffect(initialTab) { tab = initialTab }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
            OptionRow("More", listOf("Prompt Lab", "History", "Settings"), tab) { tab = it }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                "History" -> HistoryScreen(historyStore, historyRefreshKey, onEdit, onAnimate, onHistoryChanged)
                "Settings" -> SettingsScreen(tokenStore, client, runpodClient, onDoneSettings)
                else -> PromptLabScreen(runpodClient) { tab = "Settings" }
            }
        }
    }
}

@Composable
fun HistoryScreen(
    historyStore: HistoryStore,
    refreshKey: Int,
    onEdit: (File) -> Unit,
    onAnimate: (File) -> Unit,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val records = remember(refreshKey) { historyStore.load() }
    var message by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { StudioHeader("History · Generation Recipes", "Forge saves the prompt, seed, settings, and reference context with each local result.") }
        message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        if (records.isEmpty()) item { Text("No creations yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }

        items(records, key = { it.id }) { record ->
            val file = File(record.localPath)
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (record.kind == CreationKind.IMAGE) ResultImage(file) else VideoPlayer(file, compact = true)
                    Text(record.model, fontWeight = FontWeight.SemiBold)
                    Text(record.prompt, maxLines = 5, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    record.seed?.let { Text("Seed: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    if (record.settings.isNotBlank()) Text("Settings: ${record.settings}", style = MaterialTheme.typography.bodySmall)
                    if (record.referenceSummary.isNotBlank()) Text("References: ${record.referenceSummary}", style = MaterialTheme.typography.bodySmall)
                    if (record.originalPrompt.isNotBlank() && record.originalPrompt != record.prompt) {
                        Text("Original idea: ${record.originalPrompt}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.createdAt)), style = MaterialTheme.typography.labelSmall)

                    if (record.kind == CreationKind.IMAGE) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onEdit(file) }, modifier = Modifier.weight(1f)) { Text("Edit") }
                            Button(onClick = { onAnimate(file) }, modifier = Modifier.weight(1f)) { Text("Animate") }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                runCatching { MediaUtils.saveToGallery(context, file, record.kind) }
                                    .onSuccess { message = "Saved to gallery" }
                                    .onFailure { message = it.message }
                            }
                        }, modifier = Modifier.weight(1f)) { Text("Save") }
                        TextButton(onClick = { historyStore.delete(record); onChanged() }, modifier = Modifier.weight(1f)) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    tokenStore: SecureTokenStore,
    client: ReplicateClient,
    runpodClient: RunpodClient,
    done: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(tokenStore.load().orEmpty()) }
    var runpodToken by remember { mutableStateOf(tokenStore.loadRunPod().orEmpty()) }
    var show by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { StudioHeader("Settings", "API keys stay encrypted on this phone with Android Keystore.") }
        item { Text("Replicate", fontWeight = FontWeight.Bold) }
        item {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it.trim() },
                label = { Text("Replicate API token") },
                visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item { Text("RunPod", fontWeight = FontWeight.Bold) }
        item {
            OutlinedTextField(
                value = runpodToken,
                onValueChange = { runpodToken = it.trim() },
                label = { Text("RunPod API key") },
                visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item { ToggleRow("Show API keys", show) { show = it } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    tokenStore.save(token); tokenStore.saveRunPod(runpodToken); message = "API keys saved securely."
                }, modifier = Modifier.weight(1f)) { Text("Save") }
                OutlinedButton(
                    onClick = {
                        tokenStore.save(token); tokenStore.saveRunPod(runpodToken)
                        scope.launch {
                            testing = true
                            val results = mutableListOf<String>()
                            if (token.isNotBlank()) results += runCatching { "Replicate: ${client.testToken()}" }.getOrElse { "Replicate: ${it.message}" }
                            if (runpodToken.isNotBlank()) results += runCatching { "RunPod: ${runpodClient.testToken()}" }.getOrElse { "RunPod: ${it.message}" }
                            message = if (results.isEmpty()) "Add at least one API key first." else results.joinToString("\n")
                            testing = false
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.weight(1f)
                ) { Text(if (testing) "Testing…" else "Test") }
            }
        }
        message?.let { item { Text(it, color = if (it.contains("connected", true) || it.contains("saved", true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } }

        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Forge v0.4", fontWeight = FontWeight.Bold)
                    Text("Image — Qwen Image 2 with Prompt AI, 1/2/4 outputs, and reusable seeds.")
                    Text("Edit — Qwen Edit 2511 multi-reference (up to 3 images), reference roles, Character/Background Banks, and single-image RunPod Open fallback.")
                    Text("Video — WAN 2.5 Fast, WAN 2.6 Advanced (5/10/15 sec, 720p/1080p), Kling O1 multi-view character references, and LTX 2.3.")
                    Text("Prompt Lab — RunPod Qwen3 32B prompt optimization.")
                    Text("Every video prompt starts with: \"$VIDEO_PROMPT_PREFIX\"", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Character anatomy references", fontWeight = FontWeight.Bold)
                    Text("Optional adult-only anatomy references are stored in the local Character Bank and are excluded from automatic use unless explicitly enabled for a generation.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Hard boundary: never for minors and never for non-consensual intimate material.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Button(
                onClick = { tokenStore.save(token); tokenStore.saveRunPod(runpodToken); done() },
                enabled = token.isNotBlank() || runpodToken.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open Forge") }
        }
    }
}
