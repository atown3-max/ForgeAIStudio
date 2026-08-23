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

/**
 * RunPod Public Endpoints client used by Forge's Edit and Video workflows.
 *
 * Forge can switch RunPod's optional model safety checker off for lawful adult generation.
 * Forge still keeps hard boundaries against sexual content involving minors and
 * non-consensual intimate imagery.
 */
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

    /**
     * Compatibility name retained so the current VideoStudio can call this without a UI rewrite.
     * The implementation now uses WAN 2.5 because WAN 2.2's long-duration worker can generate
     * mismatched intermediate frame sizes and fail its internal ffmpeg xfade step.
     */
    suspend fun generateWan22Video(
        prompt: String,
        image: String?,
        negativePrompt: String,
        duration: Int,
        aspectRatio: String,
        seed: Long?,
        openContent: Boolean
    ): String {
        require(duration in listOf(5, 10)) {
            "RunPod Open video currently supports 5 or 10 seconds. WAN 2.2 longer-duration stitching is disabled because of an upstream xfade bug."
        }

        val imageInput = image?.trim().orEmpty()
        require(imageInput.isNotBlank()) { "Choose a first frame for image-to-video." }
        require(
            imageInput.startsWith("data:image/") ||
                imageInput.startsWith("https://") ||
                imageInput.startsWith("http://")
        ) { "WAN needs an image URL or image data URI." }

        val input = JSONObject().apply {
            put("prompt", prompt)
            put("image", imageInput)
            put("negative_prompt", negativePrompt)
            put("size", if (aspectRatio == "9:16") "720*1280" else "1280*720")
            put("duration", duration)
            put("seed", seed ?: -1L)
            put("enable_prompt_expansion", false)
            put("enable_safety_checker", !openContent)
        }

        return runSync("wan-2-5", input, "video_url", timeoutMs = 20 * 60 * 1000L)
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
                pollForResult(token, endpoint, jobId, outputKey, timeoutMs)
            }
            else -> {
                runCatching { outputUrl(initial, outputKey) }.getOrElse {
                    val jobId = initial.optString("id")
                    if (jobId.isBlank()) throw it
                    pollForResult(token, endpoint, jobId, outputKey, timeoutMs)
                }
            }
        }
    }

    private suspend fun pollForResult(
        token: String,
        endpoint: String,
        jobId: String,
        outputKey: String,
        timeoutMs: Long
    ): String {
        val started = System.currentTimeMillis()
        while (true) {
            if (System.currentTimeMillis() - started > timeoutMs) {
                error("RunPod generation timed out. Job: $jobId")
            }
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
                "COMPLETED" -> return outputUrl(status, outputKey)
                "FAILED", "ERROR", "CANCELLED", "TIMED_OUT" -> error(statusError(status))
            }
        }
    }

    private fun outputUrl(result: JSONObject, preferredKey: String): String {
        val candidates = listOf(preferredKey, "video_url", "image_url", "result", "url")

        fun fromValue(value: Any?): String? = when (value) {
            is String -> value.takeIf {
                it.startsWith("https://") || it.startsWith("http://") || it.startsWith("data:")
            }
            is JSONObject -> {
                var found: String? = null
                for (key in candidates) {
                    found = fromValue(value.opt(key))
                    if (found != null) break
                }
                if (found == null) {
                    val keys = value.keys()
                    while (keys.hasNext() && found == null) {
                        found = fromValue(value.opt(keys.next()))
                    }
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

        val output = result.opt("output")
        val jobId = result.optString("id")
        val raw = result.toString().take(1800)
        error(buildString {
            append("RunPod returned no ").append(preferredKey)
            if (jobId.isNotBlank()) append("\nJob: ").append(jobId)
            if (output != null && output != JSONObject.NULL) append("\nOutput: ").append(output.toString().take(1200))
            append("\nResponse: ").append(raw)
        })
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
