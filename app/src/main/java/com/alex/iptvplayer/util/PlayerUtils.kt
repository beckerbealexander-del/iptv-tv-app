package com.alex.iptvplayer.util

import android.content.Context
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

object PlayerUtils {

    fun createExoPlayer(context: Context): ExoPlayer {
        // 1. Robuster HTTP DataSource mit langen Timeouts & Redirect Support (ideal für 2.4GHz & weite Distanzen)
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("IPTVSmartersPro/1.0.0 (Linux; Android 11; TV)")
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)
            .setConnectTimeoutMs(30000)
            .setReadTimeoutMs(35000)

        // 2. TS Stream Extractor Optimierung
        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)

        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory, extractorsFactory)

        // 3. Hardware Rendering bevorzugen
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        // 4. Intelligente Puffersteuerung (LoadControl) für schwache / weiter entfernte WLAN-Signale
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, 64 * 1024))
            .setBufferDurationsMs(
                /* minBufferMs = */ 45000,   // Hält mindestens 45 Sek. Puffer
                /* maxBufferMs = */ 90000,   // Puffert bis zu 90 Sek. vor
                /* bufferForPlaybackMs = */ 2000, // Spielt nach 2 Sek. sofort flüssig an
                /* bufferForPlaybackAfterRebufferMs = */ 4000 // Rebuffer Schutz
            )
            .setBackBuffer(
                /* backBufferDurationMs = */ 30000,
                /* retainBackBufferFromKeyframe = */ true
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setSeekForwardIncrementMs(10000)
            .setSeekBackIncrementMs(10000)
            .build()
    }
}
