package com.music.bitchord.data.personalmix

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueBuilder
import com.music.bitchord.playback.QueueTimeline.asQueueEntry
import kotlin.math.min
import kotlin.random.Random

object PersonalMixEngine {

    /**
     * Builds the initial dynamic Personal Mix queue from the recent 20 completed songs.
     * If fewer than 20 completed songs are available, supplements with recent history or quick picks.
     */
    suspend fun buildInitialQueue(
        completedSeeds: List<Song>,
        limit: Int = PersonalMixRules.INITIAL_TRACKS,
    ): List<Song> {
        val seeds = if (completedSeeds.size >= PersonalMixRules.RECENT_COMPLETED_LIMIT) {
            completedSeeds.take(PersonalMixRules.RECENT_COMPLETED_LIMIT)
        } else {
            val history = runCatching { YtMusicRepository.recentHistory().getOrNull().orEmpty() }.getOrDefault(emptyList())
            val existingIds = completedSeeds.map { it.videoId }.toSet()
            val extra = history.filterNot { it.videoId in existingIds }
            (completedSeeds + extra).take(PersonalMixRules.RECENT_COMPLETED_LIMIT)
        }

        if (seeds.isEmpty()) {
            val fallback = runCatching { YtMusicRepository.quickPicks().getOrNull().orEmpty() }.getOrDefault(emptyList())
            return tagPersonalMix(fallback.take(limit))
        }

        val queue = seeds.toMutableList()
        val radioSeeds = seeds.shuffled().take(6)
        for (seed in radioSeeds) {
            if (queue.size >= limit) break
            val related = runCatching { YtMusicRepository.radio(seed.videoId).getOrNull().orEmpty() }.getOrDefault(emptyList())
            val extra = QueueBuilder.extend(queue, related, min(10, limit - queue.size))
            queue += extra.filterNot { c -> queue.any { it.videoId == c.videoId } }
        }

        val spread = spreadArtists(queue.distinctBy { it.videoId }).take(limit)
        return tagPersonalMix(spread)
    }

    /**
     * Top-up/grow the playlist dynamically as the user listens or scrolls toward the end.
     */
    suspend fun growQueue(
        current: List<Song>,
        limit: Int = PersonalMixRules.TOPUP_TRACKS,
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
        return tagPersonalMix(distinct)
    }

    fun tagPersonalMix(tracks: List<Song>): List<Song> = tracks.map {
        it.copy(
            radioName = PersonalMixRules.PERSONAL_MIX_NAME,
            playbackSource = PersonalMixRules.PERSONAL_MIX_NAME,
            playbackSourceType = PlaybackSourceType.EXPLORE,
            playbackSourceId = PersonalMixRules.PERSONAL_MIX_BROWSE_ID,
        ).asQueueEntry(QueueTier.CONTEXT)
    }

    fun spreadArtists(tracks: List<Song>, random: Random = Random.Default): List<Song> {
        if (tracks.size < 4) return tracks
        val out = mutableListOf<Song>()
        val buckets = tracks.groupBy { it.artist.lowercase() }.mapValues { it.value.toMutableList() }
        val keys = buckets.keys.shuffled(random).toMutableList()
        var ki = 0
        var guard = tracks.size * 3
        while (out.size < tracks.size && guard-- > 0) {
            if (keys.isEmpty()) break
            val k = keys[ki % keys.size]
            val bucket = buckets[k]
            if (bucket == null) {
                ki++
                continue
            }
            if (bucket.isNotEmpty()) out += bucket.removeAt(0)
            if (bucket.isEmpty()) keys.remove(k) else ki++
        }
        buckets.values.forEach { out += it }
        return out.distinctBy { it.videoId }
    }
}
