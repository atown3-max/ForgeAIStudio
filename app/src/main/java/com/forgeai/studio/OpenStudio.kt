package com.forgeai.studio

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File

enum class OpenMode(val label: String) { IMAGE("Image"), VIDEO("Video") }

@Composable
fun OpenStudio(
    client: RunpodClient,
    historyStore: HistoryStore,
    onHistoryChanged: () -> Unit,
    openSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(OpenMode.IMAGE) }
    var prompt by remember { mutableStateOf("") }
    var negative by remember { mutableStateOf("") }
    var ratio by remember { mutableStateOf("1:1") }
    var seedText by remember { mutableStateOf("") }
    var openContent by remember { mutableStateOf(true) }
    var adultAcknowledged by remember { mutableStateOf(false) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceRemoteUrl by remember { mutableStateOf<String?>(null) }
    var sourcePreview by remember { mutableStateOf<File?>(null) }
    var duration by remember { mutableIntStateOf(5) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var imageResult by remember { mutableStateOf<File?>(null) }
    var imageRemoteUrl by remember { mutableStateOf<String?>(null) }
    var videoResult by remember { mutableStateOf<File?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            sourceUri = uri
            sourceRemoteUrl = null
            sourcePreview = null
        }
    }

    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { StudioHeader("Open Studio", "RunPod open-weight models with the provider's optional safety checker disabled when Open content is on.") }
        item { OptionRow("Mode", OpenMode.entries.map { it.label }, mode.label) { mode = OpenMode.entries.first { m -> m.label == it } } }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleRow("Open content (adults only)", openContent) { openContent = it }
                    if (openContent) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("I understand the adults-only boundary", Modifier.weight(1f))
                            Checkbox(checked = adultAcknowledged, onCheckedChange = { adultAcknowledged = it })
                        }
                        Text(
                            "No sexual content involving minors and no non-consensual intimate imagery. Open mode uses RunPod's documented safety-checker control; it does not bypass another provider's safeguards.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (mode == OpenMode.IMAGE) {
            item { OutlinedTextField(prompt, { prompt = it }, label = { Text("Describe the image") }, minLines = 4, modifier = Modifier.fillMaxWidth()) }
            item { OptionRow("Aspect ratio", listOf("1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3"), ratio) { ratio = it } }
            item { OutlinedTextField(negative, { negative = it }, label = { Text("Negative prompt (optional)") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(seedText, { seedText = it.filter(Char::isDigit).take(10) }, label = { Text("Seed (optional)") }, modifier = Modifier.fillMaxWidth()) }
            item {
                Button(
                    onClick = {
                        if (prompt.isBlank()) { status = "Enter a prompt first."; return@Button }
                        if (openContent && !adultAcknowledged) { status = "Confirm the adults-only boundary first."; return@Button }
                        scope.launch {
                            busy = true; imageResult = null; status = "Generating Qwen Image on RunPod…"
                            runCatching {
                                val url = client.generateQwenImage(prompt, negative, ratio, seedText.toLongOrNull(), openContent)
                                imageRemoteUrl = url
                                status = "Saving image locally…"
                                val file = MediaUtils.downloadToInternal(context, url, CreationKind.IMAGE)
                                historyStore.add(CreationRecord(kind = CreationKind.IMAGE, model = "Qwen Image · RunPod Open", prompt = prompt, localPath = file.absolutePath))
                                onHistoryChanged()
                                imageResult = file
                                status = "Done"
                            }.onFailure { status = it.message ?: "Generation failed" }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(if (busy) "Generating…" else "Generate open image") }
            }
            status?.let { item { StatusCard(it, busy, openSettings) } }
            imageResult?.let { file ->
                item { ResultImage(file) }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = {
                            sourcePreview = file
                            sourceUri = null
                            sourceRemoteUrl = imageRemoteUrl
                            mode = OpenMode.VIDEO
                            status = "Qwen image loaded as the WAN first frame."
                        }, modifier = Modifier.weight(1f)) { Text("Animate with WAN") }
                        OutlinedButton(onClick = {
                            scope.launch { runCatching { MediaUtils.saveToGallery(context, file, CreationKind.IMAGE) }.onSuccess { status = "Saved to Pictures/Forge AI Studio" }.onFailure { status = it.message } }
                        }, modifier = Modifier.weight(1f)) { Text("Save") }
                    }
                }
            }
        } else {
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("First frame", fontWeight = FontWeight.SemiBold)
                        val preview: Any? = sourcePreview ?: sourceUri
                        preview?.let { AsyncImage(it, null, Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { picker.launch("image/*") }, enabled = !busy) { Text("Choose image") }
                            if (preview != null) TextButton(onClick = { sourceUri = null; sourcePreview = null; sourceRemoteUrl = null }) { Text("Clear") }
                        }
                        Text("WAN Open accepts a first frame. Generated Forge images use their temporary provider URL; gallery images are sent as a compact data URI and may depend on endpoint support.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { OutlinedTextField(prompt, { prompt = it }, label = { Text("Describe the video motion") }, minLines = 4, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(negative, { negative = it }, label = { Text("Negative prompt (optional)") }, modifier = Modifier.fillMaxWidth()) }
            item { OptionRow("Duration", listOf("5", "8", "10", "15"), duration.toString()) { duration = it.toInt() } }
            item { OptionRow("Aspect ratio", listOf("16:9", "9:16"), if (ratio in listOf("16:9", "9:16")) ratio else "16:9") { ratio = it } }
            item { OutlinedTextField(seedText, { seedText = it.filter(Char::isDigit).take(10) }, label = { Text("Seed (optional)") }, modifier = Modifier.fillMaxWidth()) }
            item {
                Button(
                    onClick = {
                        if (prompt.isBlank()) { status = "Enter a video prompt first."; return@Button }
                        if (openContent && !adultAcknowledged) { status = "Confirm the adults-only boundary first."; return@Button }
                        scope.launch {
                            busy = true; videoResult = null; status = "Preparing WAN input…"
                            runCatching {
                                val imageInput = sourceRemoteUrl ?: sourceUri?.let { MediaUtils.uriToJpegDataUri(context, it) }
                                    ?: error("Choose a first frame for WAN Open image-to-video.")
                                status = "Generating WAN image-to-video…"
                                val url = client.generateWan22Video(prompt, imageInput, negative, duration, if (ratio == "9:16") "9:16" else "16:9", seedText.toLongOrNull(), openContent)
                                status = "Saving video locally…"
                                val file = MediaUtils.downloadToInternal(context, url, CreationKind.VIDEO)
                                historyStore.add(CreationRecord(kind = CreationKind.VIDEO, model = "WAN 2.2 · RunPod Open", prompt = prompt, localPath = file.absolutePath))
                                onHistoryChanged()
                                videoResult = file
                                status = "Done"
                            }.onFailure { status = it.message ?: "Generation failed" }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(if (busy) "Generating…" else "Generate open video") }
            }
            status?.let { item { StatusCard(it, busy, openSettings) } }
            videoResult?.let { file ->
                item { VideoPlayer(file) }
                item { OutlinedButton(onClick = { scope.launch { runCatching { MediaUtils.saveToGallery(context, file, CreationKind.VIDEO) }.onSuccess { status = "Saved to Movies/Forge AI Studio" }.onFailure { status = it.message } } }, modifier = Modifier.fillMaxWidth()) { Text("Save video") } }
            }
        }
    }
}
