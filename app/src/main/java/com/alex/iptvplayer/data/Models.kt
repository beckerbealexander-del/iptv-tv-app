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

data class StreamSource(
    val streamId: Int,
    val name: String,
    val label: String,
    val score: Int,
    val subcategory: String,
    val epgChannelId: String? = null
) : Serializable

data class MultiStreamChannel(
    val cleanName: String,
    val originalName: String,
    val categoryId: String,
    val categoryName: String,
    val icon: String? = null,
    val epgId: String? = null,
    val epgStreamId: Int? = null,
    val sources: List<StreamSource> = emptyList()
) : Serializable {
    val primarySource: StreamSource?
        get() = sources.firstOrNull()
}


data class EpgResponse(
    @SerializedName("epg_listings") val listings: List<RawEpgItem>? = null
) : Serializable

data class RawEpgItem(
    @SerializedName("id") val id: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("start") val start: String? = null,
    @SerializedName("end") val end: String? = null,
    @SerializedName("now_playing") val nowPlaying: Int? = 0
) : Serializable

data class EpgProgram(
    val title: String,
    val description: String,
    val start: String,
    val end: String,
    val isNowPlaying: Boolean
) : Serializable

data class VodStream(
    @SerializedName("num") val num: Int? = null,
    @SerializedName("name") val name: String,
    @SerializedName("stream_type") val streamType: String? = null,
    @SerializedName("stream_id") val streamId: Int,
    @SerializedName("stream_icon") val streamIcon: String? = null,
    @SerializedName("rating") val rating: String? = null,
    @SerializedName("category_id") val categoryId: String? = null,
    @SerializedName("container_extension") val containerExtension: String? = "mp4",
    @SerializedName("tmdb") val tmdb: String? = null
) : Serializable

data class SeriesItem(
    @SerializedName("num") val num: Int? = null,
    @SerializedName("name") val name: String,
    @SerializedName("series_id") val seriesId: Int,
    @SerializedName("cover") val cover: String? = null,
    @SerializedName("plot") val plot: String? = null,
    @SerializedName("cast") val cast: String? = null,
    @SerializedName("director") val director: String? = null,
    @SerializedName("genre") val genre: String? = null,
    @SerializedName("releaseDate") val releaseDate: String? = null,
    @SerializedName("rating") val rating: String? = null,
    @SerializedName("category_id") val categoryId: String? = null
) : Serializable

data class SeriesDetailsInfo(
    @SerializedName("name") val name: String? = null,
    @SerializedName("cover") val cover: String? = null,
    @SerializedName("plot") val plot: String? = null,
    @SerializedName("genre") val genre: String? = null,
    @SerializedName("releaseDate") val releaseDate: String? = null,
    @SerializedName("rating") val rating: String? = null,
    @SerializedName("youtube_trailer") val youtubeTrailer: String? = null
) : Serializable

data class VodInfoResponse(
    @SerializedName("info") val info: VodDetailsInfo? = null,
    @SerializedName("movie_data") val movieData: VodMovieData? = null
) : Serializable

data class VodDetailsInfo(
    @SerializedName("name") val name: String? = null,
    @SerializedName("plot") val plot: String? = null,
    @SerializedName("cast") val cast: String? = null,
    @SerializedName("director") val director: String? = null,
    @SerializedName("genre") val genre: String? = null,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("rating") val rating: String? = null,
    @SerializedName("duration_secs") val durationSecs: Int? = null,
    @SerializedName("duration") val duration: String? = null,
    @SerializedName("movie_image") val movieImage: String? = null,
    @SerializedName("youtube_trailer") val youtubeTrailer: String? = null
) : Serializable

data class VodMovieData(
    @SerializedName("stream_id") val streamId: Int? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("container_extension") val containerExtension: String? = null
) : Serializable

data class SeriesInfoResponse(
    @SerializedName("seasons") val seasons: List<SeasonItem>? = null,
    @SerializedName("info") val info: SeriesDetailsInfo? = null,
    @SerializedName("episodes") val episodes: Map<String, List<EpisodeItem>>? = null
) : Serializable

data class SeasonItem(
    @SerializedName("season_number") val seasonNumber: Int,
    @SerializedName("name") val name: String? = null,
    @SerializedName("episode_count") val episodeCount: String? = null
) : Serializable

data class EpisodeItem(
    @SerializedName("id") val id: String,
    @SerializedName("episode_num") val episodeNum: Int,
    @SerializedName("title") val title: String,
    @SerializedName("container_extension") val containerExtension: String? = "mp4",
    @SerializedName("season") val season: Int = 1,
    @SerializedName("info") val info: EpisodeInfo? = null
) : Serializable

data class EpisodeInfo(
    @SerializedName("plot") val plot: String? = null,
    @SerializedName("duration") val duration: String? = null,
    @SerializedName("movie_image") val movieImage: String? = null
) : Serializable

data class TrendingItem(
    val id: Int,
    val title: String,
    val originalTitle: String,
    val posterUrl: String,
    val rating: String,
    val overview: String,
    val mediaType: String, // "MOVIE" or "SERIES"
    val matchedMovies: List<VodStream> = emptyList(),
    val matchedSeries: List<SeriesItem> = emptyList()
) : Serializable

