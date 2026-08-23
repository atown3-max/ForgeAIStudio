package com.forgeai.studio

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

enum class EditProvider(val label: String) {
    RUNPOD_OPEN("RunPod Open · Qwen Edit"),
    REPLICATE("Replicate · Qwen Edit 2511")
}

@Composable
fun EditStudio(
    runpodClient: RunpodClient,
    replicateClient: ReplicateClient,
    historyStore: HistoryStore,
    initialFile: File?,
    consumeInitialFile: () -> Unit,
    onAnimate: (File) -> Unit,
    onHistoryChanged: () -> Unit,
    openSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(EditProvider.RUNPOD_OPEN) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceFile by remember { mutableStateOf<File?>(null) }
    var prompt by remember { mutableStateOf("") }
    var negative by remember { mutableStateOf("") }
    var ratioLabel by remember { mutableStateOf("Match source") }
    var seedText by remember { mutableStateOf("") }
    var openContent by remember { mutableStateOf(true) }
    var adultAcknowledged by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(initialFile?.absolutePath) {
        if (initialFile != null) {
            sourceFile = initialFile
            sourceUri = null
            result = null
            status = "Image loaded. Describe what you want changed."
            consumeInitialFile()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            sourceUri = uri
            sourceFile = null
            result = null
            status = null
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            StudioHeader(
                "Edit · Image to Image",
                "Choose a photo, describe the change, and generate a new edited image."
            )
        }

        item {
            OptionRow(
                "Provider",
                EditProvider.entries.map { it.label },
                provider.label
            ) { selected -> provider = EditProvider.entries.first { it.label == selected } }
        }

        if (provider == EditProvider.RUNPOD_OPEN) {
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
                    Text("Source image", fontWeight = FontWeight.SemiBold)
                    val source: Any? = sourceFile ?: sourceUri
                    if (source != null) {
                        AsyncImage(
                            model = source,
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text("Choose the image you want to transform.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { picker.launch("image/*") }, enabled = !busy) {
                            Text(if (source == null) "Choose image" else "Change image")
                        }
                        if (source != null) {
                            TextButton(onClick = { sourceFile = null; sourceUri = null; result = null }) {
                                Text("Clear")
                            }
                        }
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Describe the edit") },
                placeholder = { Text("Example: Change the background to a cinematic city at night while preserving the subject.") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (provider == EditProvider.RUNPOD_OPEN) {
            item {
                OutlinedTextField(
                    value = negative,
                    onValueChange = { negative = it },
                    label = { Text("Negative prompt (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            item {
                OptionRow(
                    "Output ratio",
                    listOf("Match source", "1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3"),
                    ratioLabel
                ) { ratioLabel = it }
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

        item {
            Button(
                onClick = {
                    if (sourceFile == null && sourceUri == null) {
                        status = "Choose a source image first."
                        return@Button
                    }
                    if (prompt.isBlank()) {
                        status = "Describe the edit first."
                        return@Button
                    }
                    if (provider == EditProvider.RUNPOD_OPEN && openContent && !adultAcknowledged) {
                        status = "Confirm the adults-only boundary first."
                        return@Button
                    }

                    scope.launch {
                        busy = true
                        result = null
                        status = "Preparing source image…"
                        runCatching {
                            val imageData = when {
                                sourceFile != null -> MediaUtils.fileToJpegDataUri(sourceFile!!)
                                sourceUri != null -> MediaUtils.uriToJpegDataUri(context, sourceUri!!)
                                else -> error("Choose a source image first.")
                            }

                            status = "Editing with ${provider.label}…"
                            val url = when (provider) {
                                EditProvider.RUNPOD_OPEN -> runpodClient.generateQwenEdit(
                                    prompt = prompt,
                                    imageDataUriOrUrl = imageData,
                                    negativePrompt = negative,
                                    seed = seedText.toLongOrNull(),
                                    openContent = openContent
                                )
                                EditProvider.REPLICATE -> replicateClient.generateQwen2511(
                                    prompt = prompt,
                                    imageDataUris = listOf(imageData),
                                    aspectRatio = if (ratioLabel == "Match source") "match_input_image" else ratioLabel,
                                    seed = seedText.toLongOrNull()
                                )
                            }

                            status = "Saving edited image locally…"
                            val file = MediaUtils.downloadToInternal(context, url, CreationKind.IMAGE)
                            historyStore.add(
                                CreationRecord(
                                    kind = CreationKind.IMAGE,
                                    model = provider.label,
                                    prompt = prompt,
                                    localPath = file.absolutePath
                                )
                            )
                            onHistoryChanged()
                            result = file
                            status = "Done"
                        }.onFailure { status = it.message ?: "Edit failed" }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                Text(if (busy) "Editing…" else "Generate edited image")
            }
        }

        status?.let { item { StatusCard(it, busy, openSettings) } }

        result?.let { file ->
            item { ResultImage(file) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            sourceFile = file
                            sourceUri = null
                            result = null
                            status = "Edited image loaded as the new source."
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Edit again") }
                    Button(onClick = { onAnimate(file) }, modifier = Modifier.weight(1f)) {
                        Text("Animate")
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching { MediaUtils.saveToGallery(context, file, CreationKind.IMAGE) }
                                .onSuccess { status = "Saved to Pictures/Forge AI Studio" }
                                .onFailure { status = it.message }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save image") }
            }
        }
    }
}
