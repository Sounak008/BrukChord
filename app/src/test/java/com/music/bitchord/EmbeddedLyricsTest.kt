package com.music.bitchord

import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.lyrics.LrcLib
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
import com.music.bitchord.data.lyrics.toEnhancedLrc
import com.music.bitchord.data.lyrics.toLrc
import com.music.bitchord.download.FlacTagger
import com.music.bitchord.download.Mp4Tagger
import com.music.bitchord.download.WebmTagger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * The reader and the taggers have to agree, and nothing else checks that
 * they do: a download writes the file on a device and the player reads it back
 * on another run, so a disagreement between the two shows up as a downloaded
 * song that silently has no lyrics — which is exactly the bug this pair was
 * written for.
 *
 * Each test writes with the real tagger and reads with the real reader, so a
 * change to either side that breaks the pairing fails here rather than on a
 * phone. Placeholder text throughout; no real lyric appears in this file.
 */
class EmbeddedLyricsTest {

    private val lrc = "[00:01.00]first line\n[00:05.00]second line"

    @Test
    fun `an m4a written by the tagger reads back`() {
        val tagged = Mp4Tagger.tag(
            bytes = minimalMp4(),
            title = "t",
            artist = "a",
            album = null,
            lyrics = lrc,
            cover = null,
            coverIsPng = false,
        )
        assertEquals(lrc, EmbeddedLyrics.fromBytes(tagged))
    }

    @Test
    fun `a flac written by the tagger reads back`() {
        val tagged = FlacTagger.tag(
            bytes = minimalFlac(),
            title = "t",
            artist = "a",
            album = null,
            lyrics = lrc,
            cover = null,
            coverMime = "image/jpeg",
        )
        assertEquals(lrc, EmbeddedLyrics.fromBytes(tagged))
    }

    @Test
    fun `a webm written by the tagger reads back`() {
        val tagged = WebmTagger.tag(
            bytes = minimalWebm(),
            title = "t",
            artist = "a",
            album = null,
            lyrics = lrc,
            cover = null,
            coverMime = "image/jpeg",
        )
        assertEquals(lrc, EmbeddedLyrics.fromBytes(tagged))
    }

    /**
     * A cover is a `data` box too, and it sits in the same `ilst` as the lyrics.
     * Reading the first one that turns up rather than the lyrics' own would
     * hand a JPEG back as a string.
     */
    @Test
    fun `a cover alongside the lyrics is not mistaken for them`() {
        val tagged = Mp4Tagger.tag(
            bytes = minimalMp4(),
            title = "t",
            artist = "a",
            album = null,
            lyrics = lrc,
            cover = ByteArray(64) { 0x7F },
            coverIsPng = false,
        )
        assertEquals(lrc, EmbeddedLyrics.fromBytes(tagged))
    }

    /**
     * The whole point of the second field: a word-synced download has to come
     * back word-synced, or a downloaded song silently drops to whole-line
     * highlighting while a streamed one keeps its syllables.
     */
    @Test
    fun `word timings survive the write and the read, in all three containers`() {
        val words = listOf(
            LyricLine(
                timeMs = 1_000L,
                text = "two words",
                words = listOf(
                    LyricWord(startMs = 1_000L, endMs = 1_400L, text = "two"),
                    LyricWord(startMs = 1_400L, endMs = 2_000L, text = "words"),
                ),
            ),
        )
        val plain = words.toLrc()
        val enhanced = words.toEnhancedLrc()

        val written = listOf(
            Mp4Tagger.tag(minimalMp4(), "t", "a", null, plain, null, false, enhanced),
            FlacTagger.tag(minimalFlac(), "t", "a", null, plain, null, "image/jpeg", enhanced),
            WebmTagger.tag(minimalWebm(), "t", "a", null, plain, null, "image/jpeg", enhanced),
        )
        for (bytes in written) {
            // The word-timed field wins over the plain one sitting beside it.
            val read = LrcLib.parseLrc(requireNotNull(EmbeddedLyrics.fromBytes(bytes)))
            assertEquals(1, read.size)
            assertEquals("two words", read[0].text)
            assertEquals(listOf("two", "words"), read[0].words.map { it.text })
            assertEquals(listOf(1_000L, 1_400L), read[0].words.map { it.startMs })
            assertEquals(2_000L, read[0].endMs)
        }
    }

    /**
     * The standard field must stay plain whatever else is written beside it —
     * a reader without A2 shows `<00:01.00>` rather than skipping it, which is
     * the reason there are two fields at all.
     */
    @Test
    fun `the portable field never carries word stamps`() {
        val words = listOf(
            LyricLine(
                timeMs = 1_000L,
                text = "two words",
                words = listOf(
                    LyricWord(1_000L, 1_400L, "two"),
                    LyricWord(1_400L, 2_000L, "words"),
                ),
            ),
        )
        assertEquals("[00:01.00]two words", words.toLrc())
    }

    @Test
    fun `a file with no lyrics at all reads back as nothing`() {
        val tagged = Mp4Tagger.tag(
            bytes = minimalMp4(),
            title = "t",
            artist = "a",
            album = null,
            lyrics = null,
            cover = null,
            coverIsPng = false,
        )
        assertNull(EmbeddedLyrics.fromBytes(tagged))
    }

    @Test
    fun `something that is none of the three containers is not guessed at`() {
        assertNull(EmbeddedLyrics.fromBytes(ByteArray(512) { it.toByte() }))
    }

    // ---- Offline packages ---------------------------------------------------

    /**
     * The one download with no container to read: an HLS or DASH package is a
     * playlist over a directory of segments, so its lyrics are written beside
     * it. They were written and never read for as long as offline packages have
     * existed — a downloaded Tidal track showed no lyrics offline and went to
     * the network for them online, which looks like a lyrics bug rather than a
     * download one and so stayed hidden.
     */
    @Test
    fun `a package's lyrics come from the sidecar beside its playlist`() {
        val root = packageDir("lyrics.lrc" to lrc)
        assertEquals(lrc, EmbeddedLyrics.sidecar(File(root, "playlist.m3u8").path))
    }

    /** The A2 form is what the writers save, and it has to survive the trip whole. */
    @Test
    fun `word timings survive a sidecar too`() {
        val words = listOf(
            LyricLine(
                timeMs = 1_000L,
                text = "two words",
                words = listOf(
                    LyricWord(1_000L, 1_400L, "two"),
                    LyricWord(1_400L, 2_000L, "words"),
                ),
            ),
        )
        val root = packageDir("lyrics.lrc" to words.toEnhancedLrc())
        val read = LrcLib.parseLrc(requireNotNull(EmbeddedLyrics.sidecar(File(root, "playlist.m3u8").path)))
        assertEquals(listOf("two", "words"), read.single().words.map { it.text })
        assertEquals(listOf(1_000L, 1_400L), read.single().words.map { it.startMs })
    }

    @Test
    fun `a package with no lyrics is left to the container reader`() {
        // Saved without lyrics — the empty file the writer leaves behind must
        // not read as "there are lyrics, they are blank".
        assertNull(EmbeddedLyrics.sidecar(File(packageDir("lyrics.lrc" to "  \n"), "playlist.m3u8").path))
        assertNull(EmbeddedLyrics.sidecar(File(packageDir(), "playlist.m3u8").path))
        assertNull(EmbeddedLyrics.sidecar(null))
    }

    // ---- Lyric files placed beside ordinary audio ---------------------------

    /** `<song>.lrc` next to the audio: the usual way a library carries lyrics. */
    @Test
    fun `a lyrics file named after the song is read`() {
        val root = albumDir("Song.flac" to "", "Song.lrc" to lrc)
        assertEquals(lrc, EmbeddedLyrics.sidecar(File(root, "Song.flac").path))
    }

    /** Case is not something a listener should have to get right. */
    @Test
    fun `the lyrics file's extension is matched without case`() {
        val root = albumDir("Song.flac" to "", "song.LRC" to lrc)
        assertEquals(lrc, EmbeddedLyrics.sidecar(File(root, "Song.flac").path))
    }

    /** A plain `.txt` is what half the world saves lyrics as. */
    @Test
    fun `a plain text lyrics file is read too`() {
        val root = albumDir("Song.mp3" to "", "Song.txt" to lrc)
        assertEquals(lrc, EmbeddedLyrics.sidecar(File(root, "Song.mp3").path))
    }

    /**
     * With one track in the folder, `lyrics.lrc` can only be that track's — the
     * placement a listener uses when they do not want to name the file.
     */
    @Test
    fun `a folder's single lyrics file is read for its only track`() {
        val root = albumDir("Song.flac" to "", "lyrics.lrc" to lrc)
        assertEquals(lrc, EmbeddedLyrics.sidecar(File(root, "Song.flac").path))
    }

    /**
     * The same file in a folder of many tracks belongs to one of them at most,
     * and which one it is cannot be guessed: serving it to all of them would
     * put the wrong lyrics under every song.
     */
    @Test
    fun `a folder's lyrics file is not served to every track in an album`() {
        val root = albumDir("One.flac" to "", "Two.flac" to "", "lyrics.lrc" to lrc)
        assertNull(EmbeddedLyrics.sidecar(File(root, "One.flac").path))
        assertNull(EmbeddedLyrics.sidecar(File(root, "Two.flac").path))
        // …but the song's own file is still found.
        File(root, "Two.lrc").writeText(lrc)
        assertEquals(lrc, EmbeddedLyrics.sidecar(File(root, "Two.flac").path))
    }

    // ---- ID3v2, Ogg and WAV -------------------------------------------------

    /** `USLT` is the standard frame, and the one most MP3 taggers write. */
    @Test
    fun `an mp3 USLT frame reads back`() {
        val tag = id3v23(id3Frame("USLT", usltPayload(lrc)))
        assertEquals(lrc, EmbeddedLyrics.fromBytes(tag + ByteArray(32) { 0x55 }))
    }

    /** A `TXXX` naming this app's own field wins over a `USLT` beside it. */
    @Test
    fun `an mp3 TXXX frame with this app's field wins over USLT`() {
        val enhanced = "[00:01.00]one two"
        val tag = id3v23(
            id3Frame("USLT", usltPayload(lrc)),
            id3Frame("TXXX", txxxPayload("BITCHORD_LYRICS", enhanced)),
        )
        assertEquals(enhanced, EmbeddedLyrics.fromBytes(tag))
    }

    /** A line-timed `SYLT` becomes plain LRC so it can go through the parser. */
    @Test
    fun `an mp3 SYLT frame becomes LRC lines`() {
        val tag = id3v23(id3Frame("SYLT", syltPayload(listOf("first line" to 1_000L, "second" to 5_500L))))
        assertEquals("[00:01.00]first line\n[00:05.50]second\n", EmbeddedLyrics.fromBytes(tag))
    }

    /** SYLT with word-by-word entries preserves word-level timing when parsed. */
    @Test
    fun `an mp3 SYLT frame with word-by-word timing parses to word-timed lines`() {
        val payload = syltPayload(listOf("two " to 1_000L, "words\n" to 1_400L, "" to 2_000L))
        val tag = id3v23(id3Frame("SYLT", payload))
        val raw = requireNotNull(EmbeddedLyrics.fromBytes(tag))
        val parsed = requireNotNull(EmbeddedLyrics.parse(raw))
        assertEquals(1, parsed.size)
        assertEquals("two words", parsed[0].text)
        assertEquals(listOf("two", "words"), parsed[0].words.map { it.text })
        assertEquals(listOf(1_000L, 1_400L), parsed[0].words.map { it.startMs })
        assertEquals(2_000L, parsed[0].endMs)
    }

    /** SYLT synchronized lyrics win over plain unsynchronized USLT text. */
    @Test
    fun `SYLT wins over plain USLT frame in ID3`() {
        val plainText = "plain unsynced lyrics without any stamps"
        val tag = id3v23(
            id3Frame("USLT", usltPayload(plainText)),
            id3Frame("SYLT", syltPayload(listOf("first line" to 1_000L, "second" to 5_500L))),
        )
        assertEquals("[00:01.00]first line\n[00:05.50]second\n", EmbeddedLyrics.fromBytes(tag))
    }

    /** SYNCEDLYRICS in TXXX wins over plain USLT. */
    @Test
    fun `SYNCEDLYRICS in TXXX wins over plain USLT`() {
        val plainText = "plain lyrics"
        val tag = id3v23(
            id3Frame("USLT", usltPayload(plainText)),
            id3Frame("TXXX", txxxPayload("SYNCEDLYRICS", lrc)),
        )
        assertEquals(lrc, EmbeddedLyrics.fromBytes(tag))
    }

    /** A TTML word-by-word sidecar file is discovered and parsed. */
    @Test
    fun `a ttml sidecar file is read and parsed word-by-word`() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:01.000" end="00:02.000">
                    <span begin="00:01.000" end="00:01.400">two </span>
                    <span begin="00:01.400" end="00:02.000">words</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()
        val root = albumDir("Song.mp3" to "", "Song.ttml" to ttml)
        val raw = requireNotNull(EmbeddedLyrics.sidecar(File(root, "Song.mp3").path))
        val parsed = requireNotNull(EmbeddedLyrics.parse(raw))
        assertEquals(1, parsed.size)
        assertEquals("two words", parsed[0].text)
        assertEquals(listOf("two", "words"), parsed[0].words.map { it.text })
        assertEquals(listOf(1_000L, 1_400L), parsed[0].words.map { it.startMs })
    }

    /** A QRC karaoke sidecar file is discovered and parsed word-by-word. */
    @Test
    fun `a qrc karaoke sidecar file is read and parsed word-by-word`() {
        val qrc = "[1000,1000](1000,400)two (1400,600)words"
        val root = albumDir("Song.flac" to "", "Song.qrc" to qrc)
        val raw = requireNotNull(EmbeddedLyrics.sidecar(File(root, "Song.flac").path))
        val parsed = requireNotNull(EmbeddedLyrics.parse(raw))
        assertEquals(1, parsed.size)
        assertEquals("two words", parsed[0].text)
        assertEquals(listOf("two", "words"), parsed[0].words.map { it.text })
    }

    /** An SRT sidecar file is discovered and parsed into lines with duration. */
    @Test
    fun `an srt sidecar file is read and parsed`() {
        val srt = "1\n00:00:01,000 --> 00:00:03,000\nfirst line\n\n2\n00:00:05,500 --> 00:00:08,000\nsecond line\n"
        val root = albumDir("Song.mp3" to "", "Song.srt" to srt)
        val raw = requireNotNull(EmbeddedLyrics.sidecar(File(root, "Song.mp3").path))
        val parsed = requireNotNull(EmbeddedLyrics.parse(raw))
        assertEquals(2, parsed.size)
        assertEquals("first line", parsed[0].text)
        assertEquals(1_000L, parsed[0].timeMs)
        assertEquals(3_000L, parsed[0].sungUntilMs)
        assertEquals("second line", parsed[1].text)
        assertEquals(5_500L, parsed[1].timeMs)
    }

    /** A plain text lyrics file parses to non-empty lines with zero timestamps. */
    @Test
    fun `a plain text lyrics file parses to non-empty lines with zero timestamps`() {
        val text = "first line\nsecond line"
        val parsed = requireNotNull(EmbeddedLyrics.parse(text))
        assertEquals(2, parsed.size)
        assertEquals("first line", parsed[0].text)
        assertEquals(0L, parsed[0].timeMs)
        assertEquals("second line", parsed[1].text)
        assertEquals(0L, parsed[1].timeMs)
    }

    /** A FLAC vorbis comment with SYNCEDLYRICS is read back. */
    @Test
    fun `a flac vorbis comment with SYNCEDLYRICS reads back`() {
        val bytes = oggPage(vorbisCommentPacket("SYNCEDLYRICS=$lrc"))
        assertEquals(lrc, EmbeddedLyrics.fromBytes(bytes))
    }

    /** UTF-16 is the other encoding taggers use for lyrics. */
    @Test
    fun `an mp3 USLT frame encoded as UTF-16 reads back`() {
        val text = "line one\nline two"
        val payload = byteArrayOf(1, 'e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(), 0, 0) +
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            text.toByteArray(Charsets.UTF_16LE)
        assertEquals(text, EmbeddedLyrics.fromBytes(id3v23(id3Frame("USLT", payload))))
    }

    /** Ogg Vorbis comments are where a `.ogg` library keeps `LYRICS`. */
    @Test
    fun `an ogg vorbis comment reads back`() {
        val bytes = oggPage(vorbisCommentPacket("LYRICS=$lrc"))
        assertEquals(lrc, EmbeddedLyrics.fromBytes(bytes))
    }

    /** Opus uses the same comment layout under a different magic. */
    @Test
    fun `an opus comments packet reads back`() {
        val bytes = oggPage(opusTagsPacket("LYRICS=$lrc"))
        assertEquals(lrc, EmbeddedLyrics.fromBytes(bytes))
    }

    /** WAV has no native tag block; the `id3 ` chunk is an ID3v2 tag. */
    @Test
    fun `a wav id3 chunk reads back`() {
        val tag = id3v23(id3Frame("USLT", usltPayload(lrc)))
        val chunk = "id3 ".toByteArray(Charsets.US_ASCII) + le32(tag.size) + tag
        val bytes = "RIFF".toByteArray(Charsets.US_ASCII) + le32(4 + chunk.size) +
            "WAVE".toByteArray(Charsets.US_ASCII) + chunk
        assertEquals(lrc, EmbeddedLyrics.fromBytes(bytes))
    }

    /** An MP3 with no tag at all must not be read as something it isn't. */
    @Test
    fun `an untagged mp3 is not guessed at`() {
        val frames = ByteArray(512) { 0xFF.toByte() }
        assertNull(EmbeddedLyrics.fromBytes(frames))
    }

    /** A directory holding the files named in [files], one of which is the audio. */
    private fun albumDir(vararg files: Pair<String, String>): File {
        val root = createTempDirectory("album").toFile().also(File::deleteOnExit)
        files.forEach { (name, text) -> File(root, name).writeText(text) }
        return root
    }

    // ---- Minimal tags, just enough shape for the reader to walk ------------

    private fun syncSafe(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    private fun id3v23(vararg frames: ByteArray): ByteArray {
        val body = frames.fold(ByteArray(0)) { acc, frame -> acc + frame }
        return "ID3".toByteArray(Charsets.US_ASCII) + byteArrayOf(3, 0, 0) + syncSafe(body.size) + body
    }

    private fun id3Frame(id: String, payload: ByteArray): ByteArray =
        id.toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(
                (payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(),
                (payload.size ushr 8).toByte(), payload.size.toByte(),
                0, 0,
            ) + payload

    /** encoding(1) + language(3) + empty descriptor(1) + UTF-8 text. */
    private fun usltPayload(text: String): ByteArray =
        byteArrayOf(3, 'e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(), 0) +
            text.toByteArray(Charsets.UTF_8)

    /** encoding(1) + description + terminator + value. */
    private fun txxxPayload(name: String, text: String): ByteArray =
        byteArrayOf(3) + name.toByteArray(Charsets.UTF_8) + byteArrayOf(0) +
            text.toByteArray(Charsets.UTF_8)

    /** encoding(1) + language(3) + ms format(1) + lyrics type(1) + descriptor + entries. */
    private fun syltPayload(lines: List<Pair<String, Long>>): ByteArray {
        val body = lines.fold(ByteArray(0)) { acc, (text, ms) ->
            acc + text.toByteArray(Charsets.UTF_8) + byteArrayOf(0) +
                byteArrayOf(
                    (ms ushr 24).toByte(), (ms ushr 16).toByte(),
                    (ms ushr 8).toByte(), ms.toByte(),
                )
        }
        return byteArrayOf(3, 'e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(), 2, 1, 0) + body
    }

    private fun le32(value: Int): ByteArray = byteArrayOf(
        value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte(),
    )

    private fun oggPage(packet: ByteArray): ByteArray =
        "OggS".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0) +
            ByteArray(8) + ByteArray(4) + ByteArray(4) + ByteArray(4) +
            byteArrayOf(1, packet.size.toByte()) + packet

    private fun vorbisCommentPacket(comment: String): ByteArray =
        byteArrayOf(0x03) + "vorbis".toByteArray(Charsets.US_ASCII) + comments(comment)

    private fun opusTagsPacket(comment: String): ByteArray =
        "OpusTags".toByteArray(Charsets.US_ASCII) + comments(comment)

    private fun comments(comment: String): ByteArray {
        val vendor = "test".toByteArray(Charsets.UTF_8)
        val entry = comment.toByteArray(Charsets.UTF_8)
        return le32(vendor.size) + vendor + le32(1) + le32(entry.size) + entry
    }

    /** A package directory holding a playlist plus whatever [files] the case needs. */
    private fun packageDir(vararg files: Pair<String, String>): File {
        val root = createTempDirectory("offline").toFile().also(File::deleteOnExit)
        File(root, "playlist.m3u8").writeText("#EXTM3U\n#EXT-X-ENDLIST\n")
        files.forEach { (name, text) -> File(root, name).writeText(text) }
        return root
    }

    // ---- Minimal containers, just enough shape for each tagger to accept ----

    /** `ftyp` then an empty `moov`, which is all [Mp4Tagger] looks for. */
    private fun minimalMp4(): ByteArray = box("ftyp", "isom".toByteArray()) + box("moov", ByteArray(0))

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return byteArrayOf(
            (size ushr 24).toByte(), (size ushr 16).toByte(),
            (size ushr 8).toByte(), size.toByte(),
        ) + type.toByteArray(Charsets.ISO_8859_1) + payload
    }

    /** `fLaC`, a last-block STREAMINFO, then a byte standing in for the frames. */
    private fun minimalFlac(): ByteArray {
        val streamInfo = ByteArray(34)
        return "fLaC".toByteArray() +
            byteArrayOf(0x80.toByte(), 0, 0, streamInfo.size.toByte()) + streamInfo +
            byteArrayOf(0xFF.toByte())
    }

    /** An EBML header and a Segment whose declared size covers the rest of the file. */
    private fun minimalWebm(): ByteArray {
        val header = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) +
            byteArrayOf(0x84.toByte()) + ByteArray(4)
        val body = ByteArray(8)
        val segment = byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67) +
            // An 8-byte length so there is room to grow it when tags are appended.
            byteArrayOf(0x01, 0, 0, 0, 0, 0, 0, body.size.toByte()) + body
        return header + segment
    }
}
