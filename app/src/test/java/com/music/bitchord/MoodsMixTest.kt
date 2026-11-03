package com.music.bitchord

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.moodsmix.FlowMood
import com.music.bitchord.data.moodsmix.MoodsMixEngine
import com.music.bitchord.data.moodsmix.MoodsMixRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodsMixTest {

    private fun testSong(id: String, artist: String = "Artist $id", title: String = "Title $id") = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = "https://example.com/thumb/$id.jpg",
    )

    @Test
    fun `flow mood enum has all required mood options`() {
        val moods = FlowMood.entries.map { it.title }
        assertTrue("Flow" !in moods)
        assertEquals(6, FlowMood.entries.size)
        assertTrue("Chill" in moods)
        assertTrue("Workout" in moods)
        assertTrue("Focus" in moods)
        assertTrue("Party" in moods)
        assertTrue("Love" in moods)
        assertTrue("Sad" in moods)
    }

    @Test
    fun `tagMoodsMix assigns proper radio and playback source metadata`() {
        val songs = listOf(testSong("m1"), testSong("m2"))
        val tagged = MoodsMixEngine.tagMoodsMix(songs, FlowMood.CHILL)

        assertEquals(2, tagged.size)
        tagged.forEach { song ->
            assertEquals("Moods · Chill", song.radioName)
            assertEquals("Moods · Chill", song.playbackSource)
            assertEquals(PlaybackSourceType.EXPLORE, song.playbackSourceType)
            assertEquals(MoodsMixRules.MOODS_MIX_BROWSE_ID, song.playbackSourceId)
        }
    }

    @Test
    fun `moods mix rules have proper browse id and limits`() {
        assertEquals("Moods", MoodsMixRules.MOODS_MIX_NAME)
        assertEquals("bitchord:moods_mix", MoodsMixRules.MOODS_MIX_BROWSE_ID)
        assertEquals(50, MoodsMixRules.INITIAL_TRACKS)
        assertEquals(10, MoodsMixRules.TOPUP_TRACKS)
    }
}
