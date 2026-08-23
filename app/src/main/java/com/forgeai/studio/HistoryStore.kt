package com.forgeai.studio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class CreationKind { IMAGE, VIDEO }

data class CreationRecord(
    val id: String = UUID.randomUUID().toString(),
    val kind: CreationKind,
    val model: String,
    val prompt: String,
    val localPath: String,
    val createdAt: Long = System.currentTimeMillis()
)

class HistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("forge_history", Context.MODE_PRIVATE)

    fun load(): List<CreationRecord> = runCatching {
        val array = JSONArray(prefs.getString("records", "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val record = CreationRecord(
                    id = o.getString("id"),
                    kind = CreationKind.valueOf(o.getString("kind")),
                    model = o.getString("model"),
                    prompt = o.getString("prompt"),
                    localPath = o.getString("localPath"),
                    createdAt = o.getLong("createdAt")
                )
                if (File(record.localPath).exists()) add(record)
            }
        }.sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    fun add(record: CreationRecord) {
        val records = (listOf(record) + load()).take(100)
        save(records)
    }

    fun delete(record: CreationRecord) {
        runCatching { File(record.localPath).delete() }
        save(load().filterNot { it.id == record.id })
    }

    private fun save(records: List<CreationRecord>) {
        val array = JSONArray()
        records.forEach { r ->
            array.put(JSONObject().apply {
                put("id", r.id)
                put("kind", r.kind.name)
                put("model", r.model)
                put("prompt", r.prompt)
                put("localPath", r.localPath)
                put("createdAt", r.createdAt)
            })
        }
        prefs.edit().putString("records", array.toString()).apply()
    }
}
