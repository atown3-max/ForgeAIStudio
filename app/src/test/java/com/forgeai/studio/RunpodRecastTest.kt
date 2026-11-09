package com.forgeai.studio

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class RunpodRecastTest {
    @get:Rule val temp = TemporaryFolder()
    private val endpoint = "https://api.runpod.ai/v2/example1234567"

    private class Journal : RecastSubmissionJournal {
        val ids = mutableMapOf<String, String>()
        override fun submission(id: String) = ids[id]
        override fun saveSubmission(id: String, remoteId: String?) {
            if (remoteId == null) ids.remove(id) else ids[id] = remoteId
        }
    }

    private fun job(): RecastJob {
        val folder = temp.newFolder()
        File(folder, "source.video").writeBytes(byteArrayOf(1))
        File(folder, "runpod-input.mp4").writeBytes(byteArrayOf(2, 3, 4))
        File(folder, "reference.jpg").writeBytes(byteArrayOf(5, 6))
        File(folder, "runpod-media.json").writeText("""{"width":480,"height":832}""")
        return RecastJob(folder.name, endpoint, File(folder, "source.video").path,
            File(folder, "reference.jpg").path, "Reference", 1.0, 5, "480p", 12345, false)
    }

    private fun client(journal: Journal, action: (Request) -> Pair<Int, String>): RunpodRecastClient {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = action(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message("Test").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return RunpodRecastClient(journal, http) { "test-key-not-a-real-credential" }
    }

    @Test fun acceptsIdAndRunpodUrlsButRejectsCommandsAndCredentialLeaks() {
        assertEquals(endpoint, recastEndpoint("example1234567"))
        assertEquals(endpoint, recastEndpoint("$endpoint/runsync"))
        assertEquals(endpoint, recastEndpoint("$endpoint/run/"))
        listOf("curl -X POST $endpoint/run", "http://api.runpod.ai/v2/example1234567",
            "$endpoint?api_key=secret", "https://api.runpod.ai:444/v2/example1234567",
            "https://secret@api.runpod.ai/v2/example1234567", "$endpoint/status/another-job",
            "https://api.runpod.ai.evil.example/v2/example1234567").forEach {
            assertTrue(it, runCatching { recastEndpoint(it) }.isFailure)
        }
    }

    @Test fun testConnectionNeverStartsAWorkerJob() = runBlocking {
        val calls = mutableListOf<String>()
        val api = client(Journal()) { request ->
            calls += "${request.method} ${request.url.encodedPath}"
            200 to """{"jobs":{},"workers":{"idle":0,"running":0}}"""
        }
        assertTrue(api.health(endpoint).contains("access only"))
        assertEquals(listOf("GET /v2/example1234567/health"), calls)
    }

    @Test fun sendsReplacementPayloadAndResumesAcceptedJobWithoutAnotherPost() = runBlocking {
        val job = job()
        val journal = Journal()
        var posts = 0
        val action: (Request) -> Pair<Int, String> = { request ->
            assertEquals("api.runpod.ai", request.url.host)
            if (request.method == "POST") {
                posts++
                assertEquals("", journal.submission(job.id))
                val buffer = Buffer(); request.body!!.writeTo(buffer)
                val body = JSONObject(buffer.readUtf8())
                val input = body.getJSONObject("input")
                assertEquals("replace", input.getString("mode"))
                assertEquals(16, input.getInt("fps"))
                assertEquals(4, input.getInt("steps"))
                assertEquals(480, input.getInt("width"))
                assertEquals(832, input.getInt("height"))
                assertArrayEquals(byteArrayOf(2, 3, 4), Base64.getDecoder().decode(input.getString("video_base64")))
                assertFalse(input.getString("image_base64").startsWith("data:"))
                assertEquals(1_800_000, body.getJSONObject("policy").getInt("executionTimeout"))
                200 to """{"id":"remote-job-123","status":"IN_QUEUE"}"""
            } else {
                assertTrue(request.url.encodedPath.endsWith("/status/remote-job-123"))
                200 to """{"id":"remote-job-123","status":"IN_PROGRESS"}"""
            }
        }
        val api = client(journal, action)
        assertEquals("QUEUED", api.submitOrResume(job).getString("status"))
        // A fresh client models app restart with the saved journal.
        val restarted = client(journal, action)
        assertEquals("RUNNING", restarted.submitOrResume(job).getString("status"))
        assertEquals(1, posts)
        assertEquals("remote-job-123", journal.submission(job.id))
    }

    @Test fun lostSubmitResponseCannotTriggerDuplicateRenderAndCanRecoverById() = runBlocking {
        val job = job()
        val journal = Journal()
        var posts = 0
        val api = client(journal) { request ->
            if (request.method == "POST") { posts++; throw IOException("Disconnected after send") }
            200 to """{"id":"recovered-job-123","status":"IN_PROGRESS"}"""
        }
        assertTrue(runCatching { api.submitOrResume(job) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { api.submitOrResume(job) }.exceptionOrNull() is RecastSubmissionUnknown)
        api.recover(job, "recovered-job-123")
        assertEquals("RUNNING", api.submitOrResume(job).getString("status"))
        assertEquals(1, posts)
    }

    @Test fun unauthorizedSubmitCanBeRetriedAfterKeyCorrection() = runBlocking {
        val job = job(); val journal = Journal()
        val api = client(journal) { 401 to """{"error":"Do not echo secret request data"}""" }
        val error = runCatching { api.submitOrResume(job) }.exceptionOrNull()
        assertTrue(error is RecastApiException)
        assertNull(journal.submission(job.id))
        assertFalse(error!!.message!!.contains("secret request"))
    }

    @Test fun overlappingReconnectsOnlySubmitOnce() = runBlocking {
        val job = job(); val journal = Journal(); val posts = AtomicInteger()
        val api = client(journal) { request ->
            if (request.method == "POST") {
                posts.incrementAndGet()
                Thread.sleep(50)
                200 to """{"id":"concurrent-job-123","status":"IN_QUEUE"}"""
            } else 200 to """{"id":"concurrent-job-123","status":"IN_PROGRESS"}"""
        }
        listOf(async { api.submitOrResume(job) }, async { api.submitOrResume(job) }).awaitAll()
        assertEquals(1, posts.get())
    }

    @Test fun completedOutputIsSavedLocallyAndReopenDoesNotDependOnRetention() = runBlocking {
        val job = job(); val journal = Journal()
        journal.saveSubmission(job.id, "completed-job-123")
        val mp4 = byteArrayOf(0, 0, 0, 20) + "ftypisom".toByteArray()
        var calls = 0
        val api = client(journal) {
            calls++
            200 to JSONObject().put("status", "COMPLETED").put("output", JSONObject()
                .put("video", "data:video/mp4;base64," + Base64.getEncoder().encodeToString(mp4))).toString()
        }
        assertEquals("COMPLETED", api.status(job).getString("status"))
        assertArrayEquals(mp4, File(File(job.video).parentFile, "runpod-result.mp4").readBytes())
        assertEquals("COMPLETED", api.submitOrResume(job).getString("status"))
        assertEquals(1, calls)
    }

    @Test fun expiredJobAndWorkerErrorNeverResubmit() = runBlocking {
        val job = job(); val journal = Journal()
        journal.saveSubmission(job.id, "expired-job-123")
        var calls = 0
        val api = client(journal) { request ->
            assertEquals("GET", request.method); calls++
            if (calls == 1) 404 to "{}"
            else 200 to """{"status":"COMPLETED","output":{"error":"worker failed"}}"""
        }
        assertTrue(runCatching { api.submitOrResume(job) }.exceptionOrNull() is RecastApiException)
        assertEquals("FAILED", api.submitOrResume(job).getString("status"))
        assertEquals("expired-job-123", journal.submission(job.id))
    }

    @Test fun oversizedInputsFailBeforeAnyPaidSubmission() = runBlocking {
        val job = job(); val journal = Journal()
        File(File(job.video).parentFile, "runpod-input.mp4").writeBytes(ByteArray(7_200_000))
        var calls = 0
        val api = client(journal) { calls++; 200 to "{}" }
        assertTrue(runCatching { api.submitOrResume(job) }.isFailure)
        assertEquals(0, calls)
        assertNull(journal.submission(job.id))
    }
}
