package com.forgeai.studio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ProfileStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("forge_profile_bank", Context.MODE_PRIVATE)
    private val profileDir = File(context.filesDir, "profile_bank").apply { mkdirs() }

    fun loadCharacters(): List<CharacterProfile> = runCatching {
        val array = JSONArray(prefs.getString("characters", "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    CharacterProfile(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = o.optString("name", "Character"),
                        notes = o.optString("notes"),
                        adultAnatomyEnabled = o.optBoolean("adultAnatomyEnabled", false),
                        portraitSeed = o.nullableLong("portraitSeed"),
                        fullBodySeed = o.nullableLong("fullBodySeed"),
                        videoSeed = o.nullableLong("videoSeed"),
                        lockedTraits = o.optJSONArray("lockedTraits").stringSet(),
                        references = o.optJSONArray("references").storedReferences()
                    )
                )
            }
        }.sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())

    fun loadBackgrounds(): List<BackgroundProfile> = runCatching {
        val array = JSONArray(prefs.getString("backgrounds", "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    BackgroundProfile(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = o.optString("name", "Background"),
                        notes = o.optString("notes"),
                        imageSeed = o.nullableLong("imageSeed"),
                        videoSeed = o.nullableLong("videoSeed"),
                        references = o.optJSONArray("references").storedReferences()
                    )
                )
            }
        }.sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())

    fun saveCharacter(profile: CharacterProfile) {
        val current = loadCharacters().toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        if (index >= 0) current[index] = profile else current += profile
        saveCharacters(current)
    }

    fun saveBackground(profile: BackgroundProfile) {
        val current = loadBackgrounds().toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        if (index >= 0) current[index] = profile else current += profile
        saveBackgrounds(current)
    }

    fun deleteCharacter(profile: CharacterProfile) {
        profile.references.forEach { runCatching { File(it.path).delete() } }
        saveCharacters(loadCharacters().filterNot { it.id == profile.id })
    }

    fun deleteBackground(profile: BackgroundProfile) {
        profile.references.forEach { runCatching { File(it.path).delete() } }
        saveBackgrounds(loadBackgrounds().filterNot { it.id == profile.id })
    }

    suspend fun importReference(
        uri: Uri,
        bucket: String,
        role: String,
        label: String = "",
        sensitive: Boolean = false
    ): StoredReference = withContext(Dispatchers.IO) {
        val safeBucket = bucket.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val dir = File(profileDir, safeBucket).apply { mkdirs() }
        val mime = context.contentResolver.getType(uri).orEmpty()
        val ext = when {
            mime.contains("png") -> ".png"
            mime.contains("webp") -> ".webp"
            else -> ".jpg"
        }
        val file = File(dir, "${System.currentTimeMillis()}_${UUID.randomUUID()}$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        } ?: error("Could not read selected image")
        StoredReference(
            path = file.absolutePath,
            role = role,
            label = label,
            sensitive = sensitive
        )
    }

    private fun saveCharacters(profiles: List<CharacterProfile>) {
        val array = JSONArray()
        profiles.forEach { p ->
            array.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("notes", p.notes)
                put("adultAnatomyEnabled", p.adultAnatomyEnabled)
                putNullableLong("portraitSeed", p.portraitSeed)
                putNullableLong("fullBodySeed", p.fullBodySeed)
                putNullableLong("videoSeed", p.videoSeed)
                put("lockedTraits", JSONArray(p.lockedTraits.toList()))
                put("references", p.references.toJsonArray())
            })
        }
        prefs.edit().putString("characters", array.toString()).apply()
    }

    private fun saveBackgrounds(profiles: List<BackgroundProfile>) {
        val array = JSONArray()
        profiles.forEach { p ->
            array.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("notes", p.notes)
                putNullableLong("imageSeed", p.imageSeed)
                putNullableLong("videoSeed", p.videoSeed)
                put("references", p.references.toJsonArray())
            })
        }
        prefs.edit().putString("backgrounds", array.toString()).apply()
    }

    private fun JSONObject.nullableLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return optLong(key).takeIf { it > 0L }
    }

    private fun JSONObject.putNullableLong(key: String, value: Long?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private fun JSONArray?.stringSet(): Set<String> {
        if (this == null) return setOf("Face", "Body proportions")
        return buildSet {
            for (i in 0 until length()) optString(i).takeIf { it.isNotBlank() }?.let(::add)
        }.ifEmpty { setOf("Face", "Body proportions") }
    }

    private fun JSONArray?.storedReferences(): List<StoredReference> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val o = optJSONObject(i) ?: continue
                val path = o.optString("path")
                if (path.isBlank() || !File(path).exists()) continue
                add(
                    StoredReference(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        path = path,
                        role = o.optString("role", "OTHER"),
                        label = o.optString("label"),
                        sensitive = o.optBoolean("sensitive", false)
                    )
                )
            }
        }
    }

    private fun List<StoredReference>.toJsonArray(): JSONArray = JSONArray().also { array ->
        forEach { ref ->
            array.put(JSONObject().apply {
                put("id", ref.id)
                put("path", ref.path)
                put("role", ref.role)
                put("label", ref.label)
                put("sensitive", ref.sensitive)
            })
        }
    }
}
