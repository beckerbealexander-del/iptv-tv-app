package com.tivizone.player.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileReader
import java.io.FileWriter

class LiveTvCacheManager(private val context: Context) {

    private val gson = Gson()
    private val tag = "LiveTvCacheManager"

    private val bundledChannelsFile: File
        get() = File(context.filesDir, "cached_bundled_channels.json")

    private val rawCategoriesFile: File
        get() = File(context.filesDir, "cached_raw_categories.json")

    private val prefs = context.getSharedPreferences("livetv_cache_meta", Context.MODE_PRIVATE)

    fun saveBundledChannels(map: Map<String, List<MultiStreamChannel>>) {
        try {
            val tempFile = File(context.filesDir, "cached_bundled_channels.json.tmp")
            FileWriter(tempFile).use { writer ->
                gson.toJson(map, writer)
            }
            if (tempFile.exists()) {
                tempFile.renameTo(bundledChannelsFile)
            }
            prefs.edit().putLong("last_sync_timestamp", System.currentTimeMillis()).apply()
            Log.d(tag, "Bundled channels successfully saved to disk cache (${bundledChannelsFile.length() / 1024} KB)")
        } catch (e: Exception) {
            Log.e(tag, "Failed to save bundled channels to cache: ${e.message}")
        }
    }

    fun loadBundledChannels(): Map<String, List<MultiStreamChannel>>? {
        val file = bundledChannelsFile
        if (!file.exists() || file.length() == 0L) return null
        return try {
            FileReader(file).use { reader ->
                val type = object : TypeToken<Map<String, List<MultiStreamChannel>>>() {}.type
                gson.fromJson<Map<String, List<MultiStreamChannel>>>(reader, type)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to load bundled channels from cache: ${e.message}")
            null
        }
    }

    fun saveRawCategories(categories: List<Category>) {
        try {
            val tempFile = File(context.filesDir, "cached_raw_categories.json.tmp")
            FileWriter(tempFile).use { writer ->
                gson.toJson(categories, writer)
            }
            if (tempFile.exists()) {
                tempFile.renameTo(rawCategoriesFile)
            }
            Log.d(tag, "Raw categories saved to disk cache (${categories.size} categories)")
        } catch (e: Exception) {
            Log.e(tag, "Failed to save raw categories to cache: ${e.message}")
        }
    }

    fun loadRawCategories(): List<Category>? {
        val file = rawCategoriesFile
        if (!file.exists() || file.length() == 0L) return null
        return try {
            FileReader(file).use { reader ->
                val type = object : TypeToken<List<Category>>() {}.type
                gson.fromJson<List<Category>>(reader, type)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to load raw categories from cache: ${e.message}")
            null
        }
    }

    fun getCacheAgeMs(): Long {
        val lastSync = prefs.getLong("last_sync_timestamp", 0L)
        if (lastSync == 0L) return Long.MAX_VALUE
        return System.currentTimeMillis() - lastSync
    }

    fun isCacheValid(maxAgeHours: Long = 12): Boolean {
        val file = bundledChannelsFile
        if (!file.exists() || file.length() == 0L) return false
        val maxAgeMs = maxAgeHours * 3600 * 1000L
        return getCacheAgeMs() < maxAgeMs
    }

    fun clearCache() {
        try {
            bundledChannelsFile.delete()
            rawCategoriesFile.delete()
            prefs.edit().clear().apply()
        } catch (e: Exception) {
            Log.e(tag, "Failed to clear cache: ${e.message}")
        }
    }
}
