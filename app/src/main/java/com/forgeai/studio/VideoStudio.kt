package com.forgeai.studio

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

const val VIDEO_PROMPT_PREFIX = "make this image come alive, cinematic motion, smooth animation"

fun buildForgeVideoPrompt(details: String): String {
    val trimmed = details.trim()
    return if (trimmed.isBlank()) VIDEO_PROMPT_PREFIX else "$VIDEO_PROMPT_PREFIX. $trimmed"
}

enum class VideoProvider(val label: String) {
    RUNPOD_OPEN("RunPod Open · WAN 2.2"),
    REPLICATE_LTX("Replicate · LTX 2.3 Fast")
}

@Composable
fun VideoStudio(
    client: ReplicateClient,
    runpodClient: RunpodClient,
    historyStore: HistoryStore,
    initialFile: File?,
    consumeInitialFile: () -> Unit,
    onHistoryChanged: () -> Unit,
    openSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(VideoProvider.RUNPOD_OPEN) }
    var firstUri by remember { mutableStateOf<Uri?>(null) }
    var lastUri by remember { mutableStateOf<Uri?>(null) }
    var firstFile by remember { mutableStateOf<File?>(null) }
    var promptDetails by remember { mutableStateOf("") }
    var negative by remember { mutableStateOf("") }
    var duration by remember { mutableIntStateOf(5) }
    var resolution by remember { mutableStateOf("1080p") }
    var ratio by remember { mutableStateOf("16:9") }
    var fps by remember { mutableIntStateOf(25) }
    var motion by remember { mutableStateOf("none") }
    var audio by remember { mutableStateOf(true) }
    var seedText by remember { mutableStateOf("") }
    var openContent by remember { mutableStateOf(true) }
    var adultAcknowledged by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(initialFile?.absolutePath) {
        if (initialFile != null) {
            firstFile = initialFile
            firstUri = null
            result = null
            status = "Image loaded. Add any motion details, or generate with the default cinematic prompt."
            consumeInitialFile()
        }
    }

    LaunchedEffect(provider) {
        if (provider == VideoProvider.RUNPOD_OPEN && duration !in listOf(5, 8, 10, 15)) duration = 5
        if (provider == VideoProvider.REPLICATE_LTX && duration !in listOf(6, 8, 10, 12, 14, 16, 18, 20)) duration = 6
        if (provider == VideoProvider.RUNPOD_OPEN) lastUri = null
    }

    val firstPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            firstUri = uri
            firstFile = null
            result = null
        }
    }
    val lastPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) lastUri = uri
    }

    val invalidLongSetting = provider == VideoProvider.REPLICATE_LTX &&
        duration > 10 && (resolution != "1080p" || fps !in listOf(24, 25))

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            StudioHeader(
                "Video · Image to Video",
                "Choose a still image and bring it to life with WAN or LTX."
            )
        }

        item {
            OptionRow(
                "Provider",
                VideoProvider.entries.map { it.label },
                provider.label
            ) { selected -> provider = VideoProvider.entries.first { it.label == selected } }
        }

        if (provider == VideoProvider.RUNPOD_OPEN) {
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToggleRow("Open content (adults only)", openContent) { openContent = it }
                        if (openContent) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("I understand the adults-only boundary", Modifier.weight(1f))
                                Checkbox(
                                    checked = adultAcknowledged,
                                    onCheckedChange = { adultAcknowledged = it }
                                )
                            }
                            Text(
                                "No sexual content involving minors and no non-consensual intimate imagery.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("First frame", fontWeight = FontWeight.SemiBold)
                    val source: Any? = firstFile ?: firstUri
                    if (source != null) {
                        AsyncImage(
                            model = source,
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text("Choose the image you want to animate.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { firstPicker.launch("image/*") }, enabled = !busy) {
                            Text(if (source == null) "Choose image" else "Change image")
                        }
                        if (source != null) {
                            TextButton(onClick = { firstFile = null; firstUri = null; result = null }) { Text("Clear") }
                        }
                    }
                }
            }
        }

        if (provider == VideoProvider.REPLICATE_LTX) {
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Last frame (optional)", fontWeight = FontWeight.SemiBold)
                        lastUri?.let {
                            AsyncImage(
                                model = it,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { lastPicker.launch("image/*") },
                                enabled = firstFile != null || firstUri != null
                            ) { Text("Choose last frame") }
                            if (lastUri != null) TextButton(onClick = { lastUri = null }) { Text("Clear") }
                        }
                    }
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Automatic prompt start", fontWeight = FontWeight.SemiBold)
                    Text(
                        VIDEO_PROMPT_PREFIX,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Forge automatically places this at the beginning of every video prompt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = promptDetails,
                onValueChange = { promptDetails = it },
                label = { Text("Add motion details (optional)") },
                placeholder = { Text("Example: she smiles, turns toward the camera, hair moving gently in the breeze") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (provider == VideoProvider.RUNPOD_OPEN) {
            item {
                OutlinedTextField(
                    value = negative,
                    onValueChange = { negative = it },
                    label = { Text("Negative prompt (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item { OptionRow("Duration", listOf("5", "8", "10", "15"), duration.toString()) { duration = it.toInt() } }
            item { OptionRow("Aspect ratio", listOf("16:9", "9:16"), ratio) { ratio = it } }
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("WAN output", fontWeight = FontWeight.SemiBold)
                        Text("720p · 30 inference steps · guidance 5", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = seedText,
                    onValueChange = { seedText = it.filter(Char::isDigit).take(10) },
                    label = { Text("Seed (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            item { OptionRow("Duration", listOf("6", "8", "10", "12", "14", "16", "18", "20"), duration.toString()) { duration = it.toInt() } }
            item { OptionRow("Resolution", listOf("1080p", "2k", "4k"), resolution) { resolution = it } }
            item { OptionRow("Aspect ratio", listOf("16:9", "9:16"), ratio) { ratio = it } }
            item { OptionRow("FPS", listOf("24", "25", "48", "50"), fps.toString()) { fps = it.toInt() } }
            item {
                OptionRow(
                    "Camera",
                    listOf("none", "static", "dolly_in", "dolly_out", "dolly_left", "dolly_right", "jib_up", "jib_down", "focus_shift"),
                    motion
                ) { motion = it }
            }
            item { ToggleRow("Generate synchronized audio", audio) { audio = it } }
            if (invalidLongSetting) {
                item { Text("12–20 second clips require 1080p at 24 or 25 FPS.", color = MaterialTheme.colorScheme.error) }
            }
        }

        item {
            Button(
                onClick = {
                    if (firstFile == null && firstUri == null) {
                        status = "Choose a first-frame image before generating video."
                        return@Button
                    }
                    if (invalidLongSetting) {
                        status = "Fix the duration/resolution/FPS combination first."
                        return@Button
                    }
                    if (provider == VideoProvider.RUNPOD_OPEN && openContent && !adultAcknowledged) {
                        status = "Confirm the adults-only boundary first."
                        return@Button
                    }

                    scope.launch {
                        busy = true
                        result = null
                        status = "Preparing image…"
                        runCatching {
                            val firstData = when {
                                firstFile != null -> MediaUtils.fileToJpegDataUri(firstFile!!)
                                firstUri != null -> MediaUtils.uriToJpegDataUri(context, firstUri!!)
                                else -> error("Choose a first frame.")
                            }
                            val fullPrompt = buildForgeVideoPrompt(promptDetails)

                            val url = when (provider) {
                                VideoProvider.RUNPOD_OPEN -> {
                                    status = "Generating with WAN 2.2…"
                                    runpodClient.generateWan22Video(
                                        prompt = fullPrompt,
                                        image = firstData,
                                        negativePrompt = negative,
                                        duration = duration,
                                        aspectRatio = ratio,
                                        seed = seedText.toLongOrNull(),
                                        openContent = openContent
                                    )
                                }
                                VideoProvider.REPLICATE_LTX -> {
                                    val lastData = lastUri?.let { MediaUtils.uriToJpegDataUri(context, it) }
                                    status = "Generating with LTX 2.3…"
                                    client.generateLtx23(
                                        prompt = fullPrompt,
                                        firstFrameDataUri = firstData,
                                        lastFrameDataUri = lastData,
                                        duration = duration,
                                        resolution = resolution,
                                        aspectRatio = ratio,
                                        fps = fps,
                                        cameraMotion = motion,
                                        generateAudio = audio
                                    )
                                }
                            }

                            status = "Saving video locally…"
                            val file = MediaUtils.downloadToInternal(context, url, CreationKind.VIDEO)
                            historyStore.add(
                                CreationRecord(
                                    kind = CreationKind.VIDEO,
                                    model = provider.label,
                                    prompt = fullPrompt,
                                    localPath = file.absolutePath
                                )
                            )
                            onHistoryChanged()
                            result = file
                            status = "Done"
                        }.onFailure { status = it.message ?: "Video generation failed" }
                        busy = false
                    }
                },
                enabled = !busy && !invalidLongSetting,
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                Text(if (busy) "Generating…" else "Generate video")
            }
        }

        status?.let { item { StatusCard(it, busy, openSettings) } }

        result?.let { file ->
            item { VideoPlayer(file) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val uri = MediaUtils.shareUri(context, file)
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "video/mp4"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    },
                                    "Share video"
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Share") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCatching { MediaUtils.saveToGallery(context, file, CreationKind.VIDEO) }
                                    .onSuccess { status = "Saved to Movies/Forge AI Studio" }
                                    .onFailure { status = it.message }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Save") }
                }
            }
        }
    }
}
