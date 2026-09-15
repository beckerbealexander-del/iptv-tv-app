package com.alex.iptvplayer.data

import android.content.Context
import android.content.SharedPreferences

object QualityPreferenceManager {

    private const val PREFS_NAME = "tivizone_quality_prefs"
    private const val KEY_PREFIX_SOURCE = "preferred_source_"
    private const val KEY_PREFIX_QUALITY = "quality_info_"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getChannelKey(channelName: String): String {
        return MultiStreamManager.normalizeKey(channelName)
    }

    fun getPreferredStreamId(context: Context, channelName: String): Int? {
        val key = getChannelKey(channelName)
        val id = getPrefs(context).getInt("$KEY_PREFIX_SOURCE$key", -1)
        return if (id > 0) id else null
    }

    fun savePreferredStream(context: Context, channelName: String, streamId: Int, qualityInfo: String? = null) {
        if (streamId <= 0) return
        val key = getChannelKey(channelName)
        val editor = getPrefs(context).edit().putInt("$KEY_PREFIX_SOURCE$key", streamId)
        if (!qualityInfo.isNullOrEmpty()) {
            editor.putString("$KEY_PREFIX_QUALITY$key", qualityInfo)
        }
        editor.apply()
    }

    fun getPreferredQualityInfo(context: Context, channelName: String): String? {
        val key = getChannelKey(channelName)
        return getPrefs(context).getString("$KEY_PREFIX_QUALITY$key", null)
    }

    fun hasPreference(context: Context, channelName: String): Boolean {
        return getPreferredStreamId(context, channelName) != null
    }

    fun clearPreference(context: Context, channelName: String) {
        val key = getChannelKey(channelName)
        getPrefs(context).edit()
            .remove("$KEY_PREFIX_SOURCE$key")
            .remove("$KEY_PREFIX_QUALITY$key")
            .apply()
    }
}
