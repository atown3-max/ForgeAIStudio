package com.forgeai.studio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

const val RECAST_MODEL = "Wan-AI/Wan2.2-Animate-14B"

fun recastEndpoint(value: String): String {
    val text = value.trim()
    require(!text.startsWith("curl", true) && !text.contains('\n')) {
        "Paste only the endpoint ID or HTTPS URL, not the cURL command."
    }
    if (Regex("[a-z0-9]{10,64}").matches(text)) return "https://api.runpod.ai/v2/$text"
    val url = text.toHttpUrl()
    if (url.host == "api.runpod.ai") {
        require(url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null &&
            Regex("/v2/[a-z0-9]{10,64}(/(run|runsync))?/?").matches(url.encodedPath)) {
            "Use https://api.runpod.ai/v2/YOUR_ENDPOINT_ID without credentials or query parameters."
        }
        return "https://api.runpod.ai/v2/${url.pathSegments[1]}"
    }
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
        url.query == null && url.fragment == null && url.encodedPath == "/") {
        "Enter the worker's HTTPS address without a path, query, or credentials."
    }
    return url.toString().trimEnd('/')
}

fun isRunpodRecast(endpoint: String) = endpoint.startsWith("https://api.runpod.ai/v2/")

data class RecastJob(
    val id: String,
    val endpoint: String,
    val video: String,
    val image: String,
    val reference: String,
    val start: Double,
    val duration: Int,
    val resolution: String,
    val seed: Long,
    val keepAudio: Boolean
) {
    fun options() = JSONObject().apply {
        put("start", start); put("duration", duration); put("resolution", resolution)
        put("seed", seed); put("keep_audio", keepAudio)
    }
    fun toJson() = options().apply {
        put("id", id); put("endpoint", endpoint); put("video", video)
        put("image", image); put("reference", reference)
    }
    companion object {
        fun fromJson(o: JSONObject) = RecastJob(o.getString("id"), o.getString("endpoint"),
            o.getString("video"), o.getString("image"), o.getString("reference"), o.getDouble("start"),
            o.getInt("duration"), o.getString("resolution"), o.getLong("seed"), o.getBoolean("keep_audio"))
    }
}

class RecastStore(context: Context) : RecastSubmissionJournal {
    private val prefs = context.getSharedPreferences("forge_recast", Context.MODE_PRIVATE)
    fun endpoint(): String = prefs.getString("endpoint", "").orEmpty()
    fun saveEndpoint(value: String) { prefs.edit().putString("endpoint", value).commit() }
    override fun submission(id: String): String? = prefs.getString("submission_$id", null)
    override fun saveSubmission(id: String, remoteId: String?) {
        val edit = prefs.edit()
        if (remoteId == null) edit.remove("submission_$id") else edit.putString("submission_$id", remoteId)
        check(edit.commit()) { "Could not save the RunPod job ID on this phone." }
    }
    fun pending(): RecastJob? = prefs.getString("pending", null)?.let { RecastJob.fromJson(JSONObject(it)) }
    fun save(job: RecastJob) {
        check(prefs.edit().putString("pending", job.toJson().toString()).commit()) { "Could not save the job on this phone." }
    }
    fun clear(job: RecastJob) {
        check(prefs.edit().remove("pending").remove("submission_${job.id}").commit()) { "Could not clear the saved job." }
        File(job.video).parentFile?.takeIf { it.name == job.id }?.deleteRecursively()
    }
}

class RecastApiException(val status: Int, detail: String) : Exception(detail)

class RecastClient(private val journal: RecastSubmissionJournal, private val tokenProvider: () -> String?) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES).callTimeout(6, TimeUnit.MINUTES)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val serverless = RunpodRecastClient(journal, http, tokenProvider)

    private fun request(endpoint: String, path: String): Request.Builder {
        val token = tokenProvider()?.takeIf { it.isNotBlank() } ?: error("Add your Recast worker token in Settings.")
        return Request.Builder().url(recastEndpoint(endpoint) + path).header("Authorization", "Bearer $token")
    }

    private suspend fun json(request: Request): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "Recast worker returned HTTP ${response.code}."
                throw RecastApiException(response.code, detail.take(500))
            }
            JSONObject(text)
        }
    }

    suspend fun health(endpoint: String): String {
        if (isRunpodRecast(recastEndpoint(endpoint))) return serverless.health(recastEndpoint(endpoint))
        val health = json(request(endpoint, "/v1/health").get().build())
        require(health.optInt("protocol") == 1 && health.optString("model") == RECAST_MODEL) {
            "This address is not a compatible Forge Wan-Animate worker."
        }
        check(health.optBoolean("ready")) { health.optString("message", "Worker is not ready.") }
        return "Recast connected · Wan2.2-Animate-14B"
    }

    suspend fun status(job: RecastJob): JSONObject = if (isRunpodRecast(job.endpoint)) serverless.status(job)
        else json(request(job.endpoint, "/v1/jobs/${job.id}").get().build())

    suspend fun recover(job: RecastJob, remoteId: String) = serverless.recover(job, remoteId)

    suspend fun submitOrResume(job: RecastJob): JSONObject {
        if (isRunpodRecast(job.endpoint)) return serverless.submitOrResume(job)
        try { return status(job) } catch (error: RecastApiException) { if (error.status != 404) throw error }
        health(job.endpoint)
        val video = File(job.video)
        val image = File(job.image)
        require(video.isFile && image.isFile) { "Upload files are missing. Clear this job and select your files again." }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("id", job.id)
            .addFormDataPart("options", job.options().toString())
            .addFormDataPart("video", "source.mp4", video.asRequestBody("application/octet-stream".toMediaType()))
            .addFormDataPart("image", "character.jpg", image.asRequestBody("image/jpeg".toMediaType()))
            .build()
        return json(request(job.endpoint, "/v1/jobs").post(body).build())
    }

    suspend fun download(context: Context, job: RecastJob): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "creations").apply { mkdirs() }
        val file = File(dir, "recast_${job.id}.mp4")
        if (file.exists() && file.length() > 0) return@withContext file
        if (isRunpodRecast(job.endpoint)) {
            val rendered = File(File(job.video).parentFile, "runpod-result.mp4")
            check(rendered.isFile && rendered.length() > 0) { "Result is not downloaded yet. Reconnect to retrieve it." }
            val copy = File.createTempFile("recast_${job.id}_", ".part", dir)
            try {
                rendered.inputStream().use { input -> copy.outputStream().use { input.copyTo(it) } }
                check(copy.renameTo(file)) { "Could not save Recast result." }
            } finally { copy.delete() }
            return@withContext file
        }
        // A previous screen's blocking download can finish after navigation cancels its coroutine.
        // Separate temporary files prevent a resumed screen from sharing that output stream.
        val partial = File.createTempFile("recast_${job.id}_", ".part", dir)
        try {
            http.newCall(request(job.endpoint, "/v1/jobs/${job.id}/result").get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = runCatching { JSONObject(response.body?.string().orEmpty()).optString("detail") }.getOrNull()
                    throw RecastApiException(response.code, detail ?: "Could not download Recast result.")
                }
                require(response.body?.contentType()?.subtype == "mp4") { "Worker returned an unexpected file type." }
                val body = response.body ?: error("Empty Recast result.")
                body.byteStream().use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= 150L * 1024 * 1024) { "Recast result exceeds the download limit." }
                        output.write(buffer, 0, read)
                    }
                    check(total > 0 && (body.contentLength() < 0 || total == body.contentLength())) { "Incomplete result. Retry download." }
                } }
            }
            check(partial.renameTo(file)) { "Could not save Recast result." }
            file
        } finally { partial.delete() }
    }
}
