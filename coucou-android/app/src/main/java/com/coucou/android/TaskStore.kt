package com.coucou.android

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple persistent storage for notes and tasks using SharedPreferences.
 * Keys are normalized (trimmed, lowercase) for reliable lookups.
 */
class TaskStore(private val sharedPrefs: SharedPreferences) {

    private fun normalize(text: String): String = text.trim().lowercase()

    /**
     * Save a note/task with the given key and text.
     * Overwrites any existing value for the key.
     */
    fun saveNote(key: String, text: String) {
        sharedPrefs.edit().putString(normalize(key), text).apply()
    }

    /**
     * Save a note/task with the given key, text, and isDone status.
     * Overwrites any existing value for the key.
     */
    fun saveNote(key: String, text: String, isDone: Boolean) {
        sharedPrefs.edit().putString(normalize(key), "$text|$isDone").apply()
    }

    /**
     * Retrieve a note/task by key.
     * Returns null if no value is stored under the key.
     */
    fun getNote(key: String): String? =
        sharedPrefs.getString(normalize(key), null)

    /**
     * Get all notes serialized as JSON string for WebUI consumption.
     * Format: [{"id":...,"text":"...","isDone":...}]
     */
    fun getAllNotesJson(): String {
        val all = sharedPrefs.getAll()
        val items = mutableListOf<String>()
        for ((key, value) in all) {
            val normalized = normalize(key)
            if (normalized.startsWith("note_")) {
                val raw = value as? String ?: continue
                if (raw.isEmpty()) continue
                val arr = raw.split("|", limit = 2)
                val text = arr[0].replace("\\", "\\\\").replace("\"", "\\\"")
                val isDone = if (arr.size > 1) arr[1].toBoolean() else false
                val id = normalized.removePrefix("note_").toLongOrNull() ?: normalized.hashCode().toLong()
                items.add("{\"id\":$id,\"text\":\"$text\",\"isDone\":$isDone}")
            }
        }
        if (items.isEmpty()) return "[]"
        return "[" + items.joinToString(",") + "]"
    }

    data class NoteRecord(val id: Long, val text: String, val isDone: Boolean)

    fun getAllNotes(): List<NoteRecord> {
        val all = sharedPrefs.getAll()
        val items = mutableListOf<NoteRecord>()
        for ((key, value) in all) {
            val normalized = normalize(key)
            if (normalized.startsWith("note_")) {
                val raw = value as? String ?: continue
                if (raw.isEmpty()) continue
                val arr = raw.split("|", limit = 2)
                val text = arr[0]
                val isDone = if (arr.size > 1) arr[1].toBoolean() else false
                val id = normalized.removePrefix("note_").toLongOrNull() ?: normalized.hashCode().toLong()
                items.add(NoteRecord(id, text, isDone))
            }
        }
        return items.sortedByDescending { it.id }
    }

    /**
     * Remove a saved note/task.
     */
    fun removeNote(key: String) {
        sharedPrefs.edit().remove(normalize(key)).apply()
    }

    /**
     * Delete a note by its internal ID.
     * Returns true if deleted, false if not found.
     */
    fun deleteNoteById(id: Long): Boolean {
        val all = sharedPrefs.getAll()
        for ((key, _) in all) {
            val normalized = normalize(key)
            if (normalized.startsWith("note_")) {
                val noteId = normalized.removePrefix("note_").toLongOrNull() ?: normalized.hashCode().toLong()
                if (noteId == id) {
                    sharedPrefs.edit().remove(normalized).apply()
                    return true
                }
            }
        }
        return false
    }

    /**
     * Toggle the isDone status of a note by its internal ID.
     * Returns true if toggled, false if not found.
     */
    fun toggleNoteById(id: Long): Boolean {
        val all = sharedPrefs.getAll()
        for ((key, value) in all) {
            val normalized = normalize(key)
            if (normalized.startsWith("note_")) {
                val noteId = normalized.removePrefix("note_").toLongOrNull() ?: normalized.hashCode().toLong()
                if (noteId == id) {
                    val raw = value as? String ?: continue
                    val parts = raw.split("|", limit = 2)
                    if (parts.size >= 2) {
                        val currentIsDone = parts[1].toBoolean()
                        val newIsDone = !currentIsDone
                        sharedPrefs.edit()
                            .putString(normalized, "${parts[0]}|$newIsDone")
                            .apply()
                        return true
                    } else if (parts.size == 1) {
                        sharedPrefs.edit()
                            .putString(normalized, "${parts[0]}|true")
                            .apply()
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * List all saved note/task keys.
     */
    fun listNotes(): List<String> {
        return sharedPrefs
            .getAll()
            .keys
            .map { it.replace("coucou_", "") }
            .filter { it != "coucou_" }
            .toList()
    }

    fun listAllNotes(): List<String> {
        val all = sharedPrefs.getAll()
        val result = mutableListOf<String>()
        for ((key, _) in all) {
            val normalized = normalize(key)
            if (normalized.startsWith("note_")) {
                result.add(normalized)
            }
        }
        return result.sorted()
    }

    companion object {
        private const val PREFS_NAME = "coucou_task_store"

        fun create(context: Context): TaskStore {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return TaskStore(prefs)
        }

        fun createDummy(): TaskStore {
            val map = mutableMapOf<String, Any?>()
            val prefs = object : SharedPreferences {
                override fun getAll(): MutableMap<String, *> = HashMap(map)
                override fun getString(key: String?, defValue: String?): String? = map[key]?.toString() ?: defValue
                override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = null
                override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
                override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
                override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
                override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
                override fun contains(key: String?): Boolean = map.containsKey(key)
                override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
                override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

                override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                    val changes = mutableMapOf<String, Any?>()
                    val removes = mutableSetOf<String>()
                    var cleared = false

                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        key?.let { changes[it] = value }
                        return this
                    }
                    override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
                    override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
                    override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
                    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
                    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
                    override fun remove(key: String?): SharedPreferences.Editor {
                        key?.let { removes.add(it) }
                        return this
                    }
                    override fun clear(): SharedPreferences.Editor {
                        cleared = true
                        return this
                    }
                    override fun commit(): Boolean {
                        apply()
                        return true
                    }
                    override fun apply() {
                        if (cleared) map.clear()
                        removes.forEach { map.remove(it) }
                        map.putAll(changes)
                    }
                }
            }
            return TaskStore(prefs)
        }
    }
}