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

class ReplicateClient(private val tokenProvider: () -> String?) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(75, TimeUnit.SECONDS)
        .build()

    suspend fun testToken(): String = withContext(Dispatchers.IO) {
        val token = requireToken()
        val request = Request.Builder()
            .url("https://api.replicate.com/v1/account")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, errorText(body))
            JSONObject(body).optString("username", "Connected")
        }
    }

    suspend fun generateQwenImage2(
        prompt: String,
        imageDataUri: String?,
        aspectRatio: String,
        matchInput: Boolean,
        expandPrompt: Boolean,
        negativePrompt: String,
        seed: Long?
    ): String {
        val input = JSONObject().apply {
            put("prompt", prompt)
            put("aspect_ratio", aspectRatio)
            put("match_input_image", matchInput && imageDataUri != null)
            put("enable_prompt_expansion", expandPrompt)
            put("negative_prompt", negativePrompt)
            if (imageDataUri != null) put("image", imageDataUri)
            if (seed != null) put("seed", seed)
        }
        return runOfficialModel("qwen", "qwen-image-2", input)
    }

    suspend fun generateQwen2511(
        prompt: String,
        imageDataUris: List<String>,
        aspectRatio: String,
        seed: Long?
    ): String {
        require(imageDataUris.isNotEmpty()) { "Qwen Edit 2511 needs at least one reference image." }
        val input = JSONObject().apply {
            put("prompt", prompt)
            put("image", JSONArray(imageDataUris.take(3)))
            put("aspect_ratio", aspectRatio)
            put("go_fast", true)
            put("output_format", "png")
            put("output_quality", 100)
            if (seed != null) put("seed", seed)
        }
        return runOfficialModel("qwen", "qwen-image-edit-2511", input)
    }

    suspend fun generateLtx23(
        prompt: String,
        firstFrameDataUri: String?,
        lastFrameDataUri: String?,
        duration: Int,
        resolution: String,
        aspectRatio: String,
        fps: Int,
        cameraMotion: String,
        generateAudio: Boolean
    ): String {
        val input = JSONObject().apply {
            put("prompt", prompt)
            put("duration", duration)
            put("resolution", resolution)
            put("aspect_ratio", aspectRatio)
            put("fps", fps)
            put("camera_motion", cameraMotion)
            put("generate_audio", generateAudio)
            if (firstFrameDataUri != null) put("image", firstFrameDataUri)
            if (lastFrameDataUri != null) put("last_frame_image", lastFrameDataUri)
        }
        return runOfficialModel("lightricks", "ltx-2.3-fast", input, cancelAfter = "12m")
    }

    private suspend fun runOfficialModel(
        owner: String,
        model: String,
        input: JSONObject,
        cancelAfter: String = "5m"
    ): String = withContext(Dispatchers.IO) {
        val token = requireToken()
        val payload = JSONObject().put("input", input).toString()
        val request = Request.Builder()
            .url("https://api.replicate.com/v1/models/$owner/$model/predictions")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Prefer", "wait=60")
            .header("Cancel-After", cancelAfter)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val initial = client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, errorText(body))
            JSONObject(body)
        }

        var prediction = initial
        val started = System.currentTimeMillis()
        var resultUrl: String? = null
        while (resultUrl == null) {
            when (prediction.optString("status")) {
                "succeeded" -> resultUrl = outputUrl(prediction)
                "failed", "canceled" -> {
                    val error = prediction.opt("error")?.takeUnless { it == JSONObject.NULL }?.toString()
                    val predictionId = prediction.optString("id")
                    val webUrl = prediction.optJSONObject("urls")?.optString("web").orEmpty()
                    val logs = prediction.optString("logs").takeLast(1200)
                    val diagnostic = buildString {
                        append(error ?: "Generation ${prediction.optString("status")}")
                        if (predictionId.isNotBlank()) append("\nPrediction: ").append(predictionId)
                        if (webUrl.isNotBlank()) append("\nDebug: ").append(webUrl)
                        if (logs.isNotBlank()) append("\nLogs: ").append(logs)
                    }
                    error(diagnostic)
                }
            }
            if (resultUrl != null) break
            if (System.currentTimeMillis() - started > 12 * 60 * 1000L) error("Generation timed out")
            val getUrl = prediction.optJSONObject("urls")?.optString("get").orEmpty()
            if (getUrl.isBlank()) error("Replicate did not return a polling URL")
            delay(2000)
            prediction = getPrediction(token, getUrl)
        }
        resultUrl ?: error("Generation completed without output")
    }

    private fun getPrediction(token: String, url: String): JSONObject {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, errorText(body))
            return JSONObject(body)
        }
    }

    private fun outputUrl(prediction: JSONObject): String {
        return when (val output = prediction.opt("output")) {
            is String -> output
            is JSONArray -> if (output.length() > 0) output.getString(0) else error("Model returned no output")
            else -> error("Model returned no file")
        }
    }

    private fun requireToken(): String = tokenProvider()?.takeIf { it.isNotBlank() }
        ?: throw ApiException(401, "Add your Replicate API token in Settings first.")

    private fun errorText(body: String): String = runCatching {
        val o = JSONObject(body)
        o.optString("detail").ifBlank { o.optString("error") }.ifBlank { body }
    }.getOrDefault(body.ifBlank { "Unknown API error" })
}

class ApiException(val code: Int, detail: String) : Exception("Replicate HTTP $code: $detail")
