package com.tivizone.player.data

data class ChannelWithEpg(
    val channel: MultiStreamChannel,
    var epgList: List<EpgProgram> = emptyList()
) {
    constructor(stream: LiveStream, epgList: List<EpgProgram> = emptyList()) : this(
        channel = MultiStreamChannel(
            cleanName = stream.name,
            originalName = stream.name,
            categoryId = stream.categoryId ?: "",
            categoryName = "",
            icon = stream.streamIcon,
            epgId = stream.epgChannelId,
            sources = listOf(
                StreamSource(
                    streamId = stream.streamId,
                    name = stream.name,
                    label = "Standard Stream",
                    score = 50,
                    subcategory = ""
                )
            )
        ),
        epgList = epgList
    )

    val stream: LiveStream
        get() = LiveStream(
            streamId = channel.primarySource?.streamId ?: 0,
            name = channel.cleanName,
            streamIcon = channel.icon,
            epgChannelId = channel.epgId,
            categoryId = channel.categoryId
        )
}
