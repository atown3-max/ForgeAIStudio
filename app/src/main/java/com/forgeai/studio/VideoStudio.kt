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
    WAN25_FAST("RunPod · WAN 2.5 Fast"),
    WAN26_ADVANCED("RunPod · WAN 2.6 Advanced"),
    KLING_CHARACTER("RunPod · Kling O1 Character References"),
    REPLICATE_LTX("Replicate · LTX 2.3 Fast")
}

@Composable
fun VideoStudio(
    client: ReplicateClient,
    runpodClient: RunpodClient,
    historyStore: HistoryStore,
    profileStore: ProfileStore,
    profileRefreshKey: Int,
    initialFile: File?,
    consumeInitialFile: () -> Unit,
    onHistoryChanged: () -> Unit,
    openSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val characters = remember(profileRefreshKey) { profileStore.loadCharacters() }
    val backgrounds = remember(profileRefreshKey) { profileStore.loadBackgrounds() }

    var provider by remember { mutableStateOf(VideoProvider.WAN25_FAST) }
    var firstUri by remember { mutableStateOf<Uri?>(null) }
    var lastUri by remember { mutableStateOf<Uri?>(null) }
    var firstFile by remember { mutableStateOf<File?>(null) }
    var extraReferenceUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedCharacterId by remember { mutableStateOf<String?>(null) }
    var selectedBackgroundId by remember { mutableStateOf<String?>(null) }
    var includeAnatomy by remember { mutableStateOf(false) }
    var anatomyAcknowledged by remember { mutableStateOf(false) }
    var preserveIdentity by remember { mutableStateOf(true) }
    var promptDetails by remember { mutableStateOf("") }
    var originalPrompt by remember { mutableStateOf("") }
    var negative by remember { mutableStateOf("") }
    var duration by remember { mutableIntStateOf(5) }
    var wanResolution by remember { mutableStateOf("720p") }
    var ltxResolution by remember { mutableStateOf("1080p") }
    var shotType by remember { mutableStateOf("single") }
    var ratio by remember { mutableStateOf("16:9") }
    var fps by remember { mutableIntStateOf(25) }
    var motion by remember { mutableStateOf("none") }
    var audio by remember { mutableStateOf(true) }
    var promptExpansion by remember { mutableStateOf(false) }
    var seedText by remember { mutableStateOf("") }
    var randomSeed by remember { mutableStateOf(true) }
    var openContent by remember { mutableStateOf(true) }
    var adultAcknowledged by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var promptBusy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<File?>(null) }

    val selectedCharacter = characters.firstOrNull { it.id == selectedCharacterId }
    val selectedBackground = backgrounds.firstOrNull { it.id == selectedBackgroundId }

    LaunchedEffect(initialFile?.absolutePath) {
        if (initialFile != null) {
            firstFile = initialFile
            firstUri = null
            result = null
            status = "Image loaded. Add motion details or generate with the default cinematic prompt."
            consumeInitialFile()
        }
    }

    LaunchedEffect(provider) {
        when (provider) {
            VideoProvider.WAN25_FAST -> if (duration !in listOf(5, 10)) duration = 5
            VideoProvider.WAN26_ADVANCED -> if (duration !in listOf(5, 10, 15)) duration = 5
            VideoProvider.KLING_CHARACTER -> if (duration !in 3..10) duration = 5
            VideoProvider.REPLICATE_LTX -> if (duration !in listOf(6, 8, 10, 12, 14, 16, 18, 20)) duration = 6
        }
        if (provider != VideoProvider.REPLICATE_LTX) lastUri = null
        if (provider != VideoProvider.KLING_CHARACTER) extraReferenceUris = emptyList()
    }

    val firstPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { firstUri = uri; firstFile = null; result = null }
    }
    val lastPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) lastUri = uri }
    val multiPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) extraReferenceUris = uris.take(9)
    }

    val invalidLongSetting = provider == VideoProvider.REPLICATE_LTX && duration > 10 && (ltxResolution != "1080p" || fps !in listOf(24, 25))
    val isRunPod = provider != VideoProvider.REPLICATE_LTX
    val referenceContext = buildString {
        selectedCharacter?.let { append("Character ${it.name}; locked traits: ${it.lockedTraits.joinToString()}. ") }
        selectedBackground?.let { append("Background ${it.name}. ") }
        if (extraReferenceUris.isNotEmpty()) append("${extraReferenceUris.size} extra visual references. ")
    }.trim()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            StudioHeader(
                "Video · Image to Video",
                "Use WAN for animation, Kling O1 for multi-view character continuity, or LTX for its extended controls."
            )
        }
        item { OptionRow("Provider", VideoProvider.entries.map { it.label }, provider.label) { selected -> provider = VideoProvider.entries.first { it.label == selected } } }

        if (isRunPod) {
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToggleRow("Open content (adults only)", openContent) { openContent = it }
                        if (openContent) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("I understand the adults-only boundary", Modifier.weight(1f))
                                Checkbox(checked = adultAcknowledged, onCheckedChange = { adultAcknowledged = it })
                            }
                            Text("No sexual content involving minors and no non-consensual intimate imagery.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (provider == VideoProvider.KLING_CHARACTER) "Primary visual reference" else "First frame", fontWeight = FontWeight.SemiBold)
                    val source: Any? = firstFile ?: firstUri
                    if (source != null) {
                        AsyncImage(model = source, contentDescription = null, modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                    } else Text("Choose the image you want to animate.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { firstPicker.launch("image/*") }, enabled = !busy) { Text(if (source == null) "Choose image" else "Change image") }
                        if (source != null) TextButton(onClick = { firstFile = null; firstUri = null; result = null }) { Text("Clear") }
                    }
                }
            }
        }

        if (provider == VideoProvider.KLING_CHARACTER) {
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Additional viewpoints / references", fontWeight = FontWeight.SemiBold)
                        Text("Kling O1 can use up to 10 total images. Add front, side, 3/4, full-body, prop, or scene views for stronger continuity.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { multiPicker.launch("image/*") }, enabled = !busy) { Text("Choose extra references (${extraReferenceUris.size}/9)") }
                        if (extraReferenceUris.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                extraReferenceUris.take(4).forEach { uri -> AsyncImage(model = uri, contentDescription = null, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop) }
                            }
                            TextButton(onClick = { extraReferenceUris = emptyList() }) { Text("Clear extra references") }
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
                        lastUri?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { lastPicker.launch("image/*") }, enabled = firstFile != null || firstUri != null) { Text("Choose last frame") }
                            if (lastUri != null) TextButton(onClick = { lastUri = null }) { Text("Clear") }
                        }
                    }
                }
            }
        }

        if (characters.isNotEmpty()) {
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("Character continuity", fontWeight = FontWeight.SemiBold)
                        val options = listOf("None") + characters.map { it.name }
                        OptionRow("Character Bank", options, selectedCharacter?.name ?: "None") { name ->
                            selectedCharacterId = characters.firstOrNull { it.name == name }?.id
                            includeAnatomy = false
                            anatomyAcknowledged = false
                        }
                        selectedCharacter?.let { character ->
                            Text("${character.references.size} saved views · locked: ${character.lockedTraits.joinToString()}", style = MaterialTheme.typography.bodySmall)
                            if (character.references.any { it.sensitive } && character.adultAnatomyEnabled) {
                                ToggleRow("Include adult anatomy reference", includeAnatomy) { includeAnatomy = it }
                                if (includeAnatomy) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Adult structural reference only", Modifier.weight(1f))
                                        Checkbox(checked = anatomyAcknowledged, onCheckedChange = { anatomyAcknowledged = it })
                                    }
                                }
                            }
                            character.videoSeed?.let { seed -> TextButton(onClick = { randomSeed = false; seedText = seed.toString() }) { Text("Load character video seed: $seed") } }
                        }
                    }
                }
            }
        }

        if (backgrounds.isNotEmpty()) {
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("Environment continuity", fontWeight = FontWeight.SemiBold)
                        val options = listOf("None") + backgrounds.map { it.name }
                        OptionRow("Background Bank", options, selectedBackground?.name ?: "None") { name -> selectedBackgroundId = backgrounds.firstOrNull { it.name == name }?.id }
                        selectedBackground?.videoSeed?.let { seed -> TextButton(onClick = { randomSeed = false; seedText = seed.toString() }) { Text("Load background video seed: $seed") } }
                    }
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Automatic prompt start", fontWeight = FontWeight.SemiBold)
                    Text(VIDEO_PROMPT_PREFIX, color = MaterialTheme.colorScheme.primary)
                    Text("Forge places this at the beginning of every video prompt.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            OutlinedTextField(
                value = promptDetails,
                onValueChange = { promptDetails = it },
                label = { Text("Add motion / shot details") },
                placeholder = { Text("Example: she smiles, turns toward camera, then walks forward as her hair moves naturally in the breeze") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            PromptAiButtons(busy = promptBusy, actions = listOf(PromptAction.OPTIMIZE, PromptAction.CINEMATIC, PromptAction.IDENTITY, PromptAction.SIMPLIFY)) { action ->
                if (promptDetails.isBlank()) { status = "Enter rough motion details first."; return@PromptAiButtons }
                scope.launch {
                    promptBusy = true
                    status = "Prompt AI: ${action.label}…"
                    if (originalPrompt.isBlank()) originalPrompt = promptDetails
                    runCatching { runpodClient.optimizePrompt(PromptIntent.VIDEO, action, promptDetails, referenceContext) }
                        .onSuccess { promptDetails = it; status = "Video prompt optimized — review it before generating." }
                        .onFailure { status = it.message ?: "Prompt AI failed" }
                    promptBusy = false
                }
            }
        }
        item { ToggleRow("Strong identity continuity instructions", preserveIdentity) { preserveIdentity = it } }
        item { OutlinedTextField(value = negative, onValueChange = { negative = it }, label = { Text("Negative prompt / avoid (optional)") }, modifier = Modifier.fillMaxWidth()) }

        when (provider) {
            VideoProvider.WAN25_FAST -> {
                item { OptionRow("Duration", listOf("5", "10"), duration.toString()) { duration = it.toInt() } }
                item { Card { Column(Modifier.padding(14.dp)) { Text("WAN 2.5 Fast", fontWeight = FontWeight.SemiBold); Text("720p · reliable quick path", color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
            }
            VideoProvider.WAN26_ADVANCED -> {
                item { OptionRow("Duration", listOf("5", "10", "15"), duration.toString()) { duration = it.toInt() } }
                item { OptionRow("Resolution", listOf("720p", "1080p"), wanResolution) { wanResolution = it } }
                item { OptionRow("Shot type", listOf("single", "multi"), shotType) { shotType = it } }
                item { ToggleRow("WAN prompt expansion", promptExpansion) { promptExpansion = it } }
            }
            VideoProvider.KLING_CHARACTER -> {
                item { OptionRow("Duration", (3..10).map { it.toString() }, duration.toString()) { duration = it.toInt() } }
                item { OptionRow("Aspect ratio", listOf("16:9", "9:16", "1:1"), ratio) { ratio = it } }
                item { ToggleRow("Kling prompt expansion", promptExpansion) { promptExpansion = it } }
            }
            VideoProvider.REPLICATE_LTX -> {
                item { OptionRow("Duration", listOf("6", "8", "10", "12", "14", "16", "18", "20"), duration.toString()) { duration = it.toInt() } }
                item { OptionRow("Resolution", listOf("1080p", "2k", "4k"), ltxResolution) { ltxResolution = it } }
                item { OptionRow("Aspect ratio", listOf("16:9", "9:16"), ratio) { ratio = it } }
                item { OptionRow("FPS", listOf("24", "25", "48", "50"), fps.toString()) { fps = it.toInt() } }
                item { OptionRow("Camera", listOf("none", "static", "dolly_in", "dolly_out", "dolly_left", "dolly_right", "jib_up", "jib_down", "focus_shift"), motion) { motion = it } }
                item { ToggleRow("Generate synchronized audio", audio) { audio = it } }
                if (invalidLongSetting) item { Text("12–20 second LTX clips require 1080p at 24 or 25 FPS.", color = MaterialTheme.colorScheme.error) }
            }
        }

        item {
            SeedControls(randomSeed, seedText, historyStore.latestSeed(CreationKind.VIDEO), { randomSeed = it }, { seedText = it })
        }

        item {
            Button(
                onClick = {
                    if (firstFile == null && firstUri == null) { status = "Choose a first image before generating video."; return@Button }
                    if (invalidLongSetting) { status = "Fix the duration/resolution/FPS combination first."; return@Button }
                    if (isRunPod && openContent && !adultAcknowledged) { status = "Confirm the adults-only boundary first."; return@Button }
                    if (includeAnatomy && !anatomyAcknowledged) { status = "Confirm the adult anatomy-reference boundary first."; return@Button }
                    scope.launch {
                        busy = true
                        result = null
                        status = "Preparing video references…"
                        runCatching {
                            val firstData = when {
                                firstFile != null -> MediaUtils.fileToJpegDataUri(firstFile!!)
                                firstUri != null -> MediaUtils.uriToJpegDataUri(context, firstUri!!)
                                else -> error("Choose a first image.")
                            }
                            val enrichedDetails = ForgePromptBuilder.videoDetails(promptDetails, selectedCharacter, selectedBackground, preserveIdentity)
                            val fullPrompt = buildForgeVideoPrompt(enrichedDetails)
                            val seed = SeedTools.resolve(randomSeed, seedText)

                            val url = when (provider) {
                                VideoProvider.WAN25_FAST -> {
                                    status = "Generating with WAN 2.5…"
                                    runpodClient.generateWan22Video(fullPrompt, firstData, negative, duration, ratio, seed, openContent)
                                }
                                VideoProvider.WAN26_ADVANCED -> {
                                    status = "Generating with WAN 2.6 $wanResolution…"
                                    runpodClient.generateWan26Video(fullPrompt, firstData, negative, duration, wanResolution, shotType, seed, promptExpansion, openContent)
                                }
                                VideoProvider.KLING_CHARACTER -> {
                                    val refs = mutableListOf(firstData)
                                    for (uri in extraReferenceUris) {
                                        if (refs.size >= 10) break
                                        refs += MediaUtils.uriToJpegDataUri(context, uri)
                                    }
                                    selectedCharacter?.usableReferences(includeAnatomy && anatomyAcknowledged, limit = 10)?.forEach { ref -> if (refs.size < 10) refs += MediaUtils.fileToJpegDataUri(File(ref.path)) }
                                    selectedBackground?.references?.forEach { ref -> if (refs.size < 10) refs += MediaUtils.fileToJpegDataUri(File(ref.path)) }
                                    status = "Generating with Kling O1 using ${refs.size} reference image${if (refs.size == 1) "" else "s"}…"
                                    runpodClient.generateKlingReferenceVideo(fullPrompt, refs.take(10), negative, ratio, duration, seed, promptExpansion, openContent)
                                }
                                VideoProvider.REPLICATE_LTX -> {
                                    val lastData = lastUri?.let { MediaUtils.uriToJpegDataUri(context, it) }
                                    status = "Generating with LTX 2.3…"
                                    client.generateLtx23(fullPrompt, firstData, lastData, duration, ltxResolution, ratio, fps, motion, audio)
                                }
                            }

                            status = "Saving video locally…"
                            val file = MediaUtils.downloadToInternal(context, url, CreationKind.VIDEO)
                            val settings = when (provider) {
                                VideoProvider.WAN25_FAST -> "duration=${duration}s; resolution=720p"
                                VideoProvider.WAN26_ADVANCED -> "duration=${duration}s; resolution=$wanResolution; shot=$shotType"
                                VideoProvider.KLING_CHARACTER -> "duration=${duration}s; ratio=$ratio; characterRefs=${selectedCharacter?.references?.size ?: 0}"
                                VideoProvider.REPLICATE_LTX -> "duration=${duration}s; resolution=$ltxResolution; ratio=$ratio; fps=$fps; camera=$motion; audio=$audio"
                            }
                            historyStore.add(
                                CreationRecord(
                                    kind = CreationKind.VIDEO,
                                    model = provider.label,
                                    prompt = fullPrompt,
                                    originalPrompt = originalPrompt.ifBlank { promptDetails },
                                    localPath = file.absolutePath,
                                    seed = seed,
                                    settings = settings,
                                    referenceSummary = referenceContext
                                )
                            )
                            onHistoryChanged()
                            result = file
                            status = "Done"
                        }.onFailure { status = it.message ?: "Video generation failed" }
                        busy = false
                    }
                },
                enabled = !busy && !promptBusy && !invalidLongSetting,
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) { Text(if (busy) "Generating…" else "Generate video") }
        }

        status?.let { item { StatusCard(it, busy || promptBusy, openSettings) } }
        result?.let { file ->
            item { VideoPlayer(file) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                status = "Extracting final frame…"
                                runCatching { MediaUtils.extractLastFrame(context, file) }
                                    .onSuccess { frame -> firstFile = frame; firstUri = null; result = null; status = "Final frame loaded. Write the next shot and continue." }
                                    .onFailure { status = it.message ?: "Could not extract final frame" }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Continue shot") }
                    OutlinedButton(
                        onClick = {
                            val uri = MediaUtils.shareUri(context, file)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "Share video"))
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Share") }
                }
            }
            item {
                OutlinedButton(onClick = {
                    scope.launch {
                        runCatching { MediaUtils.saveToGallery(context, file, CreationKind.VIDEO) }
                            .onSuccess { status = "Saved to Movies/Forge AI Studio" }
                            .onFailure { status = it.message }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Save video") }
            }
        }
    }
}
