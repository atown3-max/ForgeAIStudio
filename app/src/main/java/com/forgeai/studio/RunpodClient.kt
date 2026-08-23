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
 * RunPod Public Endpoints provider used by Forge's optional Open mode.
 *
 * Open mode only changes the model/provider's optional safety-checker flag. Forge still keeps
 * an adults-only boundary for sexual content and does not support sexual content involving minors
 * or non-consensual intimate imagery.
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
        return runSync("qwen-image-t2i", input, "image_url")
    }

    suspend fun generateWan22Video(
        prompt: String,
        image: String?,
        negativePrompt: String,
        duration: Int,
        aspectRatio: String,
        seed: Long?,
        openContent: Boolean
    ): String {
        require(duration in listOf(5, 8, 10, 15)) { "WAN Open supports 5, 8, 10, or 15 seconds." }
        val imageUrl = image?.trim().orEmpty()
        require(imageUrl.startsWith("https://") || imageUrl.startsWith("http://")) {
            "RunPod Public WAN requires the first frame as a hosted HTTP/HTTPS image URL. " +
                "Use Animate with WAN from an Open-generated image, or provide a hosted image URL."
        }
        val input = JSONObject().apply {
            put("prompt", prompt)
            put("negative_prompt", negativePrompt)
            put("size", if (aspectRatio == "9:16") "720*1280" else "1280*720")
            put("duration", duration)
            put("seed", seed ?: -1L)
            put("enable_safety_checker", !openContent)
            put("image", imageUrl)
            put("num_inference_steps", 30)
            put("guidance", 5)
            put("flow_shift", 5)
            put("enable_prompt_optimization", false)
        }
        // RunPod's public WAN 2.2 documentation uses /runsync and returns output.video_url.
        return runSync("wan-2-2-i2v-720", input, "video_url")
    }

    private suspend fun runSync(endpoint: String, input: JSONObject, outputKey: String): String = withContext(Dispatchers.IO) {
        val token = requireToken()
        val payload = JSONObject().put("input", input).toString()
        val request = Request.Builder()
            .url("https://api.runpod.ai/v2/$endpoint/runsync")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw RunpodApiException(response.code, errorText(body))
            outputUrl(JSONObject(body), outputKey)
        }
    }

    private suspend fun runAsync(
        endpoint: String,
        input: JSONObject,
        outputKey: String,
        timeoutMs: Long
    ): String = withContext(Dispatchers.IO) {
        val token = requireToken()
        val payload = JSONObject().put("input", input).toString()
        val submit = Request.Builder()
            .url("https://api.runpod.ai/v2/$endpoint/run")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val initial = client.newCall(submit).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw RunpodApiException(response.code, errorText(body))
            JSONObject(body)
        }
        val jobId = initial.optString("id")
        if (jobId.isBlank()) error("RunPod did not return a job ID")

        val started = System.currentTimeMillis()
        var resultUrl: String? = null
        while (resultUrl == null) {
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
            when (status.optString("status")) {
                "COMPLETED" -> resultUrl = outputUrl(status, outputKey)
                "FAILED", "ERROR", "CANCELLED", "TIMED_OUT" -> {
                    val detail = status.optString("error").ifBlank { status.optString("status") }
                    error("RunPod $detail\nJob: $jobId")
                }
            }
        }
        resultUrl ?: error("RunPod completed without output. Job: $jobId")
    }

    private fun outputUrl(result: JSONObject, preferredKey: String): String {
        val output = result.opt("output")
        val candidates = listOf(preferredKey, "result", "url", "video_url", "image_url")

        fun fromJsonObject(obj: JSONObject): String? {
            for (key in candidates) {
                val value = obj.opt(key)
                if (value is String && (value.startsWith("https://") || value.startsWith("http://"))) return value
                if (value is JSONObject) {
                    val nested = fromJsonObject(value)
                    if (nested != null) return nested
                }
                if (value is JSONArray) {
                    for (i in 0 until value.length()) {
                        val item = value.opt(i)
                        if (item is String && (item.startsWith("https://") || item.startsWith("http://"))) return item
                        if (item is JSONObject) {
                            val nested = fromJsonObject(item)
                            if (nested != null) return nested
                        }
                    }
                }
            }
            return null
        }

        val url = when (output) {
            is JSONObject -> fromJsonObject(output)
            is JSONArray -> {
                var found: String? = null
                for (i in 0 until output.length()) {
                    val item = output.opt(i)
                    if (item is String && (item.startsWith("https://") || item.startsWith("http://"))) {
                        found = item
                        break
                    }
                    if (item is JSONObject) {
                        found = fromJsonObject(item)
                        if (found != null) break
                    }
                }
                found
            }
            is String -> output.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            else -> null
        }
        if (!url.isNullOrBlank()) return url

        val status = result.optString("status")
        val error = result.optString("error")
        val jobId = result.optString("id")
        val rawOutput = output?.toString()?.take(1500).orEmpty()
        val detail = buildString {
            append(if (error.isNotBlank()) "RunPod $status: $error" else "RunPod returned no $preferredKey")
            if (jobId.isNotBlank()) append("\nJob: ").append(jobId)
            if (rawOutput.isNotBlank()) append("\nOutput: ").append(rawOutput)
        }
        error(detail)
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
