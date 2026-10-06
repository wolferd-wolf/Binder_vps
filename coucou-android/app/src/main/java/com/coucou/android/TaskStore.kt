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
     * Retrieve a note/task by key.
     * Returns null if no value is stored under the key.
     */
    fun getNote(key: String): String? =
        sharedPrefs.getString(normalize(key), null)

    /**
     * Remove a saved note/task.
     */
    fun removeNote(key: String) {
        sharedPrefs.edit().remove(normalize(key)).apply()
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