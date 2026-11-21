package com.music.bitchord

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.personalmix.PersonalMixEngine
import com.music.bitchord.data.personalmix.decodePersonalMixSnapshot
import com.music.bitchord.data.personalmix.encodePersonalMixSnapshot
import com.music.bitchord.data.replaymix.ReplayMixEngine
import com.music.bitchord.data.replaymix.ReplayMixRules
import com.music.bitchord.data.replaymix.ReplayMixStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReplayMixTest {

    @Before
    fun setUp() {
        ReplayMixStore.resetForTest()
    }

    private fun testSong(id: String, artist: String = "Artist $id", title: String = "Title $id") = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = "https://example.com/thumb/$id.jpg",
    )

    @Test
    fun `replay mix rules have proper browse id, name and limits`() {
        assertEquals("Replay Mix", ReplayMixRules.REPLAY_MIX_NAME)
        assertEquals("bitchord:replay_mix", ReplayMixRules.REPLAY_MIX_BROWSE_ID)
        assertEquals(50, ReplayMixRules.INITIAL_TRACKS)
        assertEquals(10, ReplayMixRules.TOPUP_TRACKS)
    }

    @Test
    fun `tagReplayMix assigns proper radio and playback source metadata`() {
        val songs = listOf(testSong("r1"), testSong("r2"))
        val tagged = ReplayMixEngine.tagReplayMix(songs)

        assertEquals(2, tagged.size)
        tagged.forEach { song ->
            assertEquals(ReplayMixRules.REPLAY_MIX_NAME, song.radioName)
            assertEquals(ReplayMixRules.REPLAY_MIX_NAME, song.playbackSource)
            assertEquals(PlaybackSourceType.EXPLORE, song.playbackSourceType)
            assertEquals(ReplayMixRules.REPLAY_MIX_BROWSE_ID, song.playbackSourceId)
        }
    }

    @Test
    fun `snapshot encodes and decodes accurately for replay mix`() {
        val original = listOf(
            testSong("r1", "Replay Artist", "Replay Song 1"),
            testSong("r2", "Replay Artist 2", "Replay Song 2"),
        )
        val encoded = encodePersonalMixSnapshot(original)
        assertTrue(encoded.isNotEmpty())

        val decoded = decodePersonalMixSnapshot(encoded)
        assertEquals(2, decoded.size)
        assertEquals("r1", decoded[0].videoId)
        assertEquals("Replay Song 1", decoded[0].title)
        assertEquals("Replay Artist", decoded[0].artist)
        assertEquals("r2", decoded[1].videoId)
    }

    @Test
    fun `spreadArtists distributes repetitive artists evenly`() {
        val songs = listOf(
            testSong("1", "Queen"),
            testSong("2", "Queen"),
            testSong("3", "Queen"),
            testSong("4", "Radiohead"),
            testSong("5", "Radiohead"),
            testSong("6", "Pink Floyd"),
            testSong("7", "Pink Floyd"),
        )

        val spread = PersonalMixEngine.spreadArtists(songs)
        assertEquals(7, spread.size)

        // Verify no 3 consecutive songs from the same artist
        for (i in 0 until spread.size - 2) {
            val a1 = spread[i].artist
            val a2 = spread[i + 1].artist
            val a3 = spread[i + 2].artist
            assertFalse("Artist $a1 appeared 3 times in a row", a1 == a2 && a2 == a3)
        }
    }
}
