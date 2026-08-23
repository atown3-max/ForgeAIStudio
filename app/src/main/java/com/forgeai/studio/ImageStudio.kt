package com.forgeai.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ImageStudio(
    client: ReplicateClient,
    historyStore: HistoryStore,
    onHistoryChanged: () -> Unit,
    onEdit: (File) -> Unit,
    onAnimate: (File) -> Unit,
    openSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var prompt by remember { mutableStateOf("") }
    var negative by remember { mutableStateOf("") }
    var ratio by remember { mutableStateOf("1:1") }
    var expandPrompt by remember { mutableStateOf(true) }
    var seedText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<File?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            StudioHeader(
                "Image · Text to Image",
                "Create with Qwen Image 2, then send the result directly to Edit or Video."
            )
        }

        item {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Describe the image") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            OptionRow(
                "Aspect ratio",
                listOf("1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "2:1", "1:2"),
                ratio
            ) { ratio = it }
        }

        item { ToggleRow("Prompt expansion", expandPrompt) { expandPrompt = it } }

        item {
            OutlinedTextField(
                value = negative,
                onValueChange = { negative = it },
                label = { Text("Negative prompt (optional)") },
                modifier = Modifier.fillMaxWidth()
            )
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
                    if (prompt.isBlank()) {
                        status = "Enter a prompt first."
                        return@Button
                    }
                    scope.launch {
                        busy = true
                        result = null
                        status = "Generating with Qwen Image 2…"
                        runCatching {
                            val url = client.generateQwenImage2(
                                prompt = prompt,
                                imageDataUri = null,
                                aspectRatio = ratio,
                                matchInput = false,
                                expandPrompt = expandPrompt,
                                negativePrompt = negative,
                                seed = seedText.toLongOrNull()
                            )
                            status = "Saving image locally…"
                            val file = MediaUtils.downloadToInternal(context, url, CreationKind.IMAGE)
                            historyStore.add(
                                CreationRecord(
                                    kind = CreationKind.IMAGE,
                                    model = "Qwen Image 2 · Replicate",
                                    prompt = prompt,
                                    localPath = file.absolutePath
                                )
                            )
                            onHistoryChanged()
                            result = file
                            status = "Done"
                        }.onFailure { status = it.message ?: "Generation failed" }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                Text(if (busy) "Generating…" else "Generate image")
            }
        }

        status?.let { item { StatusCard(it, busy, openSettings) } }

        result?.let { file ->
            item { ResultImage(file) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onEdit(file) }, modifier = Modifier.weight(1f)) {
                        Text("Edit")
                    }
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
