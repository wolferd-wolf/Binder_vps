package com.coucou.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for TaskStore note and task persistence.
 */
class TaskStoreTest {

    private class InMemorySharedPreferences : android.content.SharedPreferences {
        val map = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? =
            map[key]?.toString() ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = null
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = EditorImpl(this)
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class EditorImpl(val prefs: InMemorySharedPreferences) : android.content.SharedPreferences.Editor {
            val changes = mutableMapOf<String, Any?>()
            val removes = mutableSetOf<String>()
            var cleared = false

            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
                key?.let { changes[it] = value }
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor = this
            override fun remove(key: String?): android.content.SharedPreferences.Editor {
                key?.let { removes.add(it) }
                return this
            }
            override fun clear(): android.content.SharedPreferences.Editor {
                cleared = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (cleared) prefs.map.clear()
                removes.forEach { prefs.map.remove(it) }
                prefs.map.putAll(changes)
            }
        }
    }

    @Test
    fun saveAndRetrieveNote() {
        val prefs = InMemorySharedPreferences()
        val store = TaskStore(prefs)

        store.saveNote("my_note", "Buy groceries")
        assertEquals("Buy groceries", store.getNote("my_note"))
        // Case-insensitive retrieval
        assertEquals("Buy groceries", store.getNote("MY_NOTE"))
    }

    @Test
    fun removeNote() {
        val prefs = InMemorySharedPreferences()
        val store = TaskStore(prefs)

        store.saveNote("task1", "Walk the dog")
        store.removeNote("task1")
        assertEquals(null, store.getNote("task1"))
    }

    @Test
    fun listNotes() {
        val prefs = InMemorySharedPreferences()
        val store = TaskStore(prefs)

        store.saveNote("coucou_todo1", "Item 1")
        store.saveNote("coucou_todo2", "Item 2")

        val keys = store.listNotes()
        assertTrue(keys.contains("todo1"))
        assertTrue(keys.contains("todo2"))
    }
}
