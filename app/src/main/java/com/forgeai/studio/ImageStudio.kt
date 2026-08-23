package com.forgeai.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ImageStudio(
    client: ReplicateClient,
    runpodClient: RunpodClient,
    historyStore: HistoryStore,
    onHistoryChanged: () -> Unit,
    onEdit: (File) -> Unit,
    onAnimate: (File) -> Unit,
    openSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var prompt by remember { mutableStateOf("") }
    var originalPrompt by remember { mutableStateOf("") }
    var negative by remember { mutableStateOf("") }
    var ratio by remember { mutableStateOf("1:1") }
    var expandPrompt by remember { mutableStateOf(true) }
    var outputCount by remember { mutableIntStateOf(1) }
    var seedText by remember { mutableStateOf("") }
    var randomSeed by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var promptBusy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<File>>(emptyList()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            StudioHeader(
                "Image · Text to Image",
                "Create with Qwen Image 2, generate controlled variants, then send any result to Edit or Video."
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
            PromptAiButtons(
                busy = promptBusy,
                actions = listOf(PromptAction.OPTIMIZE, PromptAction.CINEMATIC, PromptAction.REALISTIC, PromptAction.SIMPLIFY)
            ) { action ->
                if (prompt.isBlank()) { status = "Enter a rough image prompt first."; return@PromptAiButtons }
                scope.launch {
                    promptBusy = true
                    status = "Prompt AI: ${action.label}…"
                    if (originalPrompt.isBlank()) originalPrompt = prompt
                    runCatching { runpodClient.optimizePrompt(PromptIntent.IMAGE, action, prompt) }
                        .onSuccess { prompt = it; status = "Prompt optimized — review it before generating." }
                        .onFailure { status = it.message ?: "Prompt AI failed" }
                    promptBusy = false
                }
            }
        }

        item {
            OptionRow(
                "Aspect ratio",
                listOf("1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "2:1", "1:2"),
                ratio
            ) { ratio = it }
        }

        item { OptionRow("Number of outputs", listOf("1", "2", "4"), outputCount.toString()) { outputCount = it.toInt() } }
        item { ToggleRow("Qwen prompt expansion", expandPrompt) { expandPrompt = it } }

        item {
            OutlinedTextField(value = negative, onValueChange = { negative = it }, label = { Text("Negative prompt (optional)") }, modifier = Modifier.fillMaxWidth())
        }

        item {
            SeedControls(
                randomSeed = randomSeed,
                seedText = seedText,
                lastSeed = historyStore.latestSeed(CreationKind.IMAGE),
                onRandomChange = { randomSeed = it },
                onSeedTextChange = { seedText = it }
            )
        }

        item {
            Button(
                onClick = {
                    if (prompt.isBlank()) { status = "Enter a prompt first."; return@Button }
                    scope.launch {
                        busy = true
                        results = emptyList()
                        runCatching {
                            val lockedBaseSeed = SeedTools.resolve(randomSeed, seedText)
                            val files = mutableListOf<File>()
                            repeat(outputCount) { index ->
                                val seed = if (randomSeed) SeedTools.randomSeed() else lockedBaseSeed + index
                                status = "Generating ${index + 1}/$outputCount with Qwen Image 2…"
                                val url = client.generateQwenImage2(
                                    prompt = prompt,
                                    imageDataUri = null,
                                    aspectRatio = ratio,
                                    matchInput = false,
                                    expandPrompt = expandPrompt,
                                    negativePrompt = negative,
                                    seed = seed
                                )
                                val file = MediaUtils.downloadToInternal(context, url, CreationKind.IMAGE)
                                historyStore.add(
                                    CreationRecord(
                                        kind = CreationKind.IMAGE,
                                        model = "Qwen Image 2 · Replicate",
                                        prompt = prompt,
                                        originalPrompt = originalPrompt.ifBlank { prompt },
                                        localPath = file.absolutePath,
                                        seed = seed,
                                        settings = "ratio=$ratio; promptExpansion=$expandPrompt; output=${index + 1}/$outputCount"
                                    )
                                )
                                files += file
                            }
                            onHistoryChanged()
                            results = files
                            status = "Done · ${files.size} image${if (files.size == 1) "" else "s"}"
                        }.onFailure { status = it.message ?: "Generation failed" }
                        busy = false
                    }
                },
                enabled = !busy && !promptBusy,
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) { Text(if (busy) "Generating…" else "Generate $outputCount image${if (outputCount == 1) "" else "s"}") }
        }

        status?.let { item { StatusCard(it, busy || promptBusy, openSettings) } }

        results.forEachIndexed { index, file ->
            item {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Result ${index + 1}", fontWeight = FontWeight.SemiBold)
                        ResultImage(file)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onEdit(file) }, modifier = Modifier.weight(1f)) { Text("Edit") }
                            Button(onClick = { onAnimate(file) }, modifier = Modifier.weight(1f)) { Text("Animate") }
                        }
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
    }
}
