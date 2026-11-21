package com.music.bitchord.data.replaymix

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.personalmix.decodePersonalMixSnapshot
import com.music.bitchord.data.personalmix.encodePersonalMixSnapshot

/**
 * Persistence for Replay Mix: caches the generated dynamic playlist snapshot across app launches.
 */
object ReplayMixStore {

    private var prefs: SharedPreferences? = null

    private const val FILE = "bitchord_replay_mix"
    private const val KEY_SNAPSHOT = "replay_mix_snapshot"
    private const val KEY_SNAPSHOT_BUILT_AT = "replay_mix_built_at"

    const val SNAPSHOT_TTL_MS = 24L * 60 * 60 * 1000L

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun saveSnapshot(songs: List<Song>) {
        if (songs.isEmpty()) return
        prefs?.edit()
            ?.putString(KEY_SNAPSHOT, encodePersonalMixSnapshot(songs))
            ?.putLong(KEY_SNAPSHOT_BUILT_AT, System.currentTimeMillis())
            ?.apply()
    }

    @Synchronized
    fun readSnapshot(): Pair<List<Song>, Long>? {
        val sp = prefs ?: return null
        val text = sp.getString(KEY_SNAPSHOT, null)?.takeIf { it.isNotEmpty() } ?: return null
        val songs = decodePersonalMixSnapshot(text)
        if (songs.isEmpty()) return null
        return songs to sp.getLong(KEY_SNAPSHOT_BUILT_AT, 0L)
    }

    @Synchronized
    fun clearSnapshot() {
        prefs?.edit()?.remove(KEY_SNAPSHOT)?.remove(KEY_SNAPSHOT_BUILT_AT)?.apply()
    }

    /** Test helper to reset store state without android Context. */
    internal fun resetForTest() {
        prefs = null
    }
}
