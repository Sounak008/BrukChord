package com.music.bitchord.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.download.Downloads

/**
 * Plays a downloaded file in place of the stream its queue entry names.
 *
 * The ordinary path already answers "is this song on the device?" when the
 * item is built — see [Song.toMediaItem] — and for everything queued after a
 * download finished that is enough. It is not enough for the item that was
 * already in the queue, or already current, when the download completed: its
 * URI is a `bitchord://watch?v=` and stays one. The player then resolves a
 * stream and reads it over the network for a song whose bytes are sitting on
 * the device, which is both the data cost a download was meant to avoid and
 * the one thing this class exists to stop.
 *
 * The check is put here, under the cache and around the datasource chain,
 * rather than in [PlaybackService.streamResolver], for the reason
 * [Song.toMediaItem] documents: by the time a resolver sees a `DataSpec` the
 * data source has already been chosen from the URI's scheme, and the resolver
 * sits on the HTTP half of the chain. Rewriting the URI before the chain is
 * asked what scheme it is means the file goes to `FileDataSource` or
 * `ContentDataSource` and no request is made at all.
 *
 * Left alone: a quality upgrade's rendition (it has its own cache entry and a
 * different stream behind it, and [Downloads] treats a download as final
 * anyway), the watch's capped copy, and an offline HLS/DASH package, whose
 * playlist has to be opened as a playlist rather than as a progressive file —
 * the item-level path already knows how to do that one.
 */
internal class LocalFirstDataSource(private val upstream: DataSource) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val local = localUriOf(dataSpec.uri)
        if (local == null) return upstream.open(dataSpec)
        Log.d(TAG, "playing the downloaded copy of ${dataSpec.uri} from $local")
        return upstream.open(dataSpec.buildUpon().setUri(local).build())
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        upstream.close()
    }

    /** The local copy to use instead of [uri], or null to stream [uri] as asked. */
    private fun localUriOf(uri: Uri): Uri? {
        if (uri.scheme != "bitchord" || uri.authority != "watch") return null
        // A rendition is a specific stream chosen by [QualityUpgrade], not the
        // track the download record describes.
        if (uri.getQueryParameter(QualityUpgrade.MARKER) != null) return null
        // The watch's capped copy is the same track at a lower ceiling, so the
        // download is still the right file — it is not excluded.
        val id = uri.getQueryParameter("v") ?: return null
        val saved = Downloads.localPlaybackUri(id) ?: return null
        return saved
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = LocalFirstDataSource(upstream.createDataSource())
    }

    private companion object {
        private const val TAG = "BitChord"
    }
}
