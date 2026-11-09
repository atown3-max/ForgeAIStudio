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
    val context = LocalContext.current
    val recastStore = remember { RecastStore(context) }
    val recastClient = remember { RecastClient(recastStore) { tokenStore.loadRecast() } }
    var token by remember { mutableStateOf(tokenStore.load().orEmpty()) }
    var runpodToken by remember { mutableStateOf(tokenStore.loadRunPod().orEmpty()) }
    var recastUrl by remember { mutableStateOf(recastStore.endpoint()) }
    var recastToken by remember { mutableStateOf(tokenStore.loadRecast().orEmpty()) }
    var serverlessRecast by remember { mutableStateOf(recastUrl.isBlank() || isRunpodRecast(recastUrl)) }
    var show by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    fun saveSettings(): Boolean = try {
        val endpoint = if (recastUrl.isBlank()) "" else recastEndpoint(recastUrl)
        require(endpoint.isBlank() || isRunpodRecast(endpoint) == serverlessRecast) { "Choose the matching Recast connection type." }
        val active = recastStore.pending()
        require(active == null || endpoint == active.endpoint) { "A Recast job is saved for ${active?.endpoint}. Finish that job before changing workers." }
        require((endpoint.isBlank() && recastToken.isBlank()) || (endpoint.isNotBlank() &&
            recastToken.length >= 32 && recastToken.none { it.isWhitespace() })) {
            if (serverlessRecast) "Add the endpoint ID and its RunPod API key. Paste the key only, without 'Bearer'."
            else "Recast needs both an HTTPS worker address and its token (at least 32 characters)."
        }
        tokenStore.save(token); tokenStore.saveRunPod(runpodToken)
        tokenStore.saveRecast(recastToken); recastStore.saveEndpoint(endpoint)
        recastUrl = endpoint
        true
    } catch (error: Exception) { message = error.message; false }

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
        item { Text("Recast · Wan Animate", fontWeight = FontWeight.Bold) }
        item { OptionRow("Connection", listOf("RunPod Serverless", "Forge worker"),
            if (serverlessRecast) "RunPod Serverless" else "Forge worker") { choice ->
            val next = choice == "RunPod Serverless"
            if (next != serverlessRecast) {
                if (recastStore.pending() != null) message = "Finish or forget the saved Recast job before changing connections."
                else { serverlessRecast = next; recastUrl = ""; recastToken = "" }
            }
        } }
        item { Text(if (serverlessRecast)
            "For the Wan Animate RunPod Hub template: enter your endpoint ID or URL, then a RunPod API key with access to that endpoint. Paste only the ID or URL, not a cURL command."
            else "Enter your Forge worker's HTTPS address and its separate worker token. Do not use a RunPod account key for this connection.",
            style = MaterialTheme.typography.bodySmall) }
        item { OutlinedTextField(value = recastUrl, onValueChange = { recastUrl = it.trim() },
            label = { Text(if (serverlessRecast) "Recast endpoint ID or URL" else "Recast worker HTTPS address") },
            singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(value = recastToken, onValueChange = { recastToken = it.trim() },
            label = { Text(if (serverlessRecast) "RunPod API key for Recast" else "Recast worker token") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation()) }
        item { OutlinedButton(enabled = !testing && recastUrl.isNotBlank(), onClick = {
            if (saveSettings()) scope.launch {
                testing = true
                try { message = recastClient.health(recastUrl) }
                catch (error: Exception) { message = "Recast: ${error.message}" }
                finally { testing = false }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (testing) "Testing…" else "Test Recast connection") } }
        item { ToggleRow("Show API keys", show) { show = it } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    if (saveSettings()) message = "API keys saved securely."
                }, modifier = Modifier.weight(1f)) { Text("Save") }
                OutlinedButton(
                    onClick = {
                        if (saveSettings()) scope.launch {
                            testing = true
                            val results = mutableListOf<String>()
                            if (token.isNotBlank()) results += runCatching { "Replicate: ${client.testToken()}" }.getOrElse { "Replicate: ${it.message}" }
                            if (runpodToken.isNotBlank()) results += runCatching { "RunPod: ${runpodClient.testToken()}" }.getOrElse { "RunPod: ${it.message}" }
                            if (recastUrl.isNotBlank()) results += runCatching { recastClient.health(recastUrl) }.getOrElse { "Recast: ${it.message}" }
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
                    Text("Forge v0.5.1 · RunPod Recast preview", fontWeight = FontWeight.Bold)
                    Text("Image — Qwen Image 2 with Prompt AI, 1/2/4 outputs, and reusable seeds.")
                    Text("Edit — Qwen Edit 2511 multi-reference (up to 3 images), reference roles, Character/Background Banks, and single-image RunPod Open fallback.")
                    Text("Video — WAN 2.5 Fast, WAN 2.6 Advanced (5/10/15 sec, 720p/1080p), Kling O1 multi-view character references, and LTX 2.3.")
                    Text("Recast — upload a single-person clip and character image; Wan2.2-Animate-14B replacement with original audio.")
                    Text("Prompt Lab — RunPod Qwen3 32B prompt optimization.")
                    Text("Image-to-video prompts start with: \"$VIDEO_PROMPT_PREFIX\"", color = MaterialTheme.colorScheme.primary)
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
                onClick = { if (saveSettings()) done() },
                enabled = token.isNotBlank() || runpodToken.isNotBlank() || recastUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open Forge") }
        }
    }
}
