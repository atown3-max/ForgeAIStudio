package com.forgeai.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun PromptLabScreen(
    runpodClient: RunpodClient,
    openSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var intent by remember { mutableStateOf(PromptIntent.EDIT) }
    var action by remember { mutableStateOf(PromptAction.OPTIMIZE) }
    var draft by remember { mutableStateOf("") }
    var context by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { StudioHeader("Prompt Lab", "Use Qwen3 on RunPod to turn a rough idea into a model-ready image, edit, or video prompt.") }
        item { OptionRow("Workflow", PromptIntent.entries.map { it.label }, intent.label) { selected -> intent = PromptIntent.entries.first { it.label == selected } } }
        item { OptionRow("Goal", PromptAction.entries.map { it.label }, action.label) { selected -> action = PromptAction.entries.first { it.label == selected } } }
        item {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Your rough prompt") },
                minLines = 5,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            OutlinedTextField(
                value = context,
                onValueChange = { context = it },
                label = { Text("Reference / character context (optional)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Button(
                onClick = {
                    if (draft.isBlank()) { status = "Enter a rough prompt first."; return@Button }
                    scope.launch {
                        busy = true
                        status = "Optimizing with Qwen3…"
                        runCatching { runpodClient.optimizePrompt(intent, action, draft, context) }
                            .onSuccess { output = it; status = "Prompt ready" }
                            .onFailure { status = it.message ?: "Prompt AI failed" }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(if (busy) "Optimizing…" else "✨ Optimize prompt") }
        }
        status?.let { item { StatusCard(it, busy, openSettings) } }
        if (output.isNotBlank()) {
            item {
                OutlinedTextField(
                    value = output,
                    onValueChange = { output = it },
                    label = { Text("Optimized prompt") },
                    minLines = 7,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { draft = output; output = ""; status = "Optimized prompt moved into the editor." }, modifier = Modifier.weight(1f)) { Text("Use as draft") }
                    OutlinedButton(onClick = { output = "" }, modifier = Modifier.weight(1f)) { Text("Clear") }
                }
            }
        }
    }
}
