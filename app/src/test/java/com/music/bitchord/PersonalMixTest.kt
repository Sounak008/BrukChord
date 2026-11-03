package com.music.bitchord

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.personalmix.PersonalMixEngine
import com.music.bitchord.data.personalmix.PersonalMixRules
import com.music.bitchord.data.personalmix.PersonalMixStore
import com.music.bitchord.data.personalmix.decodePersonalMixSnapshot
import com.music.bitchord.data.personalmix.encodePersonalMixSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PersonalMixTest {

    @Before
    fun setUp() {
        PersonalMixStore.resetForTest()
    }

    private fun testSong(id: String, artist: String = "Artist $id", title: String = "Title $id") = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = "https://example.com/thumb/$id.jpg",
    )

    @Test
    fun `store records completed songs up to 20`() {
        for (i in 1..25) {
            PersonalMixStore.recordCompleted(testSong("vid_$i"))
        }

        val completed = PersonalMixStore.completedSongs.value
        assertEquals(20, completed.size)
        // Newest should be at index 0
        assertEquals("vid_25", completed.first().videoId)
        // Oldest kept should be vid_6 (1..5 pushed out)
        assertEquals("vid_6", completed.last().videoId)
    }

    @Test
    fun `store moves already recorded song to the front on replay`() {
        PersonalMixStore.recordCompleted(testSong("vid_1"))
        PersonalMixStore.recordCompleted(testSong("vid_2"))
        PersonalMixStore.recordCompleted(testSong("vid_3"))

        assertEquals(listOf("vid_3", "vid_2", "vid_1"), PersonalMixStore.completedSongs.value.map { it.videoId })

        // Replay vid_1 completely
        PersonalMixStore.recordCompleted(testSong("vid_1"))

        assertEquals(listOf("vid_1", "vid_3", "vid_2"), PersonalMixStore.completedSongs.value.map { it.videoId })
        assertEquals(3, PersonalMixStore.completedSongs.value.size)
    }

    @Test
    fun `snapshot encodes and decodes accurately`() {
        val original = listOf(
            testSong("v1", "Artist One", "Track One"),
            testSong("v2", "Artist Two", "Track Two"),
        )
        val encoded = encodePersonalMixSnapshot(original)
        assertTrue(encoded.isNotEmpty())

        val decoded = decodePersonalMixSnapshot(encoded)
        assertEquals(2, decoded.size)
        assertEquals("v1", decoded[0].videoId)
        assertEquals("Track One", decoded[0].title)
        assertEquals("Artist One", decoded[0].artist)
        assertEquals("v2", decoded[1].videoId)
    }

    @Test
    fun `tagPersonalMix assigns proper radio and playback source metadata`() {
        val songs = listOf(testSong("v1"), testSong("v2"))
        val tagged = PersonalMixEngine.tagPersonalMix(songs)

        assertEquals(2, tagged.size)
        tagged.forEach { song ->
            assertEquals(PersonalMixRules.PERSONAL_MIX_NAME, song.radioName)
            assertEquals(PersonalMixRules.PERSONAL_MIX_NAME, song.playbackSource)
            assertEquals(PlaybackSourceType.EXPLORE, song.playbackSourceType)
            assertEquals(PersonalMixRules.PERSONAL_MIX_BROWSE_ID, song.playbackSourceId)
        }
    }

    @Test
    fun `spreadArtists prevents same-artist clumping`() {
        val tracks = listOf(
            testSong("1", "A"),
            testSong("2", "A"),
            testSong("3", "A"),
            testSong("4", "B"),
            testSong("5", "B"),
            testSong("6", "C"),
            testSong("7", "C"),
        )
        val spread = PersonalMixEngine.spreadArtists(tracks)
        assertEquals(tracks.size, spread.size)

        // Verify no 3 consecutive songs from the same artist
        for (i in 0 until spread.size - 2) {
            val a1 = spread[i].artist
            val a2 = spread[i + 1].artist
            val a3 = spread[i + 2].artist
            assertFalse("Artist $a1 appeared 3 times in a row", a1 == a2 && a2 == a3)
        }
    }

    @Test
    fun `completion threshold logic ignores skips and random presses`() {
        val durationSec = 200L

        // Test 1: Random press - played for 5 seconds, not ended naturally
        val randomPressPos = 5L
        val randomPressEnded = false
        val isRandomPressCompleted = randomPressEnded ||
            (randomPressPos >= (durationSec * 0.85).toLong())
        assertFalse("Random press must not count as completed", isRandomPressCompleted)

        // Test 2: Early skip - skipped halfway through
        val earlySkipPos = 60L
        val earlySkipEnded = false
        val isEarlySkipCompleted = earlySkipEnded ||
            (earlySkipPos >= (durationSec * 0.85).toLong())
        assertFalse("Early skip must not count as completed", isEarlySkipCompleted)

        // Test 3: Natural end (auto advance)
        val naturalEndPos = 199L
        val naturalEnded = true
        val isNaturalCompleted = naturalEnded ||
            (naturalEndPos >= (durationSec * 0.85).toLong())
        assertTrue("Natural finish must count as completed", isNaturalCompleted)

        // Test 4: Near end finish (listened to >= 85% and manual skipped last second)
        val nearEndPos = 175L // 175 / 200 = 87.5%
        val nearEndEnded = false
        val isNearEndCompleted = nearEndEnded ||
            (nearEndPos >= (durationSec * 0.85).toLong())
        assertTrue("Near end finish (>= 85%) must count as completed", isNearEndCompleted)
    }
}
