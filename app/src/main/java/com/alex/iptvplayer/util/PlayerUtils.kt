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
        // 1. Ultra-robuster HTTP DataSource mit langen Timeouts & Redirect Support (optimal für Schlafzimmer / 2.4GHz)
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("VLC/3.0.18 (Linux; Android 11; TV) ExoPlayerLib/2.18.2")
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)
            .setConnectTimeoutMs(40000)
            .setReadTimeoutMs(45000)
            .setDefaultRequestProperties(mapOf(
                "Connection" to "keep-alive",
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

        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory, extractorsFactory)

        // 3. Fallback auf Software-Decoding falls Hardware-Decoder im Schlafzimmer zickt
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        // 4. Intelligente Puffersteuerung (LoadControl) für WLAN-Schwankungen
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, 64 * 1024))
            .setBufferDurationsMs(
                /* minBufferMs = */ 45000,
                /* maxBufferMs = */ 90000,
                /* bufferForPlaybackMs = */ 1500, // Schneller Start
                /* bufferForPlaybackAfterRebufferMs = */ 3000
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
