package com.music.bitchord.data.personalmix

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistence for Personal Mix: tracks songs played completely (ignoring skips and random presses)
 * up to 20 recent songs, and caches the generated dynamic playlist snapshot across restarts.
 */
object PersonalMixStore {

    private var prefs: SharedPreferences? = null

    private const val FILE = "bitchord_personal_mix"
    private const val KEY_COMPLETED = "personal_mix_completed"
    private const val KEY_SNAPSHOT = "personal_mix_snapshot"
    private const val KEY_SNAPSHOT_BUILT_AT = "personal_mix_built_at"

    const val SNAPSHOT_TTL_MS = 24L * 60 * 60 * 1000L

    private val _completedSongs = MutableStateFlow<List<Song>>(emptyList())
    val completedSongs: StateFlow<List<Song>> = _completedSongs.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = sp
        _completedSongs.value = readCompleted(sp)
    }

    /**
     * Records a track played completely.
     * Skips and random presses (< 85% duration or non-auto ended) are filtered at the player caller site.
     * Keeps up to 20 songs, ordered newest-first, deduplicated by videoId.
     */
    @Synchronized
    fun recordCompleted(song: Song) {
        if (song.videoId.isBlank()) return
        val current = _completedSongs.value.filterNot { it.videoId == song.videoId }
        val updated = (listOf(song) + current).take(PersonalMixRules.RECENT_COMPLETED_LIMIT)
        _completedSongs.value = updated
        prefs?.edit()?.putString(KEY_COMPLETED, encodePersonalMixSnapshot(updated))?.apply()
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

    private fun readCompleted(sp: SharedPreferences): List<Song> {
        val text = sp.getString(KEY_COMPLETED, null)?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return decodePersonalMixSnapshot(text).take(PersonalMixRules.RECENT_COMPLETED_LIMIT)
    }

    /** Test helper to reset store state without android Context. */
    internal fun resetForTest(initial: List<Song> = emptyList()) {
        prefs = null
        _completedSongs.value = initial
    }
}

/** Compact serialization of songs using ASCII record separators. */
fun encodePersonalMixSnapshot(songs: List<Song>): String =
    songs.take(200).joinToString("\u001e") { s ->
        listOf(
            s.videoId,
            s.title,
            s.artist,
            s.albumName.orEmpty(),
            s.thumbnailUrl.orEmpty(),
            s.durationText.orEmpty(),
            s.artistId.orEmpty(),
        ).joinToString("\u001f") { it.replace("\u001e", " ").replace("\u001f", " ") }
    }

/** Inverse of [encodePersonalMixSnapshot]. */
fun decodePersonalMixSnapshot(text: String): List<Song> {
    if (text.isBlank()) return emptyList()
    return text.split("\u001e").mapNotNull { row ->
        val f = row.split("\u001f")
        val id = f.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Song(
            videoId = id,
            title = f.getOrNull(1).orEmpty(),
            artist = f.getOrNull(2).orEmpty(),
            albumName = f.getOrNull(3)?.takeIf { it.isNotBlank() },
            thumbnailUrl = f.getOrNull(4)?.takeIf { it.isNotBlank() },
            durationText = f.getOrNull(5)?.takeIf { it.isNotBlank() },
            artistId = f.getOrNull(6)?.takeIf { it.isNotBlank() },
        )
    }
}
