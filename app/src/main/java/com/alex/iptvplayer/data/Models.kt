package com.alex.iptvplayer.data

import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class Category(
    @SerializedName("category_id") val id: String,
    @SerializedName("category_name") val name: String,
    @SerializedName("parent_id") val parentId: Int = 0
) : Serializable

data class LiveStream(
    @SerializedName("num") val num: Int? = null,
    @SerializedName("name") val name: String,
    @SerializedName("stream_type") val streamType: String? = null,
    @SerializedName("stream_id") val streamId: Int,
    @SerializedName("stream_icon") val streamIcon: String? = null,
    @SerializedName("epg_channel_id") val epgChannelId: String? = null,
    @SerializedName("category_id") val categoryId: String? = null
) : Serializable

data class VodStream(
    @SerializedName("num") val num: Int? = null,
    @SerializedName("name") val name: String,
    @SerializedName("stream_type") val streamType: String? = null,
    @SerializedName("stream_id") val streamId: Int,
    @SerializedName("stream_icon") val streamIcon: String? = null,
    @SerializedName("rating") val rating: String? = null,
    @SerializedName("category_id") val categoryId: String? = null,
    @SerializedName("container_extension") val containerExtension: String? = "mp4"
) : Serializable

data class SeriesItem(
    @SerializedName("num") val num: Int? = null,
    @SerializedName("name") val name: String,
    @SerializedName("series_id") val seriesId: Int,
    @SerializedName("cover") val cover: String? = null,
    @SerializedName("plot") val plot: String? = null,
    @SerializedName("rating") val rating: String? = null,
    @SerializedName("category_id") val categoryId: String? = null
) : Serializable

data class SeriesInfoResponse(
    @SerializedName("seasons") val seasons: List<SeasonItem>? = null,
    @SerializedName("episodes") val episodes: Map<String, List<EpisodeItem>>? = null
) : Serializable

data class SeasonItem(
    @SerializedName("season_number") val seasonNumber: Int,
    @SerializedName("name") val name: String? = null,
    @SerializedName("episode_count") val episodeCount: Int = 0
) : Serializable

data class EpisodeItem(
    @SerializedName("id") val id: String,
    @SerializedName("episode_num") val episodeNum: Int,
    @SerializedName("title") val title: String,
    @SerializedName("container_extension") val containerExtension: String? = "mp4",
    @SerializedName("season") val season: Int = 1
) : Serializable
