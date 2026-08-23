package com.forgeai.studio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
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
        require(!image.isNullOrBlank()) { "WAN Open image-to-video needs a first frame." }
        val endpoint = "wan-2-2-i2v-720"
        val input = JSONObject().apply {
            put("prompt", prompt)
            put("negative_prompt", negativePrompt)
            put("size", if (aspectRatio == "9:16") "720*1280" else "1280*720")
            put("duration", duration)
            put("seed", seed ?: -1L)
            put("enable_safety_checker", !openContent)
            put("image", image)
            put("num_inference_steps", 30)
            put("guidance", 5)
            put("flow_shift", 5)
            put("enable_prompt_optimization", false)
        }
        return runAsync(endpoint, input, "video_url", timeoutMs = 18 * 60 * 1000L)
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
            when (status.optString("status")) {
                "COMPLETED" -> return@withContext outputUrl(status, outputKey)
                "FAILED", "ERROR", "CANCELLED", "TIMED_OUT" -> {
                    val detail = status.optString("error").ifBlank { status.optString("status") }
                    error("RunPod $detail\nJob: $jobId")
                }
            }
        }
    }

    private fun outputUrl(result: JSONObject, key: String): String {
        val output = result.optJSONObject("output")
        val url = output?.optString(key).orEmpty()
        if (url.isNotBlank()) return url
        val status = result.optString("status")
        val error = result.optString("error")
        error(if (error.isNotBlank()) "RunPod $status: $error" else "RunPod returned no $key")
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
