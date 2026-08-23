package com.forgeai.studio

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import java.io.File

@Composable
fun ResultImage(file: File) {
    AsyncImage(
        model = file,
        contentDescription = null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 520.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black),
        contentScale = ContentScale.Fit
    )
}

@Composable
fun VideoPlayer(file: File, compact: Boolean = false) {
    val context = LocalContext.current
    val player = remember(file.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
        update = { it.player = player },
        modifier = Modifier.fillMaxWidth().height(if (compact) 210.dp else 300.dp).clip(RoundedCornerShape(14.dp))
    )
}

@Composable
fun OptionRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options.size) { i ->
                val option = options[i]
                FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(option) })
            }
        }
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun PromptAiButtons(
    busy: Boolean,
    actions: List<PromptAction> = listOf(PromptAction.OPTIMIZE, PromptAction.IDENTITY, PromptAction.CINEMATIC),
    onAction: (PromptAction) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Prompt AI", fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(actions) { action ->
                AssistChip(
                    onClick = { onAction(action) },
                    enabled = !busy,
                    label = { Text("✨ ${action.label}") }
                )
            }
        }
        Text(
            "Uses RunPod Qwen3 to rewrite your draft for the selected workflow. You can edit the result before generating.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SeedControls(
    randomSeed: Boolean,
    seedText: String,
    lastSeed: Long?,
    onRandomChange: (Boolean) -> Unit,
    onSeedTextChange: (String) -> Unit
) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Seed", fontWeight = FontWeight.SemiBold)
            Text(
                "A seed is the generation's random starting point. Lock or reuse one for controlled variations; references remain the stronger identity anchor.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ToggleRow("Random seed each run", randomSeed) { onRandomChange(it) }
            OutlinedTextField(
                value = seedText,
                onValueChange = { onSeedTextChange(it.filter(Char::isDigit).take(10)) },
                label = { Text(if (randomSeed) "Seed generated at run time" else "Locked seed") },
                enabled = !randomSeed,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        onRandomChange(false)
                        onSeedTextChange(SeedTools.randomSeed().toString())
                    }
                ) { Text("New locked seed") }
                if (lastSeed != null) {
                    TextButton(
                        onClick = {
                            onRandomChange(false)
                            onSeedTextChange(lastSeed.toString())
                        }
                    ) { Text("Reuse last: $lastSeed") }
                }
            }
        }
    }
}

@Composable
fun StatusCard(text: String, busy: Boolean, openSettings: () -> Unit) {
    Card {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(text, Modifier.weight(1f))
            if (text.contains("token", ignoreCase = true) || text.contains("API key", ignoreCase = true)) TextButton(onClick = openSettings) { Text("Settings") }
        }
    }
}
