package com.local.vod.domain.model

/** Un canal de una lista IPTV: el aparato abre [url] directamente. */
data class Channel(
    val id: String,
    val name: String,
    val logoUrl: String?,
    val url: String,
    val group: String,
)

data class ChannelGroup(val name: String, val channels: List<Channel>)
