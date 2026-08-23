package com.forgeai.studio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** RunPod Public Endpoints client used by Forge image, edit, video, and Prompt AI workflows. */
class RunpodClient(private val tokenProvider: () -> String?) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()

    suspend fun testToken(): String = withContext(Dispatchers.IO) {
        val token = requireToken()
        val request = Request.Builder()
            .url("https://api.runpod.ai/v2/qwen-image-t2i/health")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw RunpodApiException(response.code, errorText(body))
            "RunPod connected"
        }
    }

    suspend fun optimizePrompt(
        intent: PromptIntent,
        action: PromptAction,
        draft: String,
        contextSummary: String = ""
    ): String {
        require(draft.isNotBlank()) { "Enter a prompt first." }
        val instruction = buildString {
            append("You are Forge Prompt AI, a specialist prompt engineer for modern AI image and video generators. ")
            append("Rewrite the user's draft for the requested workflow and action. Preserve the creative intent and concrete facts. ")
            append("Use precise, model-friendly natural language. Do not add commentary, headings, quotes, markdown, or explanations. Return only the final prompt. ")
            append("Workflow: ${intent.label}. Action: ${action.label}. ")
            if (intent == PromptIntent.EDIT || intent == PromptIntent.CHARACTER) {
                append("For identity-sensitive edits, clearly separate what should change from what must remain consistent. ")
            }
            if (intent == PromptIntent.VIDEO) {
                append("Describe continuous natural motion, temporal consistency, stable anatomy, and camera behavior. Do not include the standard Forge video prefix because the app adds it automatically. ")
            }
            if (contextSummary.isNotBlank()) append("Available reference context: $contextSummary ")
            append("User draft: ").append(draft.trim())
        }
        return runTextSync(instruction, maxTokens = 520, temperature = if (action == PromptAction.SIMPLIFY) 0.2 else 0.35)
    }

    suspend fun generateQwenImage(
        prompt: String,
        negativePrompt: String,
        aspectRatio: String,
        seed: Long?,
        openContent: Boolean
    ): String {
        val input = JSONObject().apply {
            put("prompt", prompt)
            put("negative_prompt", negativePrompt)
            put("size", imageSize(aspectRatio))
            put("seed", seed ?: -1L)
            put("enable_safety_checker", !openContent)
        }
        return runSync("qwen-image-t2i", input, "image_url", timeoutMs = 6 * 60 * 1000L)
    }

    suspend fun generateQwenEdit(
        prompt: String,
        imageDataUriOrUrl: String,
        negativePrompt: String,
        seed: Long?,
        openContent: Boolean
    ): String {
        require(imageDataUriOrUrl.isNotBlank()) { "Choose an image to edit first." }
        val input = JSONObject().apply {
            put("prompt", prompt)
            put("image", imageDataUriOrUrl)
            put("negative_prompt", negativePrompt)
            put("seed", seed ?: -1L)
            put("output_format", "png")
            put("enable_safety_checker", !openContent)
        }
        return runSync("qwen-image-edit", input, "image_url", timeoutMs = 8 * 60 * 1000L)
    }

    suspend fun generateQwenEdit2511(
        prompt: String,
        images: List<String>,
        size: String,
        seed: Long?
    ): String {
        require(images.isNotEmpty()) { "Qwen Edit 2511 needs at least one image." }
        require(images.size <= 3) { "Qwen Edit 2511 accepts up to 3 images per edit." }
        val allowedSizes = setOf("1024*1024", "1024*1280", "1280*1024", "1280*1280", "1280*1536", "1536*1080")
        require(size in allowedSizes) { "Unsupported Qwen Edit 2511 output size." }
        val input = JSONObject().apply {
            put("prompt", prompt)
            put("images", JSONArray(images))
            put("size", size)
            put("seed", seed ?: -1L)
            put("output_format", "png")
        }
        return runSync("qwen-image-edit-2511", input, "image_url", timeoutMs = 8 * 60 * 1000L)
    }

    /** Stable quick path retained from v0.3. */
    suspend fun generateWan22Video(
        prompt: String,
        image: String?,
        negativePrompt: String,
        duration: Int,
        aspectRatio: String,
        seed: Long?,
        openContent: Boolean
    ): String {
        require(duration in listOf(5, 10)) { "RunPod WAN 2.5 supports 5 or 10 seconds." }
        val imageInput = image?.trim().orEmpty()
        validateImageInput(imageInput)
        @Suppress("UNUSED_VARIABLE") val requestedAspectRatio = aspectRatio
        val input = JSONObject().apply {
            put("prompt", prompt)
            put("image", imageInput)
            put("negative_prompt", negativePrompt)
            put("resolution", "720p")
            put("duration", duration)
            put("seed", seed ?: -1L)
            put("enable_prompt_expansion", false)
            put("enable_safety_checker", !openContent)
        }
        return runSync("wan-2-5", input, "video_url", timeoutMs = 20 * 60 * 1000L)
    }

    suspend fun generateWan26Video(
        prompt: String,
        image: String,
        negativePrompt: String,
        duration: Int,
        resolution: String,
        shotType: String,
        seed: Long?,
        promptExpansion: Boolean,
        openContent: Boolean
    ): String {
        validateImageInput(image)
        require(duration in listOf(5, 10, 15)) { "WAN 2.6 supports 5, 10, or 15 seconds." }
        val size = when (resolution) {
            "1080p" -> "1920*1080"
            else -> "1280*720"
        }
        require(shotType in setOf("single", "multi")) { "WAN 2.6 shot type must be single or multi." }
        val input = JSONObject().apply {
            put("prompt", prompt)
            put("image", image)
            if (negativePrompt.isNotBlank()) put("negative_prompt", negativePrompt)
            put("size", size)
            put("duration", duration)
            put("shot_type", shotType)
            put("seed", seed ?: -1L)
            put("enable_prompt_expansion", promptExpansion)
            put("enable_safety_checker", !openContent)
        }
        return runSync("wan-2-6-i2v", input, "video_url", timeoutMs = 25 * 60 * 1000L)
    }

    suspend fun generateKlingReferenceVideo(
        prompt: String,
        images: List<String>,
        negativePrompt: String,
        aspectRatio: String,
        duration: Int,
        seed: Long?,
        promptExpansion: Boolean,
        openContent: Boolean
    ): String {
        require(images.size in 1..10) { "Kling Character mode needs 1 to 10 reference images." }
        images.forEach(::validateImageInput)
        require(duration in 3..10) { "Kling Character mode supports 3 to 10 seconds." }
        require(aspectRatio in setOf("16:9", "9:16", "1:1")) { "Unsupported Kling aspect ratio." }
        val input = JSONObject().apply {
            put("prompt", prompt)
            put("images", JSONArray(images))
            if (negativePrompt.isNotBlank()) put("negative_prompt", negativePrompt)
            put("aspect_ratio", aspectRatio)
            put("duration", duration)
            put("seed", seed ?: -1L)
            put("enable_prompt_expansion", promptExpansion)
            put("enable_safety_checker", !openContent)
        }
        return runSync("kling-video-o1-r2v", input, "video_url", timeoutMs = 25 * 60 * 1000L)
    }

    private suspend fun runTextSync(prompt: String, maxTokens: Int, temperature: Double): String = withContext(Dispatchers.IO) {
        val token = requireToken()
        val input = JSONObject().apply {
            put("prompt", prompt)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            put("top_p", 0.9)
        }
        val payload = JSONObject().put("input", input).toString()
        val request = Request.Builder()
            .url("https://api.runpod.ai/v2/qwen3-32b-awq/runsync")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val initial = client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw RunpodApiException(response.code, errorText(body))
            JSONObject(body)
        }
        val final = when (initial.optString("status").uppercase()) {
            "COMPLETED", "" -> initial
            "FAILED", "ERROR", "CANCELLED", "TIMED_OUT" -> error(statusError(initial))
            else -> {
                val jobId = initial.optString("id")
                if (jobId.isBlank()) initial else pollForJson(token, "qwen3-32b-awq", jobId, 5 * 60 * 1000L)
            }
        }
        extractText(final).trim().removeSurrounding("\"").ifBlank { error("Prompt AI returned no text") }
    }

    private suspend fun runSync(
        endpoint: String,
        input: JSONObject,
        outputKey: String,
        timeoutMs: Long
    ): String = withContext(Dispatchers.IO) {
        val token = requireToken()
        val payload = JSONObject().put("input", input).toString()
        val request = Request.Builder()
            .url("https://api.runpod.ai/v2/$endpoint/runsync")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val initial = client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw RunpodApiException(response.code, errorText(body))
            JSONObject(body)
        }

        when (initial.optString("status").uppercase()) {
            "COMPLETED" -> outputUrl(initial, outputKey)
            "FAILED", "ERROR", "CANCELLED", "TIMED_OUT" -> error(statusError(initial))
            "IN_QUEUE", "IN_PROGRESS" -> {
                val jobId = initial.optString("id")
                if (jobId.isBlank()) error("RunPod did not return a job ID")
                outputUrl(pollForJson(token, endpoint, jobId, timeoutMs), outputKey)
            }
            else -> runCatching { outputUrl(initial, outputKey) }.getOrElse {
                val jobId = initial.optString("id")
                if (jobId.isBlank()) throw it
                outputUrl(pollForJson(token, endpoint, jobId, timeoutMs), outputKey)
            }
        }
    }

    private suspend fun pollForJson(token: String, endpoint: String, jobId: String, timeoutMs: Long): JSONObject {
        val started = System.currentTimeMillis()
        while (true) {
            if (System.currentTimeMillis() - started > timeoutMs) error("RunPod generation timed out. Job: $jobId")
            delay(2500)
            val request = Request.Builder()
                .url("https://api.runpod.ai/v2/$endpoint/status/$jobId")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            val status = client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw RunpodApiException(response.code, errorText(body))
                JSONObject(body)
            }
            when (status.optString("status").uppercase()) {
                "COMPLETED" -> return status
                "FAILED", "ERROR", "CANCELLED", "TIMED_OUT" -> error(statusError(status))
            }
        }
    }

    private fun extractText(result: JSONObject): String {
        fun fromValue(value: Any?): List<String> {
            return when (value) {
                null, JSONObject.NULL -> emptyList()
                is String -> listOf(value)
                is JSONArray -> buildList {
                    for (i in 0 until value.length()) addAll(fromValue(value.opt(i)))
                }
                is JSONObject -> {
                    val preferred = listOf("tokens", "text", "content", "message", "choices", "output")
                    for (key in preferred) {
                        val found = fromValue(value.opt(key))
                        if (found.isNotEmpty()) return found
                    }
                    emptyList()
                }
                else -> emptyList()
            }
        }
        return fromValue(result.opt("output")).joinToString("").ifBlank { fromValue(result).joinToString("") }
    }

    private fun outputUrl(result: JSONObject, preferredKey: String): String {
        val candidates = listOf(preferredKey, "video_url", "image_url", "result", "url")
        fun fromValue(value: Any?): String? = when (value) {
            is String -> value.takeIf { it.startsWith("https://") || it.startsWith("http://") || it.startsWith("data:") }
            is JSONObject -> {
                var found: String? = null
                for (key in candidates) {
                    found = fromValue(value.opt(key))
                    if (found != null) break
                }
                if (found == null) {
                    val keys = value.keys()
                    while (keys.hasNext() && found == null) found = fromValue(value.opt(keys.next()))
                }
                found
            }
            is JSONArray -> {
                var found: String? = null
                for (i in 0 until value.length()) {
                    found = fromValue(value.opt(i))
                    if (found != null) break
                }
                found
            }
            else -> null
        }
        val url = fromValue(result.opt("output")) ?: fromValue(result)
        if (!url.isNullOrBlank()) return url
        val jobId = result.optString("id")
        error("RunPod returned no $preferredKey${if (jobId.isNotBlank()) "\nJob: $jobId" else ""}\nResponse: ${result.toString().take(1800)}")
    }

    private fun statusError(result: JSONObject): String {
        val status = result.optString("status")
        val detail = result.optString("error").ifBlank {
            val output = result.opt("output")
            if (output == null || output == JSONObject.NULL) status else output.toString()
        }
        val jobId = result.optString("id")
        return buildString {
            append("RunPod ").append(detail)
            if (jobId.isNotBlank()) append("\nJob: ").append(jobId)
        }
    }

    private fun validateImageInput(image: String) {
        require(image.isNotBlank()) { "Choose an image first." }
        require(image.startsWith("data:image/") || image.startsWith("https://") || image.startsWith("http://")) {
            "RunPod needs an image URL or image data URI."
        }
    }

    private fun imageSize(aspectRatio: String): String = when (aspectRatio) {
        "16:9" -> "1344*768"
        "9:16" -> "768*1344"
        "4:3" -> "1152*864"
        "3:4" -> "864*1152"
        "3:2" -> "1216*832"
        "2:3" -> "832*1216"
        else -> "1024*1024"
    }

    private fun requireToken(): String = tokenProvider()?.takeIf { it.isNotBlank() }
        ?: throw RunpodApiException(401, "Add your RunPod API key in Settings first.")

    private fun errorText(body: String): String = runCatching {
        val o = JSONObject(body)
        o.optString("error").ifBlank { o.optString("detail") }.ifBlank { body }
    }.getOrDefault(body.ifBlank { "Unknown RunPod API error" })
}

class RunpodApiException(val code: Int, detail: String) : Exception("RunPod HTTP $code: $detail")
