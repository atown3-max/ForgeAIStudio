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
import java.util.UUID

enum class EditProvider(val label: String) {
    RUNPOD_MULTI("RunPod · Qwen Edit 2511 · Multi-reference"),
    RUNPOD_OPEN("RunPod Open · Qwen Edit · Single image"),
    REPLICATE("Replicate · Qwen Edit 2511")
}

data class EditUiReference(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri? = null,
    val file: File? = null,
    val role: ReferenceRole = ReferenceRole.SUBJECT
)

@Composable
fun EditStudio(
    runpodClient: RunpodClient,
    replicateClient: ReplicateClient,
    historyStore: HistoryStore,
    profileStore: ProfileStore,
    profileRefreshKey: Int,
    initialFile: File?,
    consumeInitialFile: () -> Unit,
    onAnimate: (File) -> Unit,
    onHistoryChanged: () -> Unit,
    openSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val characters = remember(profileRefreshKey) { profileStore.loadCharacters() }
    val backgrounds = remember(profileRefreshKey) { profileStore.loadBackgrounds() }

    var provider by remember { mutableStateOf(EditProvider.RUNPOD_MULTI) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceFile by remember { mutableStateOf<File?>(null) }
    var extraRefs by remember { mutableStateOf<List<EditUiReference>>(emptyList()) }
    var newReferenceRole by remember { mutableStateOf(ReferenceRole.SUBJECT) }
    var selectedCharacterId by remember { mutableStateOf<String?>(null) }
    var selectedBackgroundId by remember { mutableStateOf<String?>(null) }
    var includeAnatomy by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var originalPrompt by remember { mutableStateOf("") }
    var negative by remember { mutableStateOf("") }
    var outputSize by remember { mutableStateOf("1024*1024") }
    var outputCount by remember { mutableIntStateOf(1) }
    var seedText by remember { mutableStateOf("") }
    var randomSeed by remember { mutableStateOf(true) }
    var openContent by remember { mutableStateOf(true) }
    var adultAcknowledged by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var promptBusy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<File>>(emptyList()) }

    val selectedCharacter = characters.firstOrNull { it.id == selectedCharacterId }
    val selectedBackground = backgrounds.firstOrNull { it.id == selectedBackgroundId }

    LaunchedEffect(initialFile?.absolutePath) {
        if (initialFile != null) {
            sourceFile = initialFile
            sourceUri = null
            results = emptyList()
            status = "Image loaded. Add references or describe what you want changed."
            consumeInitialFile()
        }
    }

    val basePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            sourceUri = uri
            sourceFile = null
            results = emptyList()
            status = null
        }
    }
    val extraPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            val room = (2 - extraRefs.size).coerceAtLeast(0)
            extraRefs = extraRefs + uris.take(room).map { EditUiReference(uri = it, role = newReferenceRole) }
        }
    }

    val referenceContext = ForgePromptBuilder.referenceSummary(extraRefs.map { it.role }, selectedCharacter, selectedBackground)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            StudioHeader(
                "Edit · Multi-reference Studio",
                "Merge subjects, outfits, poses, styles, and saved characters/backgrounds. Qwen 2511 uses up to 3 images per edit."
            )
        }

        item {
            OptionRow("Provider", EditProvider.entries.map { it.label }, provider.label) { selected ->
                provider = EditProvider.entries.first { it.label == selected }
            }
        }

        if (provider == EditProvider.RUNPOD_OPEN) {
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
                                "No sexual content involving minors and no non-consensual intimate imagery. This single-image provider does not receive extra reference photos.",
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
                    Text("Base image · Image 1", fontWeight = FontWeight.SemiBold)
                    val source: Any? = sourceFile ?: sourceUri
                    if (source != null) {
                        AsyncImage(model = source, contentDescription = null, modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                    } else {
                        Text("Choose the scene or image you want to edit.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { basePicker.launch("image/*") }, enabled = !busy) { Text(if (source == null) "Choose base" else "Change base") }
                        if (source != null) TextButton(onClick = { sourceFile = null; sourceUri = null; results = emptyList() }) { Text("Clear") }
                    }
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Extra image references", fontWeight = FontWeight.SemiBold)
                    Text("Add up to two explicit references. Assign what Forge should take from each photo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OptionRow("New reference role", ReferenceRole.entries.map { it.label }, newReferenceRole.label) { selected ->
                        newReferenceRole = ReferenceRole.entries.first { it.label == selected }
                    }
                    OutlinedButton(onClick = { extraPicker.launch("image/*") }, enabled = !busy && extraRefs.size < 2) { Text("Add references (${extraRefs.size}/2)") }
                    extraRefs.forEachIndexed { index, ref ->
                        Card {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AsyncImage(model = ref.file ?: ref.uri, contentDescription = null, modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                                OptionRow("Image ${index + 2} role", ReferenceRole.entries.map { it.label }, ref.role.label) { selected ->
                                    val role = ReferenceRole.entries.first { it.label == selected }
                                    extraRefs = extraRefs.map { if (it.id == ref.id) it.copy(role = role) else it }
                                }
                                TextButton(onClick = { extraRefs = extraRefs.filterNot { it.id == ref.id } }) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        }

        if (characters.isNotEmpty()) {
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Character Bank", fontWeight = FontWeight.SemiBold)
                        val options = listOf("None") + characters.map { it.name }
                        val selectedName = selectedCharacter?.name ?: "None"
                        OptionRow("Use saved character", options, selectedName) { name ->
                            selectedCharacterId = characters.firstOrNull { it.name == name }?.id
                            includeAnatomy = false
                        }
                        selectedCharacter?.let { character ->
                            Text("Forge can pull the highest-priority character views into the remaining Qwen reference slots.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (character.references.any { it.sensitive } && character.adultAnatomyEnabled) {
                                ToggleRow("Include adult anatomy reference when a slot is available", includeAnatomy) { includeAnatomy = it }
                            }
                            character.portraitSeed?.let { seed ->
                                TextButton(onClick = { randomSeed = false; seedText = seed.toString() }) { Text("Load portrait seed: $seed") }
                            }
                            character.fullBodySeed?.let { seed ->
                                TextButton(onClick = { randomSeed = false; seedText = seed.toString() }) { Text("Load full-body seed: $seed") }
                            }
                        }
                    }
                }
            }
        }

        if (backgrounds.isNotEmpty()) {
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Background Bank", fontWeight = FontWeight.SemiBold)
                        val options = listOf("None") + backgrounds.map { it.name }
                        OptionRow("Use saved environment", options, selectedBackground?.name ?: "None") { name ->
                            selectedBackgroundId = backgrounds.firstOrNull { it.name == name }?.id
                        }
                        selectedBackground?.imageSeed?.let { seed ->
                            TextButton(onClick = { randomSeed = false; seedText = seed.toString() }) { Text("Load background seed: $seed") }
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
                placeholder = { Text("Example: Put the subject from image 2 into image 1 while preserving identity and matching the scene lighting.") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            PromptAiButtons(
                busy = promptBusy,
                actions = listOf(PromptAction.OPTIMIZE, PromptAction.IDENTITY, PromptAction.MERGE_REFERENCES, PromptAction.REALISTIC)
            ) { action ->
                if (prompt.isBlank()) { status = "Enter a rough edit prompt first."; return@PromptAiButtons }
                scope.launch {
                    promptBusy = true
                    status = "Prompt AI: ${action.label}…"
                    if (originalPrompt.isBlank()) originalPrompt = prompt
                    runCatching { runpodClient.optimizePrompt(PromptIntent.EDIT, action, prompt, referenceContext) }
                        .onSuccess { prompt = it; status = "Prompt optimized — review it before generating." }
                        .onFailure { status = it.message ?: "Prompt AI failed" }
                    promptBusy = false
                }
            }
        }

        item {
            OutlinedTextField(value = negative, onValueChange = { negative = it }, label = { Text("Avoid / negative instructions (optional)") }, modifier = Modifier.fillMaxWidth())
        }

        if (provider != EditProvider.RUNPOD_OPEN) {
            item {
                OptionRow(
                    "Output size",
                    listOf("1024*1024", "1024*1280", "1280*1024", "1280*1280", "1280*1536", "1536*1080"),
                    outputSize
                ) { outputSize = it }
            }
            item { OptionRow("Number of outputs", listOf("1", "2", "4"), outputCount.toString()) { outputCount = it.toInt() } }
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
                    if (sourceFile == null && sourceUri == null) { status = "Choose a base image first."; return@Button }
                    if (prompt.isBlank()) { status = "Describe the edit first."; return@Button }
                    if (provider == EditProvider.RUNPOD_OPEN && openContent && !adultAcknowledged) { status = "Confirm the adults-only boundary first."; return@Button }
                    scope.launch {
                        busy = true
                        results = emptyList()
                        status = "Preparing reference images…"
                        runCatching {
                            val baseData = when {
                                sourceFile != null -> MediaUtils.fileToJpegDataUri(sourceFile!!)
                                sourceUri != null -> MediaUtils.uriToJpegDataUri(context, sourceUri!!)
                                else -> error("Choose a base image first.")
                            }
                            val data = mutableListOf(baseData)
                            for (ref in extraRefs) {
                                if (data.size >= 3) break
                                data += when {
                                    ref.file != null -> MediaUtils.fileToJpegDataUri(ref.file)
                                    ref.uri != null -> MediaUtils.uriToJpegDataUri(context, ref.uri)
                                    else -> continue
                                }
                            }
                            selectedCharacter?.usableReferences(includeAnatomy && adultAcknowledged, limit = 10)?.forEach { ref ->
                                if (data.size < 3) data += MediaUtils.fileToJpegDataUri(File(ref.path))
                            }
                            selectedBackground?.references?.forEach { ref ->
                                if (data.size < 3) data += MediaUtils.fileToJpegDataUri(File(ref.path))
                            }

                            val finalPrompt = ForgePromptBuilder.editPrompt(
                                userPrompt = prompt,
                                explicitRoles = extraRefs.map { it.role },
                                character = selectedCharacter,
                                background = selectedBackground,
                                includeAnatomy = includeAnatomy && adultAcknowledged,
                                negativePrompt = if (provider == EditProvider.RUNPOD_OPEN) "" else negative
                            )
                            val referenceSummary = "$referenceContext Sent ${if (provider == EditProvider.RUNPOD_OPEN) 1 else data.size} image(s)."
                            val count = if (provider == EditProvider.RUNPOD_OPEN) 1 else outputCount
                            val lockedBaseSeed = SeedTools.resolve(randomSeed, seedText)
                            val files = mutableListOf<File>()
                            repeat(count) { index ->
                                val seed = if (randomSeed) SeedTools.randomSeed() else (lockedBaseSeed + index)
                                status = "Generating ${index + 1}/$count with ${provider.label}…"
                                val url = when (provider) {
                                    EditProvider.RUNPOD_MULTI -> runpodClient.generateQwenEdit2511(finalPrompt, data.take(3), outputSize, seed)
                                    EditProvider.RUNPOD_OPEN -> runpodClient.generateQwenEdit(prompt, baseData, negative, seed, openContent)
                                    EditProvider.REPLICATE -> replicateClient.generateQwen2511(finalPrompt, data.take(3), "match_input_image", seed)
                                }
                                val file = MediaUtils.downloadToInternal(context, url, CreationKind.IMAGE)
                                historyStore.add(
                                    CreationRecord(
                                        kind = CreationKind.IMAGE,
                                        model = provider.label,
                                        prompt = finalPrompt,
                                        originalPrompt = originalPrompt.ifBlank { prompt },
                                        localPath = file.absolutePath,
                                        seed = seed,
                                        settings = "size=$outputSize; output=${index + 1}/$count",
                                        referenceSummary = referenceSummary
                                    )
                                )
                                files += file
                            }
                            onHistoryChanged()
                            results = files
                            status = "Done · ${files.size} edited image${if (files.size == 1) "" else "s"}"
                        }.onFailure { status = it.message ?: "Edit failed" }
                        busy = false
                    }
                },
                enabled = !busy && !promptBusy,
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) { Text(if (busy) "Editing…" else "Generate edited image${if (outputCount > 1 && provider != EditProvider.RUNPOD_OPEN) "s" else ""}") }
        }

        status?.let { item { StatusCard(it, busy || promptBusy, openSettings) } }

        results.forEachIndexed { index, file ->
            item {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Result ${index + 1}", fontWeight = FontWeight.SemiBold)
                        ResultImage(file)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { sourceFile = file; sourceUri = null; results = emptyList(); status = "Result loaded as the new base image." }, modifier = Modifier.weight(1f)) { Text("Edit again") }
                            Button(onClick = { onAnimate(file) }, modifier = Modifier.weight(1f)) { Text("Animate") }
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                runCatching { MediaUtils.saveToGallery(context, file, CreationKind.IMAGE) }
                                    .onSuccess { status = "Saved to Pictures/Forge AI Studio" }
                                    .onFailure { status = it.message }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Save image") }
                    }
                }
            }
        }
    }
}
