package com.music.bitchord.data.replaymix

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.personalmix.PersonalMixEngine
import com.music.bitchord.playback.QueueBuilder
import com.music.bitchord.playback.QueueTimeline.asQueueEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Locale

object ReplayMixEngine {

    /**
     * Builds the Replay Mix queue combining:
     * 1. The app's own replay feature (most played / listened songs from ListeningStats).
     * 2. YouTube Music's Replay (official Replay Mix playlist from Mixed for you & repeated songs in history).
     * 3. Completed plays from PersonalMixStore.
     */
    suspend fun buildQueue(
        appReplaySeeds: List<Song>,
        completedSeeds: List<Song> = emptyList(),
        limit: Int = ReplayMixRules.INITIAL_TRACKS,
    ): List<Song> = coroutineScope {
        // 1. Fetch YouTube's personalized Replay Mix and recent history in parallel
        val ytReplayMixDeferred = async {
            runCatching {
                val mixes = YtMusicRepository.mixedForYou().getOrNull().orEmpty()
                val replayItem = mixes.firstOrNull { item ->
                    val title = item.title.lowercase(Locale.ROOT)
                    "replay" in title || "on repeat" in title || "heavy rotation" in title
                }
                if (replayItem?.browseId != null) {
                    val playlistId = replayItem.browseId.removePrefix("VL")
                    YtMusicRepository.playlistAsAudio(playlistId).getOrNull().orEmpty()
                } else {
                    emptyList()
                }
            }.getOrDefault(emptyList())
        }

        val ytHistoryDeferred = async {
            runCatching {
                YtMusicRepository.recentHistory().getOrNull().orEmpty()
            }.getOrDefault(emptyList())
        }

        val ytReplayTracks = ytReplayMixDeferred.await()
        val ytHistoryTracks = ytHistoryDeferred.await()

        // 2. Identify repeat plays in YouTube history (tracks appearing 2+ times)
        val historyRepeats = ytHistoryTracks
            .groupBy { it.videoId }
            .filter { it.value.size > 1 }
            .toList()
            .sortedByDescending { it.second.size }
            .map { it.second.first() }

        // 3. Score and merge candidates based on app replay + youtube replay
        val appReplayIds = appReplaySeeds.map { it.videoId }.toSet()
        val ytReplayIds = ytReplayTracks.map { it.videoId }.toSet()
        val historyRepeatIds = historyRepeats.map { it.videoId }.toSet()

        val candidatePool = mutableListOf<Song>()

        // Priority 1: Songs present in BOTH app Replay and YouTube Replay (true heavy replay across both)
        val crossPlatformReplay = appReplaySeeds.filter { it.videoId in ytReplayIds || it.videoId in historyRepeatIds }
        candidatePool += crossPlatformReplay

        // Priority 2: Songs from YouTube's official Replay Mix
        candidatePool += ytReplayTracks

        // Priority 3: Songs from the app's own Replay chart
        candidatePool += appReplaySeeds

        // Priority 4: Songs replayed multiple times in YouTube history
        candidatePool += historyRepeats

        // Priority 5: Completed songs from PersonalMixStore
        candidatePool += completedSeeds

        // Priority 6: Remaining songs from YouTube history
        candidatePool += ytHistoryTracks

        val uniqueCandidates = candidatePool.distinctBy { it.videoId }.toMutableList()

        // Fallback for new installations or signed-out users with minimal replay history
        if (uniqueCandidates.size < limit) {
            val fallback = runCatching { YtMusicRepository.quickPicks().getOrNull().orEmpty() }.getOrDefault(emptyList())
            val existingIds = uniqueCandidates.map { it.videoId }.toSet()
            uniqueCandidates += fallback.filterNot { it.videoId in existingIds }
        }

        val spread = PersonalMixEngine.spreadArtists(uniqueCandidates).take(limit)
        tagReplayMix(spread)
    }

    /**
     * Top-up / grow the Replay Mix playlist dynamically as the user scrolls toward the end.
     */
    suspend fun growQueue(
        current: List<Song>,
        limit: Int = ReplayMixRules.TOPUP_TRACKS,
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
        return tagReplayMix(distinct)
    }

    fun tagReplayMix(tracks: List<Song>): List<Song> = tracks.map {
        it.copy(
            radioName = ReplayMixRules.REPLAY_MIX_NAME,
            playbackSource = ReplayMixRules.REPLAY_MIX_NAME,
            playbackSourceType = PlaybackSourceType.EXPLORE,
            playbackSourceId = ReplayMixRules.REPLAY_MIX_BROWSE_ID,
        ).asQueueEntry(QueueTier.CONTEXT)
    }
}
