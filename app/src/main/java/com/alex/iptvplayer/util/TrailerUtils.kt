package com.alex.iptvplayer.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.net.URLEncoder

object TrailerUtils {

    fun openTrailer(context: Context, rawTrailer: String?, rawTitle: String) {
        try {
            val cleanTitle = rawTitle
                .replace(Regex("^(DE\\s*-\\s*|DE:\\s*|DE\\|\\s*|DE\\s+|4K-DE\\s*|4K\\s+DE\\s*|4K-RU\\s*|RU\\s*-\\s*|RU:\\s*|RU\\|\\s*)", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\b(4K|FHD|HD|HEVC|UHD|H\\.265|H\\.264|1080p|2160p|720p)\\b", RegexOption.IGNORE_CASE), "")
                .trim()

            val trailerUrl: String = when {
                !rawTrailer.isNullOrBlank() && rawTrailer.startsWith("http", ignoreCase = true) -> {
                    rawTrailer.trim()
                }
                !rawTrailer.isNullOrBlank() -> {
                    val id = rawTrailer.trim()
                    "https://www.youtube.com/watch?v=$id"
                }
                else -> {
                    val query = URLEncoder.encode("$cleanTitle Trailer Deutsch German", "UTF-8")
                    "https://www.youtube.com/results?search_query=$query"
                }
            }

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(trailerUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Konnte YouTube-Trailer nicht öffnen", Toast.LENGTH_SHORT).show()
        }
    }
}