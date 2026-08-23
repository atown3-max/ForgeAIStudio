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

@Composable
fun HistoryScreen(
    historyStore: HistoryStore,
    refreshKey: Int,
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
        item { StudioHeader("History", "The app caches completed generations locally because provider output links expire.") }
        message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        if (records.isEmpty()) item { Text("No creations yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(records, key = { it.id }) { record ->
            val file = File(record.localPath)
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (record.kind == CreationKind.IMAGE) ResultImage(file) else VideoPlayer(file, compact = true)
                    Text(record.model, fontWeight = FontWeight.SemiBold)
                    Text(record.prompt, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.createdAt)), style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (record.kind == CreationKind.IMAGE) Button(onClick = { onAnimate(file) }) { Text("Animate") }
                        OutlinedButton(onClick = {
                            scope.launch { runCatching { MediaUtils.saveToGallery(context, file, record.kind) }.onSuccess { message = "Saved to gallery" }.onFailure { message = it.message } }
                        }) { Text("Save") }
                        TextButton(onClick = { historyStore.delete(record); onChanged() }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(tokenStore: SecureTokenStore, client: ReplicateClient, runpodClient: RunpodClient, done: () -> Unit) {
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
        item { Text("RunPod Open", fontWeight = FontWeight.Bold) }
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
        message?.let { item { Text(it, color = if (it.contains("connected", ignoreCase = true) || it.contains("saved", ignoreCase = true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Providers", fontWeight = FontWeight.Bold)
                    Text("Replicate — Qwen Image 2, Qwen Edit 2511, LTX‑2.3 Fast")
                    Text("RunPod Open — Qwen Image + WAN 2.2 with optional provider safety checker disabled")
                    Text("RunPod Open requires a separate RunPod account/API key and RunPod credits.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Adults-only boundary: no sexual content involving minors and no non-consensual intimate imagery.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Button(onClick = { tokenStore.save(token); tokenStore.saveRunPod(runpodToken); done() }, enabled = token.isNotBlank() || runpodToken.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Open Studio") } }
    }
}
