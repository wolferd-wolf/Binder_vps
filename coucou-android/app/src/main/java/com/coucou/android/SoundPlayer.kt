package com.coucou.android

import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log

/**
 * Plays short UI sounds for the overlay.
 *
 * Sounds are resolved by *name* at runtime rather than compiled in as `R.raw.*`
 * constants. Referencing `R.raw.coucou_open` while the asset is absent would be a compile
 * error, which would break every other agent's build; name lookup means the sounds start
 * working the moment @Buffy's files land in `res/raw/`, with no code change.
 *
 * Every method is safe to call before the assets exist: it logs once and no-ops.
 *
 * The names below are @Buffy's contract (`res/raw/coucou_<name>`), covering both the UI
 * sounds and the per-state sounds of the character engine
 * ([CoucouCharacterEngine.STATE_SOUND]).
 */
class SoundPlayer(context: android.content.Context) {

    private val appContext = context.applicationContext
    private val soundPool: SoundPool? = try {
        SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
    } catch (e: Exception) {
        Log.w(TAG, "Could not create SoundPool: ${e.message}", e)
        null
    }

    private val loaded = mutableMapOf<Sound, Int>()

    enum class Sound(val rawName: String) {
        // UI sounds.
        GREET("coucou_greet"),
        OPEN("coucou_open"),
        POP("coucou_pop"),
        BLIP("coucou_blip"),
        SEND("coucou_send"),
        ERROR("coucou_error"),
        TICK("coucou_tick"),

        // Per-state sounds, matching STATE_SOUND in coucou/windows/src/mochi/engine.ts.
        WORK("coucou_work"),
        THINK("coucou_think"),
        SEARCH("coucou_search"),
        APPROVAL("coucou_approval"),
        QUESTION("coucou_question"),
        FINISH("coucou_finish"),
        RATE("coucou_rate"),
        SLEEP("coucou_sleep"),
        DIZZY("coucou_dizzy"),
        SLAP("coucou_slap"),
        ANNOYED("coucou_annoyed");

        companion object {
            fun fromNameOrNull(name: String): Sound? = entries.firstOrNull { it.name == name }

            /** Resolves a bare upstream sound name (`"think"`) to the prefixed resource. */
            fun fromWireNameOrNull(name: String): Sound? =
                entries.firstOrNull { it.rawName == "coucou_$name" || it.name.equals(name, true) }
        }
    }

    /**
     * Plays [sound] if its resource is present.
     *
     * @return true if a sound was actually played.
     */
    fun play(sound: Sound): Boolean {
        val pool = soundPool ?: return false
        val id = loaded[sound] ?: load(sound) ?: return false
        return try {
            pool.play(id, VOLUME, VOLUME, PRIORITY, REPEAT, RATE)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play ${sound.rawName}: ${e.message}", e)
            false
        }
    }

    /** Resolves and preloads [sound]; returns null when the asset is not present yet. */
    private fun load(sound: Sound): Int? {
        val pool = soundPool ?: return null
        val resId = appContext.resources.getIdentifier(sound.rawName, "raw", appContext.packageName)
        if (resId == 0) {
            Log.i(TAG, "Sound '${sound.rawName}' not present yet; skipping")
            return null
        }
        return try {
            pool.load(appContext, resId, PRIORITY).also { loaded[sound] = it }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load ${sound.rawName}: ${e.message}", e)
            null
        }
    }

    /** Preloads any sounds that are available. Safe to call before assets land. */
    fun preloadAvailable() {
        Sound.entries.forEach { load(it) }
    }

    fun release() {
        loaded.clear()
        soundPool?.release()
    }

    companion object {
        private const val TAG = "CoucouSoundPlayer"
        private const val MAX_STREAMS = 4
        private const val PRIORITY = 1
        private const val REPEAT = 0
        private const val RATE = 1.0f
        private const val VOLUME = 1.0f
    }
}