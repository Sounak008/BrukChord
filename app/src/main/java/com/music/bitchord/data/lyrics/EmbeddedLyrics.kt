package com.music.bitchord.data.lyrics

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.music.bitchord.data.DebugLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * The lyrics already sitting inside a downloaded or local file.
 *
 * The read side of what the download path wrote — see `MediaTagger`, and the
 * three taggers under it — as well as files the listener put on their device.
 * A track that was downloaded had its lyrics fetched once, at download time,
 * and written into the file; asking network servers for them again every time
 * it is played is a round trip to arrive at a string already on disk.
 *
 * Multiple fields and formats are supported, with preference given to
 * word-by-word synchronised lyrics over plain lines:
 *
 *  - `BITCHORD_LYRICS`, this app's own, holding the enhanced A2 form with word
 *    timings intact — see [toEnhancedLrc].
 *  - `SYNCEDLYRICS` / `LYRICS_SYNCED`, the standard tag used by Picard,
 *    MusicBee, LRCGet, and other desktop music taggers.
 *  - ID3 `SYLT`, the standard synchronised lyrics frame, supporting both
 *    line-synced and word-by-word / syllable timing.
 *  - Apple Music / Unison TTML lyrics (`<tt>` XML), with syllable sweeps.
 *  - QQ / NetEase Karaoke LRC (`[start,dur](start,dur)word`).
 *  - Standard `[mm:ss.xx]` LRC and `USLT` unsynchronised/plain lyrics.
 *
 * ### Local library files
 *
 * A file the listener put on the device themselves is not necessarily one of
 * the containers this app writes. MP3 is the format most of those are in, and
 * its lyrics live in an ID3 `USLT`/`TXXX`/`SYLT` frame rather than in a Vorbis
 * comment or an iTunes atom, so ID3v2 and Ogg Vorbis comments are read as well
 * as the three download formats. WAV files carrying an `id3 ` chunk go through
 * the same ID3 reader.
 *
 * ### Sidecar files
 *
 * Not every file can hold tags, and a listener who keeps their library in a
 * folder usually keeps the lyrics beside it. Seven formats are recognised:
 *
 *  - `<song>.ttml` / `lyrics.ttml` — word-by-word Apple Music / Unison XML
 *  - `<song>.qrc` / `<song>.yrc` — word-by-word Karaoke lyrics
 *  - `<song>.lrc` / `lyrics.lrc` — Enhanced word-timed LRC or standard LRC
 *  - `<song>.srt` / `<song>.vtt` — SubRip and WebVTT subtitles/lyrics
 *  - `<song>.txt` / `lyrics.txt` — Plain text lyrics
 *
 * A sidecar wins over the container: it is the listener's own file, written or
 * copied after the audio, and it is the only thing they can edit to fix a
 * wrong line. `OfflineHls` and `OfflineDash` write their `lyrics.lrc` for the
 * same reason — a package of fMP4 segments has no container to be tagged.
 */
object EmbeddedLyrics {

    private const val TAG = "BitChord"

    /**
     * Most bytes worth pulling to find a tag.
     *
     * A cap rather than a size: this reads whatever region of the file holds
     * the metadata, and that region is small in all three containers — but its
     * length is stated *by the file*, so a corrupt or hostile one could claim
     * any number at all. `LyricsTag` caps what it writes at 64k, so anything
     * past this is not a tag this app produced.
     */
    private const val MAX_TAG_BYTES = 8 * 1024 * 1024

    /**
     * The lyrics inside [uriString], or null when it has none worth showing.
     *
     * [localPath] is the filesystem path the caller already knows for the file,
     * when it knows one — the device library reads it out of MediaStore, and a
     * `content://` URI on its own does not reliably answer "which folder is
     * this in", which is what a sidecar search needs. Optional because a
     * download's `file://` URI carries the path itself.
     *
     * Touches the filesystem, so it runs on [Dispatchers.IO] regardless of
     * where it is called from.
     */
    suspend fun forUri(context: Context, uriString: String, localPath: String? = null): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            val raw = runCatching { read(context, Uri.parse(uriString), localPath) }
                .onFailure { Log.d(TAG, "no embedded lyrics in $uriString: ${it.message}") }
                .getOrNull()
                ?: return@withContext null
            parse(raw)
        }

    /**
     * Parses the raw lyric string in any supported format (TTML, Karaoke LRC,
     * Enhanced LRC, Standard LRC, SRT, WebVTT, or plain text).
     */
    internal fun parse(raw: String): List<LyricLine>? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val lines = when {
            // TTML: Apple Music word-timed, Unison, or XML format
            trimmed.contains(Regex("""<tt(?:\s|>)""", RegexOption.IGNORE_CASE)) ||
                trimmed.contains("http://www.w3.org/ns/ttml", ignoreCase = true) ||
                (trimmed.startsWith("<?xml") && trimmed.contains("<p")) -> {
                TtmlLyrics.parse(trimmed)
            }
            // Karaoke LRC: QQ/QRC and NetEase YRC word timing
            KaraokeLrc.looksLike(trimmed) -> {
                KaraokeLrc.parse(trimmed)
            }
            // SubRip (SRT) and WebVTT
            trimmed.contains("-->") -> {
                parseSrtOrVtt(trimmed)
            }
            // Enhanced LRC or Standard LRC or plain text
            else -> {
                val lrcLines = LrcLib.parseLrc(trimmed)
                if (lrcLines.any { it.isWordSynced }) {
                    lrcLines
                } else {
                    EnhancedLrc.parse(trimmed).ifEmpty { lrcLines }
                        .ifEmpty { parsePlainText(trimmed) }
                }
            }
        }

        return lines.takeIf { list -> list.any { it.text.isNotBlank() } }
            ?.withBackgroundVocals()
    }

    /**
     * Parses SubRip (`.srt`) and WebVTT (`.vtt`) subtitles into [LyricLine]s,
     * preserving cue timestamps and any inline `<...>` word stamps.
     */
    internal fun parseSrtOrVtt(text: String): List<LyricLine> {
        val cueRegex = Regex(
            """(?:(\d{1,2}):)?(\d{2}):(\d{2})[,.](\d{2,3})\s*-->\s*(?:(\d{1,2}):)?(\d{2}):(\d{2})[,.](\d{2,3})"""
        )
        val lines = mutableListOf<LyricLine>()
        val blocks = text.replace("\r\n", "\n").replace('\r', '\n').split("\n\n")
        for (block in blocks) {
            val blockLines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val timeIndex = blockLines.indexOfFirst { cueRegex.containsMatchIn(it) }
            if (timeIndex < 0) continue
            val timeMatch = cueRegex.find(blockLines[timeIndex]) ?: continue
            val startMs = parseCueTimestamp(
                timeMatch.groupValues[1],
                timeMatch.groupValues[2],
                timeMatch.groupValues[3],
                timeMatch.groupValues[4],
            )
            val endMs = parseCueTimestamp(
                timeMatch.groupValues[5],
                timeMatch.groupValues[6],
                timeMatch.groupValues[7],
                timeMatch.groupValues[8],
            )
            val body = blockLines.drop(timeIndex + 1).joinToString(" ")
            if (body.isBlank()) continue
            // WebVTT can carry inline cue timestamps: <00:01.500>word
            if (body.contains(WORD_STAMP_REGEX)) {
                val enhancedLine = "[%02d:%02d.%02d]%s".format(
                    Locale.ROOT, (startMs / 1000) / 60, (startMs / 1000) % 60, (startMs % 1000) / 10, body
                )
                val parsed = EnhancedLrc.parse(enhancedLine)
                if (parsed.isNotEmpty() && parsed.first().isWordSynced) {
                    lines += parsed.first().copy(timeMs = startMs, sungUntilMs = endMs)
                    continue
                }
            }
            lines += LyricLine(
                timeMs = startMs,
                text = body.replace(Regex("""<[^>]+>"""), "").trim(),
                sungUntilMs = endMs,
            )
        }
        return lines.sortedBy { it.timeMs }.withInstrumentalGaps()
    }

    private fun parseCueTimestamp(h: String, m: String, s: String, ms: String): Long {
        val hours = if (h.isNotEmpty()) h.toLong() else 0L
        val fraction = if (ms.length == 2) ms.toLong() * 10 else ms.toLong()
        return hours * 3_600_000L + m.toLong() * 60_000L + s.toLong() * 1_000L + fraction
    }

    private fun parsePlainText(text: String): List<LyricLine> {
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.matches(Regex("""^\[[A-Za-z]+:.*]$""")) }
            .map { LyricLine(0L, it) }
            .toList()
    }

    /** The raw lyrics text for the file: a sidecar first, then the container's own tags. */
    private fun read(context: Context, uri: Uri, localPath: String?): String? =
        sidecar(pathOf(context, uri, localPath))
            ?: open(context, uri)?.use { fromBytes(it.readAtMost(MAX_TAG_BYTES)) }

    /**
     * A path for the file behind [uri], as far as one can be had.
     *
     * A `content://` URI is not a path: MediaStore's `DATA` column is the only
     * thing that turns one into the other, and on scoped storage it is the
     * caller's cursor or nothing. [localPath] is that answer when the caller
     * already has it; this is the fallback for a caller that does not.
     */
    private fun pathOf(context: Context, uri: Uri, localPath: String?): String? {
        localPath?.takeIf { it.isNotBlank() }?.let { return it }
        if (uri.scheme == "file") return uri.path
        if (uri.scheme != "content") return null
        return runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * The lyrics file placed beside the audio file at [path].
     *
     * The stem-named file is looked for before the generic one because a folder
     * holding an album would otherwise serve every track the same lyrics. Names
     * are compared lower-cased and the directory is listed once, so `Song.LRC`
     * and `Song.lrc` are the same file to a listener — Android's filesystem is
     * case-sensitive and the listener's old music player's was not.
     *
     * Word-timed formats (.ttml, .qrc, .yrc, .lrc) are checked before plain text.
     */
    internal fun sidecar(path: String?): String? {
        val audio = path?.let(::File) ?: return null
        val dir = audio.parentFile ?: return null
        val stem = audio.nameWithoutExtension
        if (stem.isBlank()) return null
        val entries = runCatching { dir.listFiles() }.getOrNull()
            ?.filter { it.isFile }
            ?.associateBy { it.name.lowercase(Locale.ROOT) }
            ?: return null
        val playlist = audio.isPlaylist()
        // A folder holding several tracks must not serve them all the same
        // lyrics: a stray `lyrics.lrc` beside a downloaded album belongs to one
        // of them at most, and the stem-named file is the only placement that
        // can say which. It is still the right file for a folder holding a
        // single track, or for a package's playlist, which is its own case.
        val oneTrack = entries.values.count { it.extension.lowercase(Locale.ROOT) in AUDIO_EXTENSIONS } <= 1
        val wanted = buildList {
            if (!playlist) {
                add("$stem.ttml")
                add("$stem.qrc")
                add("$stem.yrc")
                add("$stem.lrc")
                add("$stem.srt")
                add("$stem.vtt")
                add("$stem.txt")
            }
            if (playlist || oneTrack) {
                add("lyrics.ttml")
                add("lyrics.qrc")
                add("lyrics.yrc")
                add("lyrics.lrc")
                add("lyrics.srt")
                add("lyrics.vtt")
                add("lyrics.txt")
            }
        }.map { it.lowercase(Locale.ROOT) }.distinct()
        for (name in wanted) {
            val file = entries[name] ?: continue
            // Bounded for the same reason [MAX_TAG_BYTES] is: this is read into
            // a string, and the size on disk is the only thing that says how
            // big one. An empty file is the writers' "there are no lyrics".
            if (file.length() !in 1..MAX_TAG_BYTES.toLong()) continue
            val text = runCatching { file.readText() }
                .onFailure { Log.d(TAG, "could not read $file: ${it.message}") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            if (text != null) return text
        }
        return null
    }

    private fun File.isPlaylist(): Boolean = name.endsWith(".m3u8", ignoreCase = true)

    /**
     * The raw lyrics text in [head], whichever container it is.
     *
     * Split from [read] so the parsing can be tested against bytes a tagger
     * just produced, without a device or a `Context` in the way — the round
     * trip is the only thing that proves a reader and a writer agree.
     */
    internal fun fromBytes(head: ByteArray): String? {
        val found = when {
            head.isId3() -> id3(head, 0)
            head.startsWith(OGG_MAGIC) -> ogg(head)
            head.startsWith(RIFF_MAGIC) -> riff(head)
            head.startsWith(FLAC_MAGIC) -> flac(head)
            head.startsWith(MATROSKA_MAGIC) -> matroska(head)
            head.isMp4() -> mp4(head)
            else -> null
        }
        return found?.takeIf { it.isNotBlank() }
    }

    private fun open(context: Context, uri: Uri): InputStream? =
        if (uri.scheme == "file") {
            uri.path?.let { File(it).takeIf(File::exists)?.inputStream() }
        } else {
            context.contentResolver.openInputStream(uri)
        }

    // ---- Tag Quality Rating -------------------------------------------------

    private val WORD_STAMP_REGEX = Regex("""<(\d{1,3}):(\d{2})[.:](\d{2,3})>""")
    private val LINE_STAMP_REGEX = Regex("""\[(\d{1,3}):(\d{2})[.:](\d{2,3})]""")
    private val TTML_WORD_REGEX = Regex("""<span(?:\s+[^>]*?)?\b(?:begin|end)=[^>]*>""", RegexOption.IGNORE_CASE)
    private val TTML_LINE_REGEX = Regex("""<p(?:\s+[^>]*?)?\bbegin=[^>]*>""", RegexOption.IGNORE_CASE)
    private val KARAOKE_WORD_REGEX = Regex("""\(\d{1,8},\d{1,8}(?:,\d{1,8})?\)""")

    /**
     * Rates the synchronisation quality of [text]:
     * 3 = Word-level timing (Enhanced LRC, TTML spans, or Karaoke)
     * 2 = Line-level timing (Standard LRC, TTML paragraphs, or SRT/VTT)
     * 1 = Plain unsynchronised lyrics
     * 0 = Blank or empty
     */
    private fun lyricQuality(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        return when {
            text.contains(WORD_STAMP_REGEX) ||
                text.contains(TTML_WORD_REGEX) ||
                text.contains(KARAOKE_WORD_REGEX) -> 3
            text.contains(LINE_STAMP_REGEX) ||
                text.contains(TTML_LINE_REGEX) ||
                text.contains("-->") -> 2
            else -> 1
        }
    }

    private fun pickBest(vararg items: String?): String? =
        items.filterNotNull().maxByOrNull { lyricQuality(it) }

    // ---- ID3v2 (MP3, and WAV's `id3 ` chunk) --------------------------------

    /**
     * The lyrics from an ID3v2 tag at [from].
     *
     * This is the frame layout every MP3 in a listener's own library uses.
     * `USLT` is the standard "unsynchronised lyrics" frame most taggers write;
     * `SYLT` is the synchronised one (with line or word-by-word timestamps);
     * `TXXX` is what a tagger writes when it has no dedicated frame for a field,
     * and it is where `BITCHORD_LYRICS` and `SYNCEDLYRICS` land. All are read,
     * with word-synced lyrics taking precedence over line-synced, and line-synced
     * over plain text.
     *
     * v2.2 frames have three-character ids, v2.3 sizes are plain big-endian and
     * v2.4 sizes are sync-safe.
     */
    private fun id3(bytes: ByteArray, from: Int): String? {
        if (from < 0 || from + 10 > bytes.size) return null
        if (!bytes.isId3(from)) return null
        val major = bytes[from + 3].toInt() and 0xFF
        if (major !in 2..4) return null
        val flags = bytes[from + 5].toInt() and 0xFF
        val declared = syncSafe(bytes, from + 6) ?: return null
        val end = (from + 10 + declared).coerceAtMost(bytes.size)
        if (end <= from + 10) return null
        // Whole-tag unsynchronisation rewrites `FF 00` back to `FF`, so the
        // frame walk has to see the decoded bytes rather than the file's own.
        val body = bytes.copyOfRange(from + 10, end)
            .let { if (flags and 0x80 != 0) deUnsynchronise(it) else it }
        var pos = 0
        if (major >= 3 && flags and 0x40 != 0) {
            if (body.size < 4) return null
            val ext = if (major == 4) syncSafe(body, 0) ?: return null
            else (readU32(body, 0).toInt() + 4)
            pos = ext
        }
        val headerLen = if (major == 2) 6 else 10
        val idLen = if (major == 2) 3 else 4
        val candidates = mutableListOf<String>()
        while (pos + headerLen <= body.size) {
            if (body[pos] == 0.toByte()) break
            val id = String(body, pos, idLen, Charsets.ISO_8859_1)
            val size = when {
                major == 2 -> ((body[pos + 3].toInt() and 0xFF) shl 16) or
                    ((body[pos + 4].toInt() and 0xFF) shl 8) or (body[pos + 5].toInt() and 0xFF)
                major == 4 -> syncSafe(body, pos + 4) ?: break
                else -> readU32(body, pos + 4).toInt()
            }
            if (size <= 0 || pos + headerLen + size > body.size) break
            val frame = body.copyOfRange(pos + headerLen, pos + headerLen + size)
            when (id) {
                "USLT", "ULT" -> textFrame(frame)?.takeIf { it.isNotBlank() }?.let { text ->
                    candidates += text
                }
                "TXXX", "TXX" -> userText(frame)?.let { (name, text) ->
                    if (text.isNotBlank()) {
                        when {
                            name.equals(WORD_LYRICS_FIELD, true) -> return text
                            name.equals("SYNCEDLYRICS", true) || name.equals("LYRICS_SYNCED", true) ||
                                name.equals("LYRICS", true) -> candidates += text
                        }
                    }
                }
                "SYLT", "SLT" -> timedLyrics(frame)?.let { candidates += it }
            }
            pos += headerLen + size
        }
        return pickBest(*candidates.toTypedArray())
    }

    /** The text of a `USLT`/`ULT` frame after its encoding byte, language and descriptor. */
    private fun textFrame(frame: ByteArray): String? {
        if (frame.size < 5) return null
        val encoding = frame[0].toInt() and 0xFF
        val (_, after) = readEncoded(frame, 4, encoding)
        return decodeText(frame, after, frame.size - after, encoding)
    }

    /** A `TXXX`'s (description, value) pair. */
    private fun userText(frame: ByteArray): Pair<String, String>? {
        if (frame.size < 2) return null
        val encoding = frame[0].toInt() and 0xFF
        val (name, after) = readEncoded(frame, 1, encoding)
        val value = decodeText(frame, after, frame.size - after, encoding)
        return name to value
    }

    /**
     * A `SYLT` frame turned into LRC lines, preserving word-by-word timing
     * when words or syllables have separate timestamps.
     *
     * The timestamp format byte says milliseconds or MPEG frames; frames cannot
     * be turned into a time without the audio header, and a wrong time is worse
     * than no lyrics, so only milliseconds are used.
     */
    private fun timedLyrics(frame: ByteArray): String? {
        if (frame.size < 7) return null
        val encoding = frame[0].toInt() and 0xFF
        val format = frame[4].toInt() and 0xFF
        if (format != 2) return null
        var pos = 6
        val (_, afterDescriptor) = readEncoded(frame, pos, encoding)
        pos = afterDescriptor
        val entries = mutableListOf<Pair<String, Long>>()
        while (pos + 4 <= frame.size) {
            val (text, next) = readEncoded(frame, pos, encoding)
            if (next + 4 > frame.size) break
            val ms = readU32(frame, next)
            pos = next + 4
            entries += text to ms
        }
        if (entries.isEmpty()) return null

        val hasNewlines = entries.any { it.first.contains('\n') || it.first.contains('\r') }
        val hasEmptyMarkers = entries.any { it.first.isEmpty() }

        // Plain line-timed entries with no line breaks or word markers
        if (!hasNewlines && !hasEmptyMarkers) {
            val lines = StringBuilder()
            for ((text, ms) in entries) {
                val total = ms / 1000
                lines.append(
                    "[%02d:%02d.%02d]%s\n".format(
                        Locale.ROOT, total / 60, total % 60, (ms % 1000) / 10, text,
                    ),
                )
            }
            return lines.takeIf { it.isNotBlank() }?.toString()
        }

        // Word/syllable-timed entries: group events into lines separated by \n or empty terminators
        val lines = StringBuilder()
        val currentLine = mutableListOf<Pair<String, Long>>()

        fun flushLine(terminatorMs: Long?) {
            if (currentLine.isEmpty()) return
            val lineStartMs = currentLine.first().second
            val lineSeconds = lineStartMs / 1000
            val lineStamp = "[%02d:%02d.%02d]".format(
                Locale.ROOT, lineSeconds / 60, lineSeconds % 60, (lineStartMs % 1000) / 10,
            )
            if (currentLine.size == 1 && terminatorMs == null) {
                val (text, _) = currentLine[0]
                lines.append(lineStamp).append(text.trimEnd('\r', '\n')).append('\n')
            } else {
                lines.append(lineStamp)
                for ((text, wordMs) in currentLine) {
                    val sec = wordMs / 1000
                    val wordStamp = "<%02d:%02d.%02d>".format(
                        Locale.ROOT, sec / 60, sec % 60, (wordMs % 1000) / 10,
                    )
                    lines.append(wordStamp).append(text.trimEnd('\r', '\n'))
                }
                if (terminatorMs != null) {
                    val sec = terminatorMs / 1000
                    val termStamp = "<%02d:%02d.%02d>".format(
                        Locale.ROOT, sec / 60, sec % 60, (terminatorMs % 1000) / 10,
                    )
                    lines.append(termStamp)
                }
                lines.append('\n')
            }
            currentLine.clear()
        }

        for (i in entries.indices) {
            val (text, ms) = entries[i]
            if (text.isEmpty()) {
                flushLine(ms)
            } else if (text.contains('\n') || text.contains('\r')) {
                currentLine += text to ms
                val nextMs = entries.getOrNull(i + 1)?.takeIf { it.first.isEmpty() }?.second
                flushLine(nextMs)
            } else {
                currentLine += text to ms
            }
        }
        flushLine(null)
        return lines.takeIf { it.isNotBlank() }?.toString()
    }

    /** Reads one null-terminated string, returning it and where the next field starts. */
    private fun readEncoded(bytes: ByteArray, from: Int, encoding: Int): Pair<String, Int> {
        val wide = encoding == 1 || encoding == 2
        var i = from.coerceAtLeast(0)
        if (wide) {
            while (i + 1 < bytes.size) {
                if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) break
                i += 2
            }
            val text = decodeText(bytes, from, (i - from).coerceAtLeast(0), encoding)
            return text to (i + 2).coerceAtMost(bytes.size)
        }
        while (i < bytes.size && bytes[i] != 0.toByte()) i++
        val text = decodeText(bytes, from, (i - from).coerceAtLeast(0), encoding)
        return text to (i + 1).coerceAtMost(bytes.size)
    }

    private fun decodeText(bytes: ByteArray, from: Int, length: Int, encoding: Int): String {
        if (length <= 0 || from < 0 || from + length > bytes.size) return ""
        val charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(bytes, from, length, charset).trimEnd('\u0000')
    }

    /** Turns `FF 00` back into `FF`, which is all ID3v2 unsynchronisation does. */
    private fun deUnsynchronise(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            out.write(bytes[i].toInt())
            if (bytes[i] == 0xFF.toByte() && i + 1 < bytes.size && bytes[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }

    private fun ByteArray.isId3(from: Int = 0): Boolean =
        from + 3 <= size && this[from] == 'I'.code.toByte() &&
            this[from + 1] == 'D'.code.toByte() && this[from + 2] == '3'.code.toByte()

    /** A sync-safe 28-bit integer: four bytes, seven bits each. */
    private fun syncSafe(bytes: ByteArray, at: Int): Int? {
        if (at < 0 || at + 4 > bytes.size) return null
        var value = 0
        for (i in 0 until 4) {
            val b = bytes[at + i].toInt() and 0xFF
            if (b and 0x80 != 0) return null
            value = (value shl 7) or b
        }
        return value
    }

    // ---- Ogg Vorbis / Opus --------------------------------------------------

    /**
     * The `LYRICS` comment from an Ogg stream's comment header.
     *
     * Ogg pages are walked and their packets collected: a Vorbis comment header
     * starts with `\x03vorbis`, an Opus one with `OpusTags`, and both then hold
     * the same little-endian vendor/count/entry layout as FLAC's
     * `VORBIS_COMMENT` block — so [vorbisComment] reads either.
     */
    private fun ogg(bytes: ByteArray): String? {
        var pos = 0
        while (pos + 27 <= bytes.size) {
            if (!bytes.startsWith(OGG_MAGIC, pos)) break
            val segments = bytes[pos + 26].toInt() and 0xFF
            val tableAt = pos + 27
            if (tableAt + segments > bytes.size) break
            var bodyLen = 0
            for (i in 0 until segments) bodyLen += bytes[tableAt + i].toInt() and 0xFF
            val bodyAt = tableAt + segments
            if (bodyAt + bodyLen > bytes.size) break
            val packet = bytes.copyOfRange(bodyAt, bodyAt + bodyLen)
            val lyrics = when {
                packet.startsWith(VORBIS_COMMENT_MAGIC) -> vorbisComment(packet, 7, packet.size)
                packet.startsWith(OPUS_TAGS_MAGIC) -> vorbisComment(packet, 8, packet.size)
                else -> null
            }
            if (lyrics != null) return lyrics
            pos = bodyAt + bodyLen
        }
        return null
    }

    // ---- RIFF / WAV ---------------------------------------------------------

    /**
     * The ID3 tag inside a WAV's `id3 ` chunk.
     *
     * A WAV's chunks are length-prefixed and word-aligned, so the tag is found
     * by walking them rather than by scanning — an `id3 ` byte sequence inside
     * the audio data is not a tag.
     */
    private fun riff(bytes: ByteArray): String? {
        if (bytes.size < 12 || !bytes.startsWith(RIFF_MAGIC)) return null
        if (String(bytes, 8, 4, Charsets.ISO_8859_1) != "WAVE") return null
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.ISO_8859_1)
            val size = readLE32(bytes, pos + 4)
            val start = pos + 8
            if (size < 0 || start + size > bytes.size) return null
            if (id.equals("id3 ", ignoreCase = true) || id.equals("ID3 ", ignoreCase = true)) {
                return id3(bytes, start)
            }
            pos = start + size + (size and 1)
        }
        return null
    }

    // ---- MP4 / M4A ----------------------------------------------------------

    /**
     * The `©lyr` atom's text, or freeform items (`BITCHORD_LYRICS`, `SYNCEDLYRICS`, `LYRICS`).
     *
     * Both live under `moov/udta/meta/ilst`, and the search is scoped to `moov`.
     */
    private fun mp4(bytes: ByteArray): String? {
        val moov = topLevelBox(bytes, "moov") ?: return null
        val end = moov.last + 1
        val word = ilstFreeform(bytes, moov.first, end, WORD_LYRICS_FIELD)
        val synced = ilstFreeform(bytes, moov.first, end, "SYNCEDLYRICS")
        val lyrics = ilstFreeform(bytes, moov.first, end, "LYRICS")
        val standard = ilstStandard(bytes, moov.first, end)
        return pickBest(word, synced, lyrics, standard)
    }

    /**
     * The bounds of a top-level box, without walking into it.
     *
     * `moov` is not required to come before the audio — a file written without
     * the faststart pass puts it after `mdat` — so this steps box to box rather
     * than assuming a position.
     */
    private fun topLevelBox(bytes: ByteArray, type: String): IntRange? {
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val declared = readU32(bytes, pos)
            var headerLen = 8
            var size = declared
            if (declared == 1L) {
                if (pos + 16 > bytes.size) return null
                size = readU64(bytes, pos + 8)
                headerLen = 16
            } else if (declared == 0L) {
                size = (bytes.size - pos).toLong()
            }
            if (size < headerLen || size > Int.MAX_VALUE) return null
            val end = (pos + size).toInt().coerceAtMost(bytes.size)
            if (String(bytes, pos + 4, 4, Charsets.ISO_8859_1) == type) return pos until end
            pos += size.toInt()
        }
        return null
    }

    private fun ilstFreeform(bytes: ByteArray, from: Int, endExclusive: Int, name: String): String? {
        val marker = name.toByteArray(Charsets.UTF_8)
        var at = from
        while (true) {
            val found = bytes.indexOf(marker, at, endExclusive) ?: return null
            val data = bytes.indexOf(DATA_ATOM, found, endExclusive) ?: return null
            dataText(bytes, data, endExclusive)?.let { return it }
            at = found + marker.size
        }
    }

    private fun ilstStandard(bytes: ByteArray, from: Int, endExclusive: Int): String? {
        var at = from
        while (true) {
            val found = bytes.indexOf(LYR_ATOM, at, endExclusive) ?: return null
            val data = bytes.indexOf(DATA_ATOM, found, endExclusive) ?: return null
            dataText(bytes, data, endExclusive)?.let { return it }
            at = found + LYR_ATOM.size
        }
    }

    /** An iTunes `data` box's payload as text. */
    private fun dataText(bytes: ByteArray, dataAt: Int, endExclusive: Int): String? {
        val start = dataAt - 4
        if (start < 0 || dataAt + 12 > endExclusive) return null
        val size = readU32(bytes, start).toInt()
        if (size <= 16 || start + size > endExclusive) return null
        if (readU32(bytes, dataAt + 4).toInt() != 1) return null
        return String(bytes, dataAt + 12, start + size - (dataAt + 12), Charsets.UTF_8)
    }

    // ---- FLAC ---------------------------------------------------------------

    /**
     * The `LYRICS` (or [WORD_LYRICS_FIELD] / `SYNCEDLYRICS`) comment out of the
     * `VORBIS_COMMENT` block.
     *
     * Every length in the block is **little-endian** — it reuses Ogg Vorbis'
     * layout, which is the one part of FLAC that isn't big-endian.
     */
    private fun flac(bytes: ByteArray): String? {
        var pos = FLAC_MAGIC.size
        while (pos + 4 <= bytes.size) {
            val flags = bytes[pos].toInt() and 0xFF
            val length = ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            val start = pos + 4
            if (start + length > bytes.size) return null
            if (flags and 0x7F == FLAC_VORBIS_COMMENT) {
                return vorbisComment(bytes, start, start + length)
            }
            if (flags and 0x80 != 0) return null
            pos = start + length
        }
        return null
    }

    private fun vorbisComment(bytes: ByteArray, start: Int, end: Int): String? {
        var pos = start
        fun u32(): Int? {
            if (pos + 4 > end) return null
            val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 16) or ((bytes[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }
        val vendor = u32() ?: return null
        pos += vendor
        val count = u32() ?: return null
        val candidates = mutableListOf<String>()
        repeat(count.coerceAtMost(4_096)) {
            val length = u32() ?: return pickBest(*candidates.toTypedArray())
            if (length < 0 || pos + length > end) return pickBest(*candidates.toTypedArray())
            val entry = String(bytes, pos, length, Charsets.UTF_8)
            pos += length
            val name = entry.substringBefore('=').uppercase()
            val value = entry.substringAfter('=', "")
            if (value.isNotBlank()) {
                when (name) {
                    WORD_LYRICS_FIELD -> return value
                    "SYNCEDLYRICS", "LYRICS_SYNCED", "LYRICS", "UNSYNCEDLYRICS" -> {
                        candidates += value
                    }
                }
            }
        }
        return pickBest(*candidates.toTypedArray())
    }

    // ---- Matroska / WebM ----------------------------------------------------

    /**
     * The `LYRICS`, `SYNCEDLYRICS`, or [WORD_LYRICS_FIELD] SimpleTag's string.
     */
    private fun matroska(bytes: ByteArray): String? {
        val candidates = mutableListOf<String>()
        for (name in listOf(WORD_LYRICS_FIELD, "SYNCEDLYRICS", "LYRICS")) {
            val needle = name.toByteArray(Charsets.US_ASCII)
            var from = 0
            while (true) {
                val at = bytes.indexOf(needle, from, bytes.size) ?: break
                from = at + needle.size
                if (at < 3 || bytes[at - 3] != ID_TAGNAME[0] || bytes[at - 2] != ID_TAGNAME[1]) continue
                if ((bytes[at - 1].toInt() and 0x7F) != needle.size) continue
                val string = bytes.indexOf(ID_TAGSTRING, from, bytes.size) ?: continue
                val size = readVint(bytes, string + 2) ?: continue
                val valueAt = string + 2 + size.width
                if (size.value <= 0 || valueAt + size.value > bytes.size) continue
                val value = String(bytes, valueAt, size.value.toInt(), Charsets.UTF_8)
                if (value.isBlank()) continue
                if (name == WORD_LYRICS_FIELD) return value
                candidates += value
            }
        }
        return pickBest(*candidates.toTypedArray())
    }

    private class Vint(val value: Long, val width: Int)

    /**
     * An EBML variable-length integer: the highest set bit of the first byte
     * gives the width, and the bits after it are the value.
     */
    private fun readVint(bytes: ByteArray, offset: Int): Vint? {
        if (offset >= bytes.size) return null
        val first = bytes[offset].toInt() and 0xFF
        if (first == 0) return null
        var width = 1
        var mask = 0x80
        while (first and mask == 0) {
            mask = mask shr 1
            width++
        }
        if (offset + width > bytes.size) return null
        var value = (first and mask.inv() and 0xFF).toLong()
        for (i in 1 until width) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return Vint(value, width)
    }

    // ---- Bytes --------------------------------------------------------------

    /** Reads up to [max] bytes, which is all of a normal file and a prefix of a huge one. */
    private fun InputStream.readAtMost(max: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(max, 1 shl 16))
        val buffer = ByteArray(1 shl 16)
        var total = 0
        while (total < max) {
            val read = read(buffer, 0, minOf(buffer.size, max - total))
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private fun ByteArray.startsWith(prefix: ByteArray, from: Int = 0): Boolean {
        if (size - from < prefix.size) return false
        return prefix.indices.all { this[from + it] == prefix[it] }
    }

    /** `ftyp` at offset 4 is what says "this is an MP4" — there is no leading magic. */
    private fun ByteArray.isMp4(): Boolean =
        size > 12 && this[4] == 'f'.code.toByte() && this[5] == 't'.code.toByte() &&
            this[6] == 'y'.code.toByte() && this[7] == 'p'.code.toByte()

    private fun ByteArray.indexOf(needle: ByteArray, from: Int, until: Int): Int? {
        if (needle.isEmpty()) return null
        val last = minOf(until, size) - needle.size
        var i = from.coerceAtLeast(0)
        outer@ while (i <= last) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return null
    }

    private fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    /** Little-endian, for RIFF chunk lengths and Vorbis comments. */
    private fun readLE32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun readU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private const val FLAC_VORBIS_COMMENT = 4
    private val FLAC_MAGIC = "fLaC".toByteArray(Charsets.US_ASCII)
    private val OGG_MAGIC = "OggS".toByteArray(Charsets.US_ASCII)
    private val RIFF_MAGIC = "RIFF".toByteArray(Charsets.US_ASCII)
    private val VORBIS_COMMENT_MAGIC = byteArrayOf(0x03) + "vorbis".toByteArray(Charsets.US_ASCII)
    private val OPUS_TAGS_MAGIC = "OpusTags".toByteArray(Charsets.US_ASCII)
    private val MATROSKA_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    private val DATA_ATOM = "data".toByteArray(Charsets.ISO_8859_1)
    private val LYR_ATOM = byteArrayOf(0xA9.toByte()) + "lyr".toByteArray(Charsets.ISO_8859_1)
    private val ID_TAGNAME = byteArrayOf(0x45, 0xA3.toByte())
    private val ID_TAGSTRING = byteArrayOf(0x44, 0x87.toByte())

    /**
     * Extensions this app treats as audio when deciding whether a folder holds
     * one track or an album. Only used to scope the folder-level `lyrics.lrc`.
     */
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "m4b", "alac", "aac", "flac", "ogg", "oga", "opus", "wav", "wave",
        "aif", "aiff", "wma", "webm", "mka", "mp4", "dsf", "dff", "ape", "wv", "3ga",
    )
}
