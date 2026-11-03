package com.music.bitchord.data.moodsmix

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.personalmix.PersonalMixEngine
import com.music.bitchord.data.personalmix.PersonalMixRules
import com.music.bitchord.playback.QueueBuilder
import com.music.bitchord.playback.QueueTimeline.asQueueEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Locale

object MoodsMixEngine {

    suspend fun buildQueue(
        mood: FlowMood,
        completedSeeds: List<Song>,
        limit: Int = MoodsMixRules.INITIAL_TRACKS,
    ): List<Song> = coroutineScope {
        // 1. Gather user's personal seed pool strictly from completed songs + account history
        val seeds = if (completedSeeds.size >= PersonalMixRules.RECENT_COMPLETED_LIMIT) {
            completedSeeds.take(PersonalMixRules.RECENT_COMPLETED_LIMIT)
        } else {
            val history = runCatching { YtMusicRepository.recentHistory().getOrNull().orEmpty() }.getOrDefault(emptyList())
            val existingIds = completedSeeds.map { it.videoId }.toSet()
            val extra = history.filterNot { it.videoId in existingIds }
            (completedSeeds + extra).take(PersonalMixRules.RECENT_COMPLETED_LIMIT)
        }

        val moodWords = mood.shelfWords.map { it.lowercase(Locale.ROOT) }
        val moodTitleLower = mood.title.lowercase(Locale.ROOT)

        // 2. Fetch the user's OWN personalized YouTube Music mixes (from FEmusic_mixed_for_you)
        // e.g. "Chill Mix", "Workout Mix", "Energy Mix", "Focus Mix" built specifically for this signed-in user
        val personalizedMixTracksDeferred = async {
            runCatching {
                val mixes = YtMusicRepository.mixedForYou().getOrNull().orEmpty()
                val matchingMix = mixes.firstOrNull { item ->
                    val title = item.title.lowercase(Locale.ROOT)
                    moodWords.any { it in title } || moodTitleLower in title
                }
                if (matchingMix?.browseId != null) {
                    val playlistId = matchingMix.browseId.removePrefix("VL")
                    YtMusicRepository.playlistAsAudio(playlistId).getOrNull().orEmpty()
                } else {
                    emptyList()
                }
            }.getOrDefault(emptyList())
        }

        // 3. Find user seeds that match mood keywords in title or artist
        val directlyMatchingSeeds = seeds.filter { song ->
            val title = song.title.lowercase(Locale.ROOT)
            val artist = song.artist.lowercase(Locale.ROOT)
            moodWords.any { it in title || it in artist }
        }

        // 4. Radio expansion:
        // Prioritize matching user seeds, or user's top completed seeds, keeping recommendations
        // 100% inside the user's sonic and linguistic universe
        val radioSeeds = if (directlyMatchingSeeds.isNotEmpty()) {
            directlyMatchingSeeds.shuffled().take(4)
        } else {
            seeds.shuffled().take(4)
        }

        val radioTracksDeferred = async {
            val radioQueue = mutableListOf<Song>()
            for (seed in radioSeeds) {
                if (radioQueue.size >= 35) break
                val related = runCatching { YtMusicRepository.radio(seed.videoId).getOrNull().orEmpty() }.getOrDefault(emptyList())
                val extra = QueueBuilder.extend(radioQueue, related, 10)
                radioQueue += extra.filterNot { c -> radioQueue.any { it.videoId == c.videoId } }
            }
            radioQueue
        }

        // 5. Query user's top artists with mood hint to find tracks from their preferred artists in this mood
        val artistMoodTracksDeferred = async {
            val userArtists = seeds.map { it.artist }.filter { it.isNotBlank() }.distinct().take(3)
            val artistTracks = mutableListOf<Song>()
            for (artist in userArtists) {
                if (artistTracks.size >= 20) break
                val query = "$artist ${mood.title}"
                val results = runCatching {
                    YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull().orEmpty()
                }.getOrDefault(emptyList())
                val songs = results.mapNotNull { result ->
                    when (result) {
                        is SearchResult.Track -> result.song
                        is SearchResult.TopTrack -> result.song
                        else -> null
                    }
                }
                artistTracks += songs.take(6)
            }
            artistTracks
        }

        val personalMixTracks = personalizedMixTracksDeferred.await()
        val radioTracks = radioTracksDeferred.await()
        val artistMoodTracks = artistMoodTracksDeferred.await()

        val candidatePool = mutableListOf<Song>()

        // User's directly matching seeds come first
        candidatePool += directlyMatchingSeeds

        // Interleave personalized mix tracks, radio tracks, and artist mood tracks
        val pQueue = personalMixTracks.shuffled().toMutableList()
        val rQueue = radioTracks.shuffled().toMutableList()
        val aQueue = artistMoodTracks.shuffled().toMutableList()

        while ((pQueue.isNotEmpty() || rQueue.isNotEmpty() || aQueue.isNotEmpty()) && candidatePool.size < limit * 2) {
            if (pQueue.isNotEmpty()) candidatePool += pQueue.removeAt(0)
            if (rQueue.isNotEmpty()) candidatePool += rQueue.removeAt(0)
            if (aQueue.isNotEmpty()) candidatePool += aQueue.removeAt(0)
        }

        // If candidate pool is still sparse, supplement from remaining user seeds + their radios
        if (candidatePool.size < limit) {
            candidatePool += seeds
            for (seed in seeds.take(3)) {
                val more = runCatching { YtMusicRepository.radio(seed.videoId).getOrNull().orEmpty() }.getOrDefault(emptyList())
                candidatePool += more
            }
        }

        // Fallback only if candidate pool is completely empty
        if (candidatePool.isEmpty()) {
            val fallback = runCatching { YtMusicRepository.quickPicks().getOrNull().orEmpty() }.getOrDefault(emptyList())
            candidatePool += fallback
        }

        // Spread artists and enforce limit
        val spread = PersonalMixEngine.spreadArtists(candidatePool.distinctBy { it.videoId }).take(limit)
        tagMoodsMix(spread, mood)
    }

    suspend fun growQueue(
        mood: FlowMood,
        current: List<Song>,
        limit: Int = MoodsMixRules.TOPUP_TRACKS,
    ): List<Song> {
        if (current.isEmpty()) return emptyList()
        val tail = current.takeLast(4)
        val out = current.toMutableList()
        for (seed in tail) {
            if (out.size >= current.size + limit) break
            val related = runCatching { YtMusicRepository.radio(seed.videoId).getOrNull().orEmpty() }.getOrDefault(emptyList())
            val extra = QueueBuilder.extend(out, related, limit)
            out += extra.filterNot { c -> out.any { it.videoId == c.videoId } }
        }
        val distinct = out.distinctBy { it.videoId }
        return tagMoodsMix(distinct, mood)
    }

    fun tagMoodsMix(tracks: List<Song>, mood: FlowMood): List<Song> = tracks.map {
        val label = "Moods · ${mood.title}"
        it.copy(
            radioName = label,
            playbackSource = label,
            playbackSourceType = PlaybackSourceType.EXPLORE,
            playbackSourceId = MoodsMixRules.MOODS_MIX_BROWSE_ID,
        ).asQueueEntry(QueueTier.CONTEXT)
    }
}
