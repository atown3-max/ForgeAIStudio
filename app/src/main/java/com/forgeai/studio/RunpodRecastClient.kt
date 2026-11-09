package com.forgeai.studio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Base64

/** null = never sent, empty = submission outcome unknown, otherwise the accepted RunPod ID. */
interface RecastSubmissionJournal {
    fun submission(id: String): String?
    fun saveSubmission(id: String, remoteId: String?)
}

class RecastSubmissionUnknown : Exception(
    "RunPod may have accepted this render, but its job ID was not saved. No new render will be submitted. " +
        "Open this endpoint's Requests in RunPod and enter the job ID below to reconnect."
)

/** Adapter for wlsdml1114/Wan_Animate_Runpod_hub. Does not copy or bundle that project's code. */
class RunpodRecastClient(
    private val journal: RecastSubmissionJournal,
    http: OkHttpClient,
    private val tokenProvider: () -> String?
) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()

    private fun request(endpoint: String, path: String): Request.Builder {
        val base = recastEndpoint(endpoint)
        require(isRunpodRecast(base)) { "Choose a RunPod Serverless endpoint." }
        val key = tokenProvider()?.trim()?.takeIf { it.isNotBlank() }
            ?: error("Add this endpoint's RunPod API key in Recast settings.")
        return Request.Builder().url(base + path).header("Authorization", "Bearer $key")
    }

    private fun execute(request: Request): JSONObject {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // Do not echo server error bodies: they can contain input media or request credentials.
                val detail = when (response.code) {
                    401, 403 -> "RunPod rejected the API key. Check its access to this endpoint."
                    404 -> "RunPod endpoint or job was not found. Completed results expire after 30 minutes."
                    413 -> "Upload exceeds RunPod's request limit. Choose a shorter clip."
                    429 -> "RunPod is rate limiting requests. Wait before reconnecting."
                    else -> "RunPod returned HTTP ${response.code}. Check the endpoint's request logs."
                }
                throw RecastApiException(response.code, detail)
            }
            val source = response.body?.source() ?: error("Empty RunPod response.")
            source.request(MAX_RESPONSE_BYTES + 1)
            require(source.buffer.size <= MAX_RESPONSE_BYTES) { "RunPod result exceeds this preview's download limit." }
            return JSONObject(source.readUtf8())
        }
    }

    suspend fun health(endpoint: String): String = withContext(Dispatchers.IO) {
        val health = execute(request(endpoint, "/health").get().build())
        require(health.optJSONObject("workers") != null && health.optJSONObject("jobs") != null) {
            "This address did not return RunPod Serverless health information."
        }
        "Recast endpoint connected. This checks access only; the Wan Animate template still needs a test render."
    }

    suspend fun submitOrResume(job: RecastJob): JSONObject = withContext(Dispatchers.IO) {
        submissionLock.withLock {
            val saved = journal.submission(job.id)
            if (saved != null) {
                if (saved.isEmpty()) throw RecastSubmissionUnknown()
                return@withLock readStatus(job, saved)
            }
            val body = payload(job)
            // Build/validate the request before committing the irreversible submission marker.
            val request = request(job.endpoint, "/run")
                .post(body.toRequestBody("application/json".toMediaType())).build()
            journal.saveSubmission(job.id, "")
            val response = try { execute(request) } catch (error: RecastApiException) {
                // These responses mean the request was rejected. All ambiguous failures keep the marker.
                if (error.status in listOf(400, 401, 403, 413, 422, 429)) journal.saveSubmission(job.id, null)
                throw error
            }
            val remoteId = response.optString("id")
            require(validRemoteId(remoteId)) { "RunPod did not return a usable job ID. Check Requests before retrying." }
            journal.saveSubmission(job.id, remoteId)
            normalize(job, response)
        }
    }

    suspend fun status(job: RecastJob): JSONObject = withContext(Dispatchers.IO) {
        val remoteId = journal.submission(job.id)?.takeIf { it.isNotEmpty() } ?: throw RecastSubmissionUnknown()
        readStatus(job, remoteId)
    }

    suspend fun recover(job: RecastJob, remoteId: String): Unit = withContext(Dispatchers.IO) {
        submissionLock.withLock {
            require(journal.submission(job.id) == "") { "This job does not need submission recovery." }
            val id = remoteId.trim()
            require(validRemoteId(id)) { "Copy the job ID from this endpoint's Requests tab." }
            // Read-only validation; never submit another job while recovering.
            readStatus(job, id)
            journal.saveSubmission(job.id, id)
        }
    }

    private fun readStatus(job: RecastJob, remoteId: String): JSONObject {
        val result = File(File(job.video).parentFile, "runpod-result.mp4")
        if (result.isFile && result.length() > 0) return JSONObject().put("status", "COMPLETED")
        return normalize(job, execute(request(job.endpoint, "/status/$remoteId").get().build()))
    }

    private fun normalize(job: RecastJob, response: JSONObject): JSONObject {
        val status = response.optString("status")
        val output = response.optJSONObject("output")
        if (response.has("error") || output?.has("error") == true || status in listOf("FAILED", "CANCELLED", "TIMED_OUT")) {
            return JSONObject().put("status", "FAILED").put("error",
                "RunPod render ${if (status == "COMPLETED") "failed" else status.lowercase()}. " +
                    "Check this job in the endpoint's Requests/logs. No automatic retry was submitted.")
        }
        return when (status) {
            "IN_QUEUE" -> JSONObject().put("status", "QUEUED").put("stage", "Queued / waiting for GPU")
            "IN_PROGRESS" -> JSONObject().put("status", "RUNNING").put("stage", "Rendering on RunPod")
            "COMPLETED" -> {
                val encoded = output?.optString("video")?.takeIf { it.isNotBlank() }
                    ?: error("Wan Animate returned no video. Check the deployed template and request logs.")
                val data = if (encoded.startsWith("data:video/mp4;base64,")) encoded.substringAfter(',') else encoded
                val bytes = Base64.getDecoder().decode(data)
                require(bytes.size >= 12 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp") {
                    "Wan Animate returned an invalid MP4. Check the endpoint logs."
                }
                val folder = File(job.video).parentFile!!
                val partial = File.createTempFile("runpod-result-", ".part", folder)
                try {
                    partial.writeBytes(bytes)
                    check(partial.renameTo(File(folder, "runpod-result.mp4"))) { "Could not save the RunPod video." }
                } finally { partial.delete() }
                JSONObject().put("status", "COMPLETED")
            }
            else -> error("Unexpected RunPod status. Reconnect to check the saved job.")
        }
    }

    companion object {
        private val submissionLock = Mutex()
        const val MAX_REQUEST_BYTES = 9_500_000
        const val MAX_RESPONSE_BYTES = 32_000_000L
        fun validRemoteId(value: String) = Regex("[A-Za-z0-9_-]{8,160}").matches(value)

        fun payload(job: RecastJob): String {
            val folder = File(job.video).parentFile!!
            val video = File(folder, "runpod-input.mp4")
            val metadata = File(folder, "runpod-media.json")
            val image = File(job.image)
            require(video.isFile && metadata.isFile && image.isFile) { "Prepared upload is missing. Clear this job and select the files again." }
            require(video.length() > 0 && image.length() > 0 &&
                (video.length() + image.length()) * 4 / 3 + 4096 < MAX_REQUEST_BYTES) {
                "Prepared media exceeds RunPod's request limit. Choose a shorter clip."
            }
            val size = JSONObject(metadata.readText())
            val input = JSONObject().apply {
                put("mode", "replace")
                put("prompt", "Replace the performer with the reference character. Preserve the original motion, expression, camera and background.")
                put("negative_prompt", "blurry, distorted anatomy, extra limbs, flicker")
                put("image_base64", Base64.getEncoder().encodeToString(image.readBytes()))
                put("video_base64", Base64.getEncoder().encodeToString(video.readBytes()))
                put("seed", job.seed); put("width", size.getInt("width")); put("height", size.getInt("height"))
                put("fps", 16); put("cfg", 1.0); put("steps", 4)
            }
            val body = JSONObject().put("input", input).put("policy", JSONObject()
                .put("executionTimeout", 1_800_000).put("ttl", 3_600_000)).toString()
            require(body.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) { "RunPod upload is too large. Choose a shorter clip." }
            return body
        }
    }
}
