package com.alex.iptvplayer.util

import android.content.Context
import androidx.media3.common.C
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object PlayerUtils {

    // Eigener OkHttpClient für Video-Streams mit aggressivem Verbindungs-Management
    val playerOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(4, 15, TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            .build()
    }

    fun createExoPlayer(context: Context, isLive: Boolean = false): ExoPlayer {
        // 1. OkHttpDataSource für präzises Schließen aller TCP-Sockets
        val okHttpDataSourceFactory = OkHttpDataSource.Factory(playerOkHttpClient)
            .setUserAgent("VLC/3.0.18 (Linux; Android 11; TV) ExoPlayerLib/2.18.2")
            .setDefaultRequestProperties(mapOf(
                "Connection" to "close", // Schneller Verbindungsabbau ohne hängende Keep-Alive Sockets
                "Accept" to "*/*"
            ))

        // 2. All-Format TS / MKV / MP4 / HLS Stream Extractor Optimierung
        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM
            )
            .setConstantBitrateSeekingEnabled(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(okHttpDataSourceFactory, extractorsFactory)

        // 3. MediaCodec-Erweiterungen mit Fallback für maximale Kompatibilität
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setTunnelingEnabled(false) // Tunneling deaktiviert: Führt bei 5.1 Mehrkanalton auf Android TV (z.B. Chromecast HD) zu AudioTrack-Init-Fehlern
                .build()
        }

        // 4. Maßgeschneiderte Puffersteuerung: Live-TV (2.5s-5s) vs. VOD
        val loadControl = if (isLive) {
            DefaultLoadControl.Builder()
                .setAllocator(DefaultAllocator(true, 32 * 1024))
                .setBufferDurationsMs(
                    /* minBufferMs = */ 2500,
                    /* maxBufferMs = */ 5000,
                    /* bufferForPlaybackMs = */ 1500,
                    /* bufferForPlaybackAfterRebufferMs = */ 2000
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setAllocator(DefaultAllocator(true, 64 * 1024))
                .setBufferDurationsMs(
                    /* minBufferMs = */ 15000,
                    /* maxBufferMs = */ 45000,
                    /* bufferForPlaybackMs = */ 1500,
                    /* bufferForPlaybackAfterRebufferMs = */ 2500
                )
                .setBackBuffer(
                    /* backBufferDurationMs = */ 30000,
                    /* retainBackBufferFromKeyframe = */ true
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setSeekForwardIncrementMs(10000)
            .setSeekBackIncrementMs(10000)
            .build()

        // 5. Nahtloses Frame-Rate-Matching: 50Hz PAL TV ohne 60Hz-Judder und ohne HDMI-Blackscreens
        player.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS

        return player
    }

    fun cancelPendingMediaRequests() {
        try {
            playerOkHttpClient.dispatcher.cancelAll()
        } catch (e: Exception) {
            // ignore
        }
    }

    // Aggressives Verbindungs-Tear-Down: Beendet Sockets sofort hart
    fun releaseStreamConnections(player: ExoPlayer?) {
        try {
            AppLogger.logLifecycle("PlayerUtils", "releaseStreamConnections: Stopping player & terminating all HTTP sockets")
            player?.stop()
            player?.clearMediaItems()
            playerOkHttpClient.dispatcher.cancelAll()
            playerOkHttpClient.connectionPool.evictAll()
        } catch (e: Exception) {
            AppLogger.e("PlayerUtils", "Error releasing stream connections: ${e.message}", e)
        }
    }
}

object SeriesUtils {

    private val SEASON_EPISODE_PATTERN = Regex("""(\s+-\s+S\d+|\s+S\d+E\d+|\s*\[S\d+|\s+Staffel\s+\d+).*""", RegexOption.IGNORE_CASE)
    private val LANG_PREFIX_PATTERN = Regex("""^(4K-)?(DE|AT|CH|RUS|RU|EN|TR|FR|IT|ES)\s*[-:|]\s*""", RegexOption.IGNORE_CASE)
    private val YEAR_PATTERN = Regex("""\s*\(\d{4}\)""")

    fun cleanSeriesTitle(raw: String): String {
        if (raw.isBlank()) return ""
        var t = raw.trim()
        t = t.replace(SEASON_EPISODE_PATTERN, "").trim()
        t = t.trimEnd('-', ':', '|', ' ')
        return t
    }

    fun stripLanguagePrefix(name: String): String {
        return name.replace(LANG_PREFIX_PATTERN, "")
            .replace(YEAR_PATTERN, "")
            .trim()
    }

    fun findMatchingSeries(searchTitle: String, allSeries: List<com.alex.iptvplayer.data.SeriesItem>): com.alex.iptvplayer.data.SeriesItem? {
        if (allSeries.isEmpty() || searchTitle.isBlank()) return null
        val clean = cleanSeriesTitle(searchTitle)
        if (clean.isBlank()) return null

        // 1. Exakter Namensabgleich (case-insensitive)
        val exact = allSeries.firstOrNull { it.name.trim().equals(clean, ignoreCase = true) }
        if (exact != null) return exact

        // 2. Ohne Sprachpräfix (z. B. "Shameless - Nicht ganz nüchtern (US)")
        val cleanCore = stripLanguagePrefix(clean)
        if (cleanCore.isNotEmpty()) {
            val coreMatch = allSeries.firstOrNull {
                stripLanguagePrefix(it.name).equals(cleanCore, ignoreCase = true)
            }
            if (coreMatch != null) return coreMatch
        }

        // 3. Substring-Suche (entweder Playlist-Name in gesuchtem Titel oder umgekehrt)
        val subMatch = allSeries.firstOrNull {
            it.name.contains(clean, ignoreCase = true) || clean.contains(it.name.trim(), ignoreCase = true)
        }
        if (subMatch != null) return subMatch

        // 4. Kern-Substring (z. B. "Shameless")
        if (cleanCore.length >= 3) {
            val coreSubMatch = allSeries.firstOrNull {
                val itCore = stripLanguagePrefix(it.name)
                itCore.contains(cleanCore, ignoreCase = true) || cleanCore.contains(itCore, ignoreCase = true)
            }
            if (coreSubMatch != null) return coreSubMatch
        }

        // 5. Alphanumerischer Normalisierungs-Vergleich
        val normClean = cleanCore.filter { it.isLetterOrDigit() }.lowercase()
        if (normClean.length >= 3) {
            val normMatch = allSeries.firstOrNull {
                val normIt = stripLanguagePrefix(it.name).filter { c -> c.isLetterOrDigit() }.lowercase()
                normIt == normClean || normIt.contains(normClean) || normClean.contains(normIt)
            }
            if (normMatch != null) return normMatch
        }

        return null
    }
}

