package com.forgeai.studio

import android.content.Intent
import android.graphics.ImageDecoder
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable
fun RecastStudio(historyStore: HistoryStore, profileStore: ProfileStore, profileRefreshKey: Int,
                 onHistoryChanged: () -> Unit, openSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { RecastStore(context) }
    val secrets = remember { SecureTokenStore(context) }
    val client = remember { RecastClient { secrets.loadRecast() } }
    val characters = remember(profileRefreshKey) { profileStore.loadCharacters() }
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var bankImage by remember { mutableStateOf<StoredReference?>(null) }
    var selectedCharacter by remember { mutableStateOf<CharacterProfile?>(null) }
    var start by remember { mutableStateOf("0") }
    var duration by remember { mutableIntStateOf(2) }
    var resolution by remember { mutableStateOf("480p") }
    var keepAudio by remember { mutableStateOf(true) }
    var acknowledged by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(store.pending()) }
    var retry by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var terminal by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<File?>(null) }
    var confirmForget by remember { mutableStateOf(false) }

    if (confirmForget) AlertDialog(onDismissRequest = { confirmForget = false },
        title = { Text("Forget this job?") },
        text = { Text("This removes the saved job and upload files from this phone. It does not cancel rendering on the worker, which may still use GPU time. You will lose this screen's link to its result.") },
        confirmButton = { TextButton(onClick = {
            pending?.let { store.clear(it) }; pending = null; paused = false; status = null; confirmForget = false
        }) { Text("Forget job") } },
        dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Keep job") } })

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { videoUri = uri; result = null }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { imageUri = uri; bankImage = null; selectedCharacter = null; result = null }
    }

    LaunchedEffect(pending?.id, retry) {
        val job = pending ?: return@LaunchedEffect
        busy = true; paused = false; terminal = false
        try {
            check(store.endpoint() == job.endpoint) { "Restore this job's worker address in Settings: ${job.endpoint}" }
            status = "Uploading or reconnecting… Keep Forge open until the upload is accepted."
            var remote = client.submitOrResume(job)
            while (true) {
                when (remote.getString("status")) {
                    "COMPLETED" -> {
                        status = "Saving recast video…"
                        val file = client.download(context, job)
                        if (historyStore.load().none { it.id == "recast_${job.id}" }) {
                            historyStore.add(CreationRecord(id = "recast_${job.id}", kind = CreationKind.VIDEO,
                                model = "Wan2.2-Animate-14B · Recast", prompt = "Replace the person using the character reference.",
                                localPath = file.absolutePath, seed = job.seed,
                                settings = "start=${job.start}s; duration=${job.duration}s; resolution=${job.resolution}; fps=30; originalAudio=${job.keepAudio}",
                                referenceSummary = job.reference))
                        }
                        store.clear(job); pending = null; result = file
                        onHistoryChanged(); status = "Recast saved."
                        break
                    }
                    "FAILED" -> { terminal = true; status = remote.optString("error", "Recast failed."); break }
                    "QUEUED", "RUNNING" -> status = "${remote.optString("stage")} · You can leave this screen and return later."
                    else -> error("Unknown worker status. Your job is saved; retry checking it.")
                }
                delay(4000)
                remote = client.status(job)
            }
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            terminal = error is RecastApiException && error.status in listOf(410, 413, 422)
            paused = !terminal
            status = "${error.message ?: "Connection interrupted."}${if (!terminal) " Your job is saved on this phone." else ""}"
        } finally { busy = false }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { StudioHeader("Recast", "Replace the person in your video with a character from an image.") }
        item { Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Wan2.2-Animate-14B", fontWeight = FontWeight.Bold)
            Text("Preview · 2–10 seconds · one visible person. Use a clear character photo, preferably showing the full body. Multiple-person clips aren't supported in this first version.")
            if (store.endpoint().isBlank()) {
                Text("Connect your Recast worker before generating.")
                OutlinedButton(onClick = openSettings) { Text("Set up Recast") }
            }
        } } }
        if (pending == null) {
            item { Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1 · Source video", fontWeight = FontWeight.Bold)
                videoUri?.let { VideoPlayer(it, compact = true) }
                Text(if (videoUri == null) "Choose an MP4 or MOV, up to 40 MB." else "Video selected. The section below will be recast.")
                OutlinedButton(onClick = { videoPicker.launch(arrayOf("video/*")) }, enabled = !busy) { Text("Choose video") }
                OutlinedTextField(value = start, onValueChange = { start = it }, enabled = !busy,
                    label = { Text("Start time in seconds") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OptionRow("Clip length", listOf("2", "5", "10"), duration.toString()) { if (!busy) duration = it.toInt() }
            } } }
            item { Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2 · Replacement character", fontWeight = FontWeight.Bold)
                val source: Any? = bankImage?.let { File(it.path) } ?: imageUri
                source?.let { AsyncImage(model = it, contentDescription = "Replacement character", modifier = Modifier.fillMaxWidth().height(220.dp)) }
                OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }, enabled = !busy) { Text("Choose character image") }
                if (characters.isNotEmpty()) {
                    Text("Or use your Character Bank:")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        characters.forEach { character ->
                            FilterChip(selected = selectedCharacter?.id == character.id, enabled = !busy,
                                onClick = {
                                    selectedCharacter = character; imageUri = null
                                    bankImage = character.references.firstOrNull { !it.sensitive && it.role == CharacterViewRole.FULL_BODY.name }
                                        ?: character.usableReferences(false).firstOrNull()
                                }, label = { Text(character.name) })
                        }
                    }
                    selectedCharacter?.let { character ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            character.usableReferences(false).forEach { ref ->
                                FilterChip(selected = bankImage?.id == ref.id, enabled = !busy,
                                    onClick = { bankImage = ref }, label = { Text(ref.label.ifBlank { ref.role.replace('_', ' ') }) })
                            }
                        }
                        if (bankImage == null) Text("Add a reference image to this character first.")
                    }
                }
            } } }
            item { OptionRow("Quality", listOf("480p", "720p"), resolution) { if (!busy) resolution = it } }
            item { ToggleRow("Keep original audio", keepAudio) { if (!busy) keepAudio = it } }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = acknowledged, onCheckedChange = { if (!busy) acknowledged = it })
                Text("I have permission to use the footage and character. No sexual content involving minors or non-consensual intimate imagery.", style = MaterialTheme.typography.bodySmall)
            } }
            item { Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && videoUri != null &&
                (imageUri != null || bankImage != null) && acknowledged && store.endpoint().isNotBlank(), onClick = {
                scope.launch {
                    busy = true; result = null; status = "Preparing upload…"
                    var folder: File? = null
                    try {
                        val offset = start.toDoubleOrNull()
                        require(offset != null && offset.isFinite() && offset in 0.0..600.0) { "Enter a start time between 0 and 600 seconds." }
                        val endpoint = recastEndpoint(store.endpoint())
                        client.health(endpoint)
                        val id = UUID.randomUUID().toString()
                        val jobFolder = File(context.filesDir, "recast_uploads/$id").apply { mkdirs() }
                        folder = jobFolder
                        val sourceVideo = videoUri!!
                        val sourceImage = imageUri
                        val sourceBank = bankImage
                        val referenceName = selectedCharacter?.let { "Character Bank: ${it.name}; view=${sourceBank?.role}" } ?: "Uploaded character image"
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(sourceVideo)?.use { input ->
                                File(jobFolder, "source.video").outputStream().use { output ->
                                    val buffer = ByteArray(65536); var total = 0L
                                    while (true) {
                                        val read = input.read(buffer); if (read < 0) break
                                        total += read
                                        require(total <= 40L * 1024 * 1024) { "Video is larger than 40 MB. Trim or compress it first." }
                                        output.write(buffer, 0, read)
                                    }
                                    require(total > 0) { "The selected video is empty." }
                                }
                            } ?: error("Could not open the video.")
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(File(jobFolder, "source.video").absolutePath)
                                val seconds = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
                                require(offset + duration <= seconds + 0.1) { "The selected section extends beyond the video. Choose a shorter section." }
                            } finally { retriever.release() }
                            val imageSource = if (sourceBank != null) ImageDecoder.createSource(File(sourceBank.path))
                                else ImageDecoder.createSource(context.contentResolver, sourceImage!!)
                            val bitmap = ImageDecoder.decodeBitmap(imageSource) { decoder, info, _ ->
                                val scale = minOf(1f, 1536f / maxOf(info.size.width, info.size.height))
                                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            }
                            try { File(jobFolder, "reference.jpg").outputStream().use { output ->
                                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) { "Could not prepare character image." }
                            } } finally { bitmap.recycle() }
                        }
                        val job = RecastJob(id, endpoint, File(jobFolder, "source.video").absolutePath,
                            File(jobFolder, "reference.jpg").absolutePath, referenceName, offset, duration,
                            resolution, SeedTools.randomSeed(), keepAudio)
                        store.save(job); folder = null; pending = job
                    } catch (error: CancellationException) { throw error
                    } catch (error: Exception) { status = error.message
                    } finally { folder?.deleteRecursively(); busy = false }
                }
            }) { Text(if (busy) "Preparing…" else "Recast video") } }
        }
        status?.let { item { StatusCard(it, busy, openSettings) } }
        if (pending != null && paused) item {
            Column {
                OutlinedButton(onClick = { retry++ }, enabled = !busy) { Text("Reconnect / retry upload") }
                TextButton(onClick = { confirmForget = true }, enabled = !busy) { Text("Forget saved job…") }
            }
        }
        if (pending != null && terminal) item {
            OutlinedButton(onClick = { pending?.let { store.clear(it) }; pending = null; terminal = false; status = null }) { Text("Clear failed job") }
        }
        result?.let { file ->
            item { VideoPlayer(file) }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch {
                    try { MediaUtils.saveToGallery(context, file, CreationKind.VIDEO); status = "Saved to Movies/Forge AI Studio." }
                    catch (error: Exception) { status = error.message }
                } }, modifier = Modifier.weight(1f)) { Text("Save video") }
                OutlinedButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, MediaUtils.shareUri(context, file))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share recast video"))
                }, modifier = Modifier.weight(1f)) { Text("Share") }
            } }
        }
    }
}
