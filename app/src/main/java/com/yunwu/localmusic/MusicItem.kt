package com.yunwu.localmusic

data class MusicItem(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val uri: android.net.Uri,
    val duration: Long,
    val lyrics: String? = null
)
