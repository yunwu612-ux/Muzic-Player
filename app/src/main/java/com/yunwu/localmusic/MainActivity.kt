package com.yunwu.localmusic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.guava.await
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controllerFuture = MediaController.Builder(this, SessionToken(this, android.content.ComponentName(this, PlaybackService::class.java))).buildAsync()
        setContent { MusicApp(controllerFuture) }
    }
    override fun onDestroy() { controllerFuture?.let { MediaController.releaseFuture(it) }; super.onDestroy() }
}

@Composable
private fun MusicApp(controllerFuture: ListenableFuture<MediaController>?) {
    var permissionGranted by remember { mutableStateOf(false) }
    var songs by remember { mutableStateOf(emptyList<MusicItem>()) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<MusicItem?>(null) }
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> permissionGranted = ok }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(permission) {
        permissionGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    fun scan() {
        val list = mutableListOf<MusicItem>()
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION)
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID); val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE); val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST); val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM); val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) { val mediaId = c.getLong(id); list += MusicItem(mediaId, c.getString(title) ?: "未知歌曲", c.getString(artist) ?: "未知歌手", c.getString(album) ?: "未知专辑", android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId), c.getLong(duration)) }
        }
        songs = list
    }
    LaunchedEffect(permissionGranted) { if (permissionGranted) scan() }
    val filtered = remember(songs, query) { if (query.isBlank()) songs else songs.filter { it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) } }
    val controller by produceState<MediaController?>(initialValue = null, controllerFuture) {
        value = runCatching { controllerFuture?.await() }.getOrNull()
    }
    val isPlaying = controller?.isPlaying == true
    Scaffold(containerColor = Color(0xFFF7F7F7), bottomBar = { if (selected != null) MiniPlayer(selected!!, isPlaying, controller) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(18.dp)); Text("本地音乐", style = MaterialTheme.typography.headlineMedium); Text("简单、干净，只播放手机里的音乐", color = Color.Gray)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("搜索歌曲、歌手或专辑") }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { IconButton(onClick = { scan() }) { Icon(Icons.Default.Refresh, "刷新") } }, shape = RoundedCornerShape(18.dp))
            Spacer(Modifier.height(14.dp))
            if (!permissionGranted) {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) { Column(Modifier.padding(22.dp)) { Text("需要音乐权限", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(8.dp)); Text("允许后，App 会读取手机本地音乐文件，不会上传歌曲。", color = Color.Gray); Spacer(Modifier.height(16.dp)); Button({ launcher.launch(permission) }) { Text("允许访问音乐") } } }
            } else if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.MusicNote, null, Modifier.size(54.dp), tint = Color.Gray); Spacer(Modifier.height(10.dp)); Text(if (songs.isEmpty()) "没有找到本地音乐" else "没有匹配的歌曲", color = Color.Gray); Spacer(Modifier.height(8.dp)); if (songs.isEmpty()) Text("把 MP3 / FLAC / M4A 等音乐放进手机后刷新即可", color = Color.Gray) } }
            } else {
                Text("全部歌曲 · ${filtered.size}", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(6.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) { items(filtered, key = { it.id }) { song -> SongRow(song, selected?.id == song.id) { selected = song; controller?.let { c -> c.setMediaItems(filtered.map { MediaItem.Builder().setMediaId(it.id.toString()).setUri(it.uri).setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(it.title).setArtist(it.artist).setAlbumTitle(it.album).build()).build() }, filtered.indexOf(song), 0L); c.prepare(); c.play() } } } }
            }
        }
    }
}

@Composable private fun SongRow(song: MusicItem, playing: Boolean, onClick: () -> Unit) { Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (playing) Color(0xFFE8F0FE) else Color.Transparent).clickable { onClick() }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFDDE3EA)), contentAlignment = Alignment.Center) { Icon(if (playing) Icons.Default.VolumeUp else Icons.Default.MusicNote, null, tint = Color.DarkGray) }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.Gray, style = MaterialTheme.typography.bodySmall) }; Text(formatDuration(song.duration), color = Color.Gray, style = MaterialTheme.typography.bodySmall) } }

@Composable private fun MiniPlayer(song: MusicItem, playing: Boolean, controller: MediaController?) { Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0xFFDDE3EA)), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null) }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(song.artist, color = Color.Gray, style = MaterialTheme.typography.bodySmall) }; IconButton({ controller?.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, "上一首") }; IconButton({ if (playing) controller?.pause() else controller?.play() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "播放暂停") }; IconButton({ controller?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, "下一首") } } }

private fun formatDuration(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
