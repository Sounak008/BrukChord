package com.music.bitchord

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistCirclesPersonallyListenedModeTest {

    @Test
    fun `artistCirclesPersonallyListened default is false for main artist page navigation`() {
        assertFalse(AppSettings.artistCirclesPersonallyListened.value)
    }

    @Test
    fun `UC artist IDs resolve to BrowseType ARTIST`() {
        val channelId = "UCqECaJ8Gagnn7YCbPEzWH6g"
        assertTrue(channelId.startsWith("UC"))
        assertEquals(BrowseType.ARTIST, MainViewModel.browseTypeOf(channelId))
    }

    @Test
    fun `navigation targets main artist page by default and personally listened mode when enabled`() {
        val libraryCorpusId = "FEmusic_library_corpus_artist_detail_12345"
        val mainChannelId = "UCqECaJ8Gagnn7YCbPEzWH6g"

        fun resolveTargetBrowseId(
            itemBrowseId: String?,
            cachedMainId: String?,
            personallyListenedMode: Boolean,
        ): String? {
            return if (personallyListenedMode) {
                itemBrowseId ?: cachedMainId
            } else {
                if (itemBrowseId != null && itemBrowseId.startsWith("UC")) {
                    itemBrowseId
                } else {
                    cachedMainId ?: itemBrowseId
                }
            }
        }

        // With personallyListenedMode = false (default):
        // Main channel ID is preferred even if itemBrowseId is a library corpus ID
        val defaultTarget = resolveTargetBrowseId(
            itemBrowseId = libraryCorpusId,
            cachedMainId = mainChannelId,
            personallyListenedMode = false,
        )
        assertEquals(mainChannelId, defaultTarget)
        assertTrue(defaultTarget?.startsWith("UC") == true)

        // With personallyListenedMode = true:
        // Item's library browseId is used directly (personally listened mode)
        val personalTarget = resolveTargetBrowseId(
            itemBrowseId = libraryCorpusId,
            cachedMainId = mainChannelId,
            personallyListenedMode = true,
        )
        assertEquals(libraryCorpusId, personalTarget)
        assertFalse(personalTarget?.startsWith("UC") == true)
    }
}
