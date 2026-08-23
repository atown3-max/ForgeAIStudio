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
        item {
            StudioHeader(
                "History",
                "Completed generations are cached locally because provider output links expire."
            )
        }
        message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        if (records.isEmpty()) {
            item { Text("No creations yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        items(records, key = { it.id }) { record ->
            val file = File(record.localPath)
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (record.kind == CreationKind.IMAGE) ResultImage(file) else VideoPlayer(file, compact = true)
                    Text(record.model, fontWeight = FontWeight.SemiBold)
                    Text(record.prompt, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.createdAt)),
                        style = MaterialTheme.typography.labelSmall
                    )

                    if (record.kind == CreationKind.IMAGE) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onEdit(file) }, modifier = Modifier.weight(1f)) { Text("Edit") }
                            Button(onClick = { onAnimate(file) }, modifier = Modifier.weight(1f)) { Text("Animate") }
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCatching { MediaUtils.saveToGallery(context, file, record.kind) }
                                        .onSuccess { message = "Saved to gallery" }
                                        .onFailure { message = it.message }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Save") }
                        TextButton(
                            onClick = {
                                historyStore.delete(record)
                                onChanged()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Delete") }
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
                Button(
                    onClick = {
                        tokenStore.save(token)
                        tokenStore.saveRunPod(runpodToken)
                        message = "API keys saved securely."
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Save") }

                OutlinedButton(
                    onClick = {
                        tokenStore.save(token)
                        tokenStore.saveRunPod(runpodToken)
                        scope.launch {
                            testing = true
                            val results = mutableListOf<String>()
                            if (token.isNotBlank()) {
                                results += runCatching { "Replicate: ${client.testToken()}" }
                                    .getOrElse { "Replicate: ${it.message}" }
                            }
                            if (runpodToken.isNotBlank()) {
                                results += runCatching { "RunPod: ${runpodClient.testToken()}" }
                                    .getOrElse { "RunPod: ${it.message}" }
                            }
                            message = if (results.isEmpty()) "Add at least one API key first." else results.joinToString("\n")
                            testing = false
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.weight(1f)
                ) { Text(if (testing) "Testing…" else "Test") }
            }
        }

        message?.let {
            item {
                Text(
                    it,
                    color = if (
                        it.contains("connected", ignoreCase = true) ||
                        it.contains("saved", ignoreCase = true)
                    ) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Forge v0.3 workflows", fontWeight = FontWeight.Bold)
                    Text("Image — Qwen Image 2 on Replicate for text-to-image.")
                    Text("Edit — RunPod Qwen Image Edit for image-to-image, with Replicate Qwen Edit 2511 as a fallback.")
                    Text("Video — RunPod WAN 2.2 image-to-video or Replicate LTX 2.3 Fast.")
                    Text(
                        "Every video prompt automatically starts with: \"$VIDEO_PROMPT_PREFIX\"",
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Open-content mode", fontWeight = FontWeight.Bold)
                    Text(
                        "RunPod Edit and WAN can use RunPod's documented optional safety-checker setting for broader lawful adult generation.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Hard boundary: no sexual content involving minors and no non-consensual intimate imagery.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Button(
                onClick = {
                    tokenStore.save(token)
                    tokenStore.saveRunPod(runpodToken)
                    done()
                },
                enabled = token.isNotBlank() || runpodToken.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open Forge") }
        }
    }
}
