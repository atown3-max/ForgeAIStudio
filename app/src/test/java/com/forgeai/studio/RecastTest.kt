package com.forgeai.studio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RecastTest {
    @Test fun onlySecureWorkerOriginsAreAccepted() {
        assertEquals("https://worker.example", recastEndpoint(" https://worker.example/ "))
        listOf("http://worker.example", "https://user:secret@worker.example", "https://worker.example/path",
            "https://worker.example/?token=secret", "https://worker.example/#key").forEach { address ->
            assertTrue(address, runCatching { recastEndpoint(address) }.isFailure)
        }
    }

    @Test fun savedJobRetainsIdentityAndImmutableRecipeAcrossRestart() {
        val job = RecastJob("id", "https://worker.example", "/private/source", "/private/image", "Character: Alex",
            1.5, 5, "480p", 12345L, true)
        val recovered = RecastJob.fromJson(JSONObject(job.toJson().toString()))
        assertEquals(job, recovered)
        val options = recovered.options()
        assertEquals(1.5, options.getDouble("start"), 0.0)
        assertEquals(5, options.getInt("duration"))
        assertTrue(options.getBoolean("keep_audio"))
        assertFalse(options.has("endpoint"))
        assertFalse(options.has("video"))
        assertFalse(options.has("token"))
    }
}
