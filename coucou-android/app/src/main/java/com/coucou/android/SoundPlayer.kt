package com.coucou.android

import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log

/**
 * Plays short UI sounds for the overlay.
 *
 * Sprint 1 has no audio assets yet: @Buffy is holding the sound files pending @Boss's
 * decision on the asset source, though the resource *names* are final
 * (`res/raw/coucou_{greet,open,pop,blip,send,error}`).
 *
 * Sounds are therefore resolved by name at runtime rather than compiled in as
 * `R.raw.*` constants. Referencing `R.raw.coucou_open` today would be a compile error,
 * which would break every other agent's build while the assets are on hold. Name lookup
 * means the sounds start working the moment the files land, with no code change.
 *
 * Every method is safe to call before the assets exist: it logs once and no-ops.
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
        GREET("coucou_greet"),
        OPEN("coucou_open"),
        POP("coucou_pop"),
        BLIP("coucou_blip"),
        SEND("coucou_send"),
        ERROR("coucou_error");

        companion object {
            fun fromNameOrNull(name: String): Sound? = entries.firstOrNull { it.name == name }
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