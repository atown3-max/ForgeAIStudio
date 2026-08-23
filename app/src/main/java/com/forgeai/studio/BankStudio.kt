package com.forgeai.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File

private val CHARACTER_TRAITS = listOf("Face", "Hair", "Eyes", "Body proportions", "Height / build", "Age", "Wardrobe")

@Composable
fun BankScreen(
    profileStore: ProfileStore,
    refreshKey: Int,
    onChanged: () -> Unit
) {
    var tab by remember { mutableStateOf("Characters") }
    val characters = remember(refreshKey) { profileStore.loadCharacters() }
    val backgrounds = remember(refreshKey) { profileStore.loadBackgrounds() }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            OptionRow("Bank", listOf("Characters", "Backgrounds"), tab) { tab = it }
        }
        Box(Modifier.weight(1f)) {
            if (tab == "Characters") {
                CharacterBank(profileStore, characters, onChanged)
            } else {
                BackgroundBank(profileStore, backgrounds, onChanged)
            }
        }
    }
}

@Composable
private fun CharacterBank(
    profileStore: ProfileStore,
    characters: List<CharacterProfile>,
    onChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var editingId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var adultAnatomy by remember { mutableStateOf(false) }
    var portraitSeed by remember { mutableStateOf("") }
    var fullBodySeed by remember { mutableStateOf("") }
    var videoSeed by remember { mutableStateOf("") }
    var lockedTraits by remember { mutableStateOf(setOf("Face", "Body proportions")) }
    var refs by remember { mutableStateOf<List<StoredReference>>(emptyList()) }
    var newRole by remember { mutableStateOf(CharacterViewRole.FRONT) }
    var message by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    fun reset() {
        editingId = null
        name = ""
        notes = ""
        adultAnatomy = false
        portraitSeed = ""
        fullBodySeed = ""
        videoSeed = ""
        lockedTraits = setOf("Face", "Body proportions")
        refs = emptyList()
        newRole = CharacterViewRole.FRONT
    }

    fun load(profile: CharacterProfile) {
        editingId = profile.id
        name = profile.name
        notes = profile.notes
        adultAnatomy = profile.adultAnatomyEnabled
        portraitSeed = profile.portraitSeed?.toString().orEmpty()
        fullBodySeed = profile.fullBodySeed?.toString().orEmpty()
        videoSeed = profile.videoSeed?.toString().orEmpty()
        lockedTraits = profile.lockedTraits
        refs = profile.references
        message = "Editing ${profile.name}"
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                importing = true
                runCatching {
                    val imported = uris.take((10 - refs.size).coerceAtLeast(0)).map { uri ->
                        profileStore.importReference(
                            uri = uri,
                            bucket = editingId ?: "character_draft",
                            role = newRole.name,
                            sensitive = newRole.sensitive
                        )
                    }
                    refs = refs + imported
                }.onFailure { message = it.message }
                importing = false
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            StudioHeader(
                "Character Bank",
                "Save multi-angle identity references and seeds so characters stay more consistent across images and video."
            )
        }

        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (editingId == null) "New character" else "Edit character", fontWeight = FontWeight.Bold)
                    OutlinedTextField(name, { name = it }, label = { Text("Character name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(notes, { notes = it }, label = { Text("Identity / continuity notes") }, minLines = 2, modifier = Modifier.fillMaxWidth())

                    Text("Locked traits", fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        CHARACTER_TRAITS.forEach { trait ->
                            FilterChip(
                                selected = trait in lockedTraits,
                                onClick = {
                                    lockedTraits = if (trait in lockedTraits) lockedTraits - trait else lockedTraits + trait
                                },
                                label = { Text(trait) }
                            )
                        }
                    }

                    ToggleRow("Enable adult anatomy-reference slot", adultAnatomy) {
                        adultAnatomy = it
                        if (!it && newRole == CharacterViewRole.ANATOMY_REFERENCE) newRole = CharacterViewRole.FRONT
                    }
                    Text(
                        "Optional adult-only structural reference for non-sexual proportion consistency. Never use for minors or non-consensual intimate material. Stored locally in the app library.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val roles = CharacterViewRole.entries.filter { adultAnatomy || !it.sensitive }
                    OptionRow("Role for newly added photos", roles.map { it.label }, newRole.label) { selected ->
                        newRole = roles.first { it.label == selected }
                    }
                    Button(onClick = { picker.launch("image/*") }, enabled = !importing && refs.size < 10) {
                        Text(if (importing) "Importing…" else "Add reference photos (${refs.size}/10)")
                    }

                    refs.forEach { ref ->
                        val roleOptions = CharacterViewRole.entries.filter { adultAnatomy || !it.sensitive }
                        Card {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AsyncImage(
                                    model = File(ref.path),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                val currentRole = CharacterViewRole.entries.firstOrNull { it.name == ref.role } ?: CharacterViewRole.OTHER
                                OptionRow("View role", roleOptions.map { it.label }, currentRole.label) { selected ->
                                    val role = roleOptions.first { it.label == selected }
                                    refs = refs.map {
                                        if (it.id == ref.id) it.copy(role = role.name, sensitive = role.sensitive) else it
                                    }
                                }
                                TextButton(onClick = {
                                    runCatching { File(ref.path).delete() }
                                    refs = refs.filterNot { it.id == ref.id }
                                }) { Text("Remove photo") }
                            }
                        }
                    }

                    Text("Character seed bank", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(portraitSeed, { portraitSeed = it.filter(Char::isDigit).take(10) }, label = { Text("Portrait seed") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(fullBodySeed, { fullBodySeed = it.filter(Char::isDigit).take(10) }, label = { Text("Full-body seed") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(videoSeed, { videoSeed = it.filter(Char::isDigit).take(10) }, label = { Text("Video seed") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (name.isBlank()) {
                                    message = "Give the character a name first."
                                    return@Button
                                }
                                val profile = CharacterProfile(
                                    id = editingId ?: java.util.UUID.randomUUID().toString(),
                                    name = name.trim(),
                                    notes = notes.trim(),
                                    adultAnatomyEnabled = adultAnatomy,
                                    portraitSeed = portraitSeed.toLongOrNull(),
                                    fullBodySeed = fullBodySeed.toLongOrNull(),
                                    videoSeed = videoSeed.toLongOrNull(),
                                    lockedTraits = lockedTraits,
                                    references = refs
                                )
                                profileStore.saveCharacter(profile)
                                message = "Saved ${profile.name}"
                                reset()
                                onChanged()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Save character") }
                        if (editingId != null) {
                            OutlinedButton(onClick = { reset(); message = null }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                        }
                    }
                }
            }
        }

        message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }

        if (characters.isEmpty()) {
            item { Text("No saved characters yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        items(characters, key = { it.id }) { profile ->
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (profile.notes.isNotBlank()) Text(profile.notes, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (profile.references.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            profile.references.take(4).forEach { ref ->
                                AsyncImage(
                                    model = File(ref.path),
                                    contentDescription = null,
                                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }
                    Text("${profile.references.size} references · Locked: ${profile.lockedTraits.joinToString()}", style = MaterialTheme.typography.bodySmall)
                    Text("Seeds — portrait ${profile.portraitSeed ?: "—"} · body ${profile.fullBodySeed ?: "—"} · video ${profile.videoSeed ?: "—"}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { load(profile) }) { Text("Edit") }
                        TextButton(onClick = { profileStore.deleteCharacter(profile); onChanged() }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundBank(
    profileStore: ProfileStore,
    backgrounds: List<BackgroundProfile>,
    onChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var editingId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var imageSeed by remember { mutableStateOf("") }
    var videoSeed by remember { mutableStateOf("") }
    var refs by remember { mutableStateOf<List<StoredReference>>(emptyList()) }
    var newRole by remember { mutableStateOf(BackgroundViewRole.WIDE) }
    var message by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    fun reset() {
        editingId = null; name = ""; notes = ""; imageSeed = ""; videoSeed = ""; refs = emptyList(); newRole = BackgroundViewRole.WIDE
    }

    fun load(profile: BackgroundProfile) {
        editingId = profile.id
        name = profile.name
        notes = profile.notes
        imageSeed = profile.imageSeed?.toString().orEmpty()
        videoSeed = profile.videoSeed?.toString().orEmpty()
        refs = profile.references
        message = "Editing ${profile.name}"
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                importing = true
                runCatching {
                    val imported = uris.take((8 - refs.size).coerceAtLeast(0)).map { uri ->
                        profileStore.importReference(uri, editingId ?: "background_draft", newRole.name)
                    }
                    refs = refs + imported
                }.onFailure { message = it.message }
                importing = false
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { StudioHeader("Background Bank", "Save recurring locations, viewpoints, continuity notes, and seeds.") }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (editingId == null) "New background" else "Edit background", fontWeight = FontWeight.Bold)
                    OutlinedTextField(name, { name = it }, label = { Text("Background name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(notes, { notes = it }, label = { Text("Environment / lighting notes") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    OptionRow("Role for newly added photos", BackgroundViewRole.entries.map { it.label }, newRole.label) { selected ->
                        newRole = BackgroundViewRole.entries.first { it.label == selected }
                    }
                    Button(onClick = { picker.launch("image/*") }, enabled = !importing && refs.size < 8) {
                        Text(if (importing) "Importing…" else "Add background photos (${refs.size}/8)")
                    }
                    refs.forEach { ref ->
                        Card {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AsyncImage(model = File(ref.path), contentDescription = null, modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                                val current = BackgroundViewRole.entries.firstOrNull { it.name == ref.role } ?: BackgroundViewRole.OTHER
                                OptionRow("View role", BackgroundViewRole.entries.map { it.label }, current.label) { selected ->
                                    val role = BackgroundViewRole.entries.first { it.label == selected }
                                    refs = refs.map { if (it.id == ref.id) it.copy(role = role.name) else it }
                                }
                                TextButton(onClick = {
                                    runCatching { File(ref.path).delete() }
                                    refs = refs.filterNot { it.id == ref.id }
                                }) { Text("Remove photo") }
                            }
                        }
                    }
                    OutlinedTextField(imageSeed, { imageSeed = it.filter(Char::isDigit).take(10) }, label = { Text("Image seed") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(videoSeed, { videoSeed = it.filter(Char::isDigit).take(10) }, label = { Text("Video seed") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (name.isBlank()) { message = "Give the background a name first."; return@Button }
                            val profile = BackgroundProfile(
                                id = editingId ?: java.util.UUID.randomUUID().toString(),
                                name = name.trim(), notes = notes.trim(), imageSeed = imageSeed.toLongOrNull(), videoSeed = videoSeed.toLongOrNull(), references = refs
                            )
                            profileStore.saveBackground(profile)
                            message = "Saved ${profile.name}"
                            reset(); onChanged()
                        }, modifier = Modifier.weight(1f)) { Text("Save background") }
                        if (editingId != null) OutlinedButton(onClick = { reset(); message = null }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    }
                }
            }
        }
        message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        if (backgrounds.isEmpty()) item { Text("No saved backgrounds yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(backgrounds, key = { it.id }) { profile ->
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (profile.notes.isNotBlank()) Text(profile.notes, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (profile.references.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            profile.references.take(4).forEach { ref ->
                                AsyncImage(model = File(ref.path), contentDescription = null, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                            }
                        }
                    }
                    Text("${profile.references.size} references · image seed ${profile.imageSeed ?: "—"} · video seed ${profile.videoSeed ?: "—"}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { load(profile) }) { Text("Edit") }
                        TextButton(onClick = { profileStore.deleteBackground(profile); onChanged() }) { Text("Delete") }
                    }
                }
            }
        }
    }
}
