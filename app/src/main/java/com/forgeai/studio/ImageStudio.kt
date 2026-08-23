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
fun ImageStudio(
    client: ReplicateClient,
    historyStore: HistoryStore,
    onHistoryChanged: () -> Unit,
    onAnimate: (File) -> Unit,
    openSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var model by remember { mutableStateOf(ImageModel.QWEN2) }
    var refs by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var prompt by remember { mutableStateOf("") }
    var negative by remember { mutableStateOf("") }
    var ratio by remember { mutableStateOf("1:1") }
    var matchInput by remember { mutableStateOf(true) }
    var expandPrompt by remember { mutableStateOf(true) }
    var seedText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<File?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        refs = uris.take(if (model == ImageModel.QWEN2511) 3 else 1)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { StudioHeader("Image Studio", "Generate or edit with Qwen. Then send the result straight to LTX.") }
        item {
            OptionRow(
                label = "Model",
                options = ImageModel.entries.map { it.label },
                selected = model.label,
                onSelect = { chosen ->
                    model = ImageModel.entries.first { it.label == chosen }
                    if (model == ImageModel.QWEN2 && refs.size > 1) refs = refs.take(1)
                }
            )
        }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (refs.isEmpty()) "No reference image" else "${refs.size} reference image${if (refs.size == 1) "" else "s"}")
                    if (refs.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            refs.take(3).forEach { uri ->
                                AsyncImage(uri, null, Modifier.size(88.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                            }
                        }
                    }
                    Button(onClick = { picker.launch("image/*") }, enabled = !busy) {
                        Text(if (model == ImageModel.QWEN2511) "Choose up to 3 images" else "Choose image (optional)")
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text(if (refs.isEmpty()) "Describe the image" else "Describe the edit") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item { OptionRow("Aspect ratio", listOf("1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "2:1", "1:2"), ratio) { ratio = it } }
        if (model == ImageModel.QWEN2) {
            item { ToggleRow("Match input image size", matchInput && refs.isNotEmpty(), enabled = refs.isNotEmpty()) { matchInput = it } }
            item { ToggleRow("Prompt expansion", expandPrompt) { expandPrompt = it } }
            item { OutlinedTextField(value = negative, onValueChange = { negative = it }, label = { Text("Negative prompt (optional)") }, modifier = Modifier.fillMaxWidth()) }
        }
        item { OutlinedTextField(value = seedText, onValueChange = { seedText = it.filter(Char::isDigit).take(10) }, label = { Text("Seed (optional)") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Button(
                onClick = {
                    if (prompt.isBlank()) { status = "Enter a prompt first."; return@Button }
                    if (model == ImageModel.QWEN2511 && refs.isEmpty()) { status = "Qwen Edit 2511 needs at least one reference image."; return@Button }
                    scope.launch {
                        busy = true; status = "Preparing input…"; result = null
                        runCatching {
                            val data = refs.mapIndexed { index, uri ->
                                status = "Preparing reference ${index + 1}/${refs.size}…"
                                MediaUtils.uriToJpegDataUri(context, uri)
                            }
                            status = "Generating with ${model.label}…"
                            val url = when (model) {
                                ImageModel.QWEN2 -> client.generateQwenImage2(prompt, data.firstOrNull(), ratio, matchInput, expandPrompt, negative, seedText.toLongOrNull())
                                ImageModel.QWEN2511 -> client.generateQwen2511(prompt, data, if (ratio == "1:1" && refs.isNotEmpty()) "match_input_image" else ratio, seedText.toLongOrNull())
                            }
                            status = "Saving result locally…"
                            val file = MediaUtils.downloadToInternal(context, url, CreationKind.IMAGE)
                            historyStore.add(CreationRecord(kind = CreationKind.IMAGE, model = model.label, prompt = prompt, localPath = file.absolutePath))
                            onHistoryChanged(); result = file; status = "Done"
                        }.onFailure { status = it.message ?: "Generation failed" }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(if (busy) "Generating…" else "Generate image") }
        }
        status?.let { item { StatusCard(it, busy, openSettings) } }
        result?.let { file ->
            item { ResultImage(file) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onAnimate(file) }, modifier = Modifier.weight(1f)) { Text("Animate with LTX") }
                    OutlinedButton(onClick = { scope.launch { runCatching { MediaUtils.saveToGallery(context, file, CreationKind.IMAGE) }.onSuccess { status = "Saved to Pictures/Forge AI Studio" }.onFailure { status = it.message } } }, modifier = Modifier.weight(1f)) { Text("Save") }
                }
            }
        }
    }
}
