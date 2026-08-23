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
fun VideoStudio(
    client: ReplicateClient,
    historyStore: HistoryStore,
    initialFile: File?,
    consumeInitialFile: () -> Unit,
    onHistoryChanged: () -> Unit,
    openSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var firstUri by remember { mutableStateOf<Uri?>(null) }
    var lastUri by remember { mutableStateOf<Uri?>(null) }
    var firstFile by remember { mutableStateOf<File?>(null) }
    var prompt by remember { mutableStateOf("") }
    var duration by remember { mutableIntStateOf(6) }
    var resolution by remember { mutableStateOf("1080p") }
    var ratio by remember { mutableStateOf("16:9") }
    var fps by remember { mutableIntStateOf(25) }
    var motion by remember { mutableStateOf("none") }
    var audio by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(initialFile?.absolutePath) {
        if (initialFile != null) {
            firstFile = initialFile; firstUri = null; result = null
            status = "Image loaded from Image Studio. Describe how it should move."
            consumeInitialFile()
        }
    }

    val firstPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) { firstUri = uri; firstFile = null } }
    val lastPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> lastUri = uri }
    val invalidLongSetting = duration > 10 && (resolution != "1080p" || fps !in listOf(24, 25))

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { StudioHeader("Video Studio", "LTX‑2.3 Fast · text-to-video or image-to-video with synchronized audio.") }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (firstFile != null || firstUri != null) "First frame selected" else "First frame (optional)")
                    (firstFile ?: firstUri)?.let { source -> AsyncImage(source, null, Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { firstPicker.launch("image/*") }, enabled = !busy) { Text("Choose first frame") }
                        if (firstFile != null || firstUri != null) TextButton(onClick = { firstFile = null; firstUri = null }) { Text("Clear") }
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (lastUri == null) "Last frame (optional)" else "Last frame selected")
                    lastUri?.let { AsyncImage(it, null, Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { lastPicker.launch("image/*") }, enabled = firstFile != null || firstUri != null) { Text("Choose last frame") }
                        if (lastUri != null) TextButton(onClick = { lastUri = null }) { Text("Clear") }
                    }
                }
            }
        }
        item { OutlinedTextField(prompt, { prompt = it }, label = { Text("Describe the video") }, minLines = 4, modifier = Modifier.fillMaxWidth()) }
        item { OptionRow("Duration", listOf("6", "8", "10", "12", "14", "16", "18", "20"), duration.toString()) { duration = it.toInt() } }
        item { OptionRow("Resolution", listOf("1080p", "2k", "4k"), resolution) { resolution = it } }
        item { OptionRow("Aspect ratio", listOf("16:9", "9:16"), ratio) { ratio = it } }
        item { OptionRow("FPS", listOf("24", "25", "48", "50"), fps.toString()) { fps = it.toInt() } }
        item { OptionRow("Camera", listOf("none", "static", "dolly_in", "dolly_out", "dolly_left", "dolly_right", "jib_up", "jib_down", "focus_shift"), motion) { motion = it } }
        item { ToggleRow("Generate synchronized audio", audio) { audio = it } }
        if (invalidLongSetting) item { Text("12–20 second clips require 1080p at 24 or 25 FPS.", color = MaterialTheme.colorScheme.error) }
        item {
            Button(
                onClick = {
                    if (prompt.isBlank()) { status = "Enter a video prompt first."; return@Button }
                    if (invalidLongSetting) { status = "Fix the duration/resolution/FPS combination first."; return@Button }
                    if (lastUri != null && firstFile == null && firstUri == null) { status = "A last frame requires a first frame."; return@Button }
                    scope.launch {
                        busy = true; result = null; status = "Preparing frames…"
                        runCatching {
                            val firstData = when {
                                firstFile != null -> MediaUtils.fileToJpegDataUri(firstFile!!)
                                firstUri != null -> MediaUtils.uriToJpegDataUri(context, firstUri!!)
                                else -> null
                            }
                            val lastData = lastUri?.let { MediaUtils.uriToJpegDataUri(context, it) }
                            status = "Generating with LTX‑2.3…"
                            val url = client.generateLtx23(prompt, firstData, lastData, duration, resolution, ratio, fps, motion, audio)
                            status = "Saving video locally…"
                            val file = MediaUtils.downloadToInternal(context, url, CreationKind.VIDEO)
                            historyStore.add(CreationRecord(kind = CreationKind.VIDEO, model = "LTX 2.3 Fast", prompt = prompt, localPath = file.absolutePath))
                            onHistoryChanged(); result = file; status = "Done"
                        }.onFailure { status = it.message ?: "Generation failed" }
                        busy = false
                    }
                },
                enabled = !busy && !invalidLongSetting,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(if (busy) "Generating…" else "Generate video") }
        }
        status?.let { item { StatusCard(it, busy, openSettings) } }
        result?.let { file ->
            item { VideoPlayer(file) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        val uri = MediaUtils.shareUri(context, file)
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share video"))
                    }, modifier = Modifier.weight(1f)) { Text("Share") }
                    OutlinedButton(onClick = { scope.launch { runCatching { MediaUtils.saveToGallery(context, file, CreationKind.VIDEO) }.onSuccess { status = "Saved to Movies/Forge AI Studio" }.onFailure { status = it.message } } }, modifier = Modifier.weight(1f)) { Text("Save") }
                }
            }
        }
    }
}
