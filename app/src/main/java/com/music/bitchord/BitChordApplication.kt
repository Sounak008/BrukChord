package com.music.bitchord

import android.app.Application
import androidx.annotation.OptIn
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.DebugLog
import com.music.bitchord.data.lyrics.LyricsTranslation
import androidx.appcompat.app.AppCompatDelegate
import com.music.bitchord.ui.player.AndroidPlayerHost
import com.music.bitchord.ui.player.PlayerPlatform
import com.music.bitchord.data.canvas.CanvasCache
import com.music.bitchord.data.smb.SmbCoverFetcher
import com.music.bitchord.data.webdav.WebDavCoilAuth
import com.music.bitchord.data.canvas.SpotifyToken
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.LastPlayed
import com.music.bitchord.playback.BoundedContextPersistence
import com.music.bitchord.playback.OriginalVersion
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.AndroidStreamHooks
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.scrobbling.LastFM
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SearchHistory
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.stats.ArtistFacts
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.PlaylistSync
import androidx.work.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.os.SystemClock
import kotlin.concurrent.thread

class BitChordApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    /** Asked for on first use rather than at launch; see [PlaylistSync.init]. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    @OptIn(UnstableApi::class)
    @kotlin.OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate() {
        val tAppStart = SystemClock.elapsedRealtime()
        android.util.Log.i("BitChordStartup", "Application.onCreate start")
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                val log = sw.toString()
                android.util.Log.e("BitChordCrash", log)
                val crashFile = java.io.File(getExternalFilesDir(null), "crash.txt")
                crashFile.writeText(log)
                val pubDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (pubDir.exists()) {
                    java.io.File(pubDir, "brukchord_crash.txt").writeText(log)
                }
            } catch (_: Throwable) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
        // The player is drawn by the shared UI module; this is what it reads
        // underneath — settings, the Canvas decoder, outputs, the party.
        AndroidStreamHooks.installEarly()
        PlayerPlatform.install(AndroidPlayerHost(this))
        com.music.bitchord.ui.AppUi.install(com.music.bitchord.ui.AndroidAppUiHost)
        // The shared data layer (lyrics, the YouTube Music client) logs to
        // logcat on debug builds only, as the app's own DebugLog always has.
        if (BuildConfig.DEBUG) {
            DebugLog.sink = DebugLog.Sink { level, tag, message, error ->
                when (level) {
                    'D' -> android.util.Log.d(tag, message, error)
                    'I' -> android.util.Log.i(tag, message, error)
                    'W' -> android.util.Log.w(tag, message, error)
                    else -> android.util.Log.e(tag, message, error)
                }
            }
        } else {
            // R8 strips android.util.Log from a release build (proguard-rules.pro);
            // this stops Media3 composing the lines it would have handed it.
            androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
            // java.util.logging reaches logcat without android.util.Log, through
            // the handler Android installs on its root logger — the Discord
            // gateway's connection chatter went out that way.
            java.util.logging.LogManager.getLogManager().reset()
            java.util.logging.Logger.getLogger("").level = java.util.logging.Level.OFF
        }
        // Compose re-votes the window's frame rate from dispatchDraw on every
        // frame (API 35+), and Samsung's View logs each vote together with a
        // stack walk of its caller: measured on an S22, 8,000 lines in two
        // minutes, two stack traces built on the main thread per frame. The app
        // asks for its refresh rate itself (MainActivity.applyPerformanceMode),
        // so those votes buy nothing — and they kept resetting that request.
        ComposeUiFlags.isAdaptiveRefreshRateEnabled = false
        // The Spotify library and import live in the shared module; the tokens are the WebView's.
        SpotifyLibrary.auth = SpotifyLibrary.Auth {
            SpotifyToken.accessToken()?.let { SpotifyLibrary.Tokens(it, SpotifyToken.clientToken()) }
        }
        // The per-app language picker, which YouTube Music's `hl` follows.
        // Full tag (zh-Hant / zh-Hans preserved); Innertube maps to hl/gl.
        Innertube.appLanguage = { AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag() }
        migrateScriptOnlyAppLocale()
        LyricsTranslation.cacheDir = cacheDir
        // PlaybackService shares this process, so seeding the cookie here means
        // stream resolution is authenticated from the first play onwards.
        authStore = AuthStore(this)
        // Heavy subsystems (SourceRegistry, AudioCache, CanvasCache, etc.) are initialized
        // in a background thread without blocking Application.onCreate so cold boot is instant.
        thread(name = "startup-init", priority = Thread.NORM_PRIORITY) {
            SourceRegistry.init(this@BitChordApplication)
            AndroidStreamHooks.initInnerTubeX(this@BitChordApplication)
            CanvasCache.init(this@BitChordApplication)
            AudioCache.init(this@BitChordApplication)
            Downloads.init(this@BitChordApplication)
            ListeningStats.init(this@BitChordApplication)
            ArtistFacts.init(this@BitChordApplication)
            initLastfm()
            LastPlayed.init(this@BitChordApplication)
            com.music.bitchord.data.personalmix.PersonalMixStore.init(this@BitChordApplication)
            BoundedContextPersistence.init(this@BitChordApplication)
            com.music.bitchord.playback.PartyPersonalQueueStash.init(this@BitChordApplication)
            OriginalVersion.init(this@BitChordApplication)
            com.music.bitchord.playback.audio.LosslessOutput.init(this@BitChordApplication)
        }
        // Fast settings and UI essentials on main thread
        val restoredSession = authStore.activeSession
        if (restoredSession != null && authStore.activeAccountId == null) {
            authStore.select(restoredSession.accountId, restoredSession.activeProfileId)
        }
        authStore.cookie = restoredSession?.cookie
        Innertube.cookie = restoredSession?.cookie
        if (restoredSession != null) {
            restoredSession.profiles.firstOrNull { it.profileId == restoredSession.activeProfileId }
                ?.let { Innertube.selectChannel(it.pageId, it.dataSyncId, it.authUser) }
            CoroutineScope(Dispatchers.IO).launch { Innertube.ensureSessionScope() }
        }
        AppSettings.init(this, authStore)
        com.music.bitchord.data.home.HomeFeedCache.init(this)
        ListenTogether.init(this)
        SearchHistory.init(this)
        PlaylistSync.init(this)
        SpotifyToken.init(this)
        if (AppSettings.consumeVersionUpdate(BuildConfig.VERSION_CODE)) {
            CoroutineScope(Dispatchers.IO).launch {
                SingletonImageLoader.get(this@BitChordApplication).let { loader ->
                    loader.memoryCache?.clear()
                    loader.diskCache?.clear()
                }
            }
        }
        com.music.bitchord.data.presence.AndroidPresence.install(this)
        val tAppEnd = SystemClock.elapsedRealtime()
        android.util.Log.i("BitChordStartup", "Application.onCreate end: elapsed=" + (tAppEnd - tAppStart) + "ms")
    }

    /**
     * Coil's own transport, separate from [Http][com.music.bitchord.data.Http]'s
     * playback-tuned client.
     *
     * Wikimedia (which serves signature art) answers generic clients with
     * HTTP 403 under its User-Agent policy, so every image request carries an
     * identifying agent. Anything else falls through to Coil's own fetchers.
     */
    private val coilHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header(
                            "User-Agent",
                            "BrukChord/${BuildConfig.VERSION_NAME} (Android; +https://github.com/kushagrasinghx/BitChord)",
                        )
                        .build(),
                )
            }
            .build()
    }

    /**
     * Artwork loading, which was previously left entirely on Coil's defaults.
     *
     * The defaults aren't unreasonable, but the disk cache is sized at 2% of
     * free space — which on a full phone is the 10MB floor, a few screens of
     * covers, and covers are exactly the thing worth still having tomorrow.
     * Naming a directory alongside it keeps that cache somewhere identifiable
     * rather than in the process's temp dir.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            // Covers on the WebDAV server need the credential or every one
            // of them 401s — which reads as "this track has no artwork".
            // Coil's own transport never sees Http.client's interceptor, so
            // the header is attached per request instead. See WebDavCoilAuth.
            .components {
                add(WebDavCoilAuth())
                // Covers filed on the SMB share; anything else falls
                // through to Coil's own fetchers. See SmbCoverFetcher.
                add(SmbCoverFetcher.Factory())
                // Artist signatures are usually SVG, which Coil can't
                // decode without this. See ArtistSignatureRepository.
                add(SvgDecoder.Factory())
                // Wikimedia 403s Coil's default client; see [coilHttpClient].
                add(OkHttpNetworkFetcherFactory(callFactory = { coilHttpClient }))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            // Avoid 200ms crossfade animation storms during list flings
            .crossfade(false)
            .allowHardware(!emulatedGpu)
            .build()

    /**
     * Whether graphics here go through an emulator's GL translator rather than
     * a real GPU driver — see [newImageLoader].
     *
     * On BlueStacks (Android 9) uploading a hardware bitmap aborts the whole
     * process: the renderer turns a cover into a texture, the translator
     * answers GL_INVALID_ENUM, and hwui's own error check kills the app —
     * `glGenTextures error! GL_INVALID_ENUM`, from RenderThread, each time the
     * app came back to the foreground with a 640px cover to decode. Covers in
     * ordinary bitmaps never take that path.
     *
     * Asked of the driver on disk rather than of build properties, because
     * emulators spoof those — BlueStacks reports itself as a Qualcomm Galaxy
     * S22. `libEGL_emulation` is the AOSP emulator's translator, which no
     * phone ships.
     */
    private val emulatedGpu: Boolean by lazy {
        listOf("/vendor/lib64", "/vendor/lib", "/system/lib64", "/system/lib")
            .any { java.io.File("$it/egl/libEGL_emulation.so").exists() }
    }

    private fun initLastfm() {
        val sessionKey = AppSettings.lastfmSessionKey.value
        if (sessionKey.isBlank()) return
        val endpoint = AppSettings.lastfmEndpoint.value.ifBlank { LastFM.DEFAULT_API_ENDPOINT }
        val apiKey = AppSettings.lastfmApiKey.value.trim()
        val secret = AppSettings.lastfmSecret.value.trim()
        if (apiKey.isBlank() || secret.isBlank()) return
        LastFM.configure(
            endpoint = endpoint,
            apiKey = apiKey,
            secret = secret,
            sessionKey = sessionKey,
        )
    }

    companion object {
        lateinit var authStore: AuthStore
            private set

        /**
         * One-time move from region-less script tags to regional ones.
         *
         * Early builds of the Simplified/Traditional picker stored `zh-Hant` /
         * `zh-Hans`. Android Views resolve those (script qualifiers), but the
         * shared Compose resources only match language + region — so Settings
         * read Traditional while the player read Simplified. Regional tags
         * match on both systems. Bare `zh` is left alone: it historically
         * meant Simplified.
         */
        private fun migrateScriptOnlyAppLocale() {
            val current = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
            val replacement = when (current) {
                "zh-Hant" -> "zh-TW"
                "zh-Hans" -> "zh-CN"
                else -> return
            }
            AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(replacement),
            )
        }
    }
}
