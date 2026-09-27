package com.yunwu.localmusic

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controllerFuture = MediaController.Builder(
            this,
            SessionToken(this, android.content.ComponentName(this, PlaybackService::class.java))
        ).buildAsync()
        setContent { MusicApp(controllerFuture) }
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}


private const val PREFS_NAME = "local_music_metadata"
private const val KEY_LYRICS_PREFIX = "lyrics_"

private fun metadataPrefs(context: Context) =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

private fun storedLyrics(context: Context, id: Long): String? =
    metadataPrefs(context).getString(KEY_LYRICS_PREFIX + id, null)

private fun saveLyrics(context: Context, id: Long, lyrics: String) {
    metadataPrefs(context).edit().putString(KEY_LYRICS_PREFIX + id, lyrics).apply()
}

private fun coverFile(context: Context, id: Long): File =
    File(File(context.filesDir, "music_covers").apply { mkdirs() }, "$id.jpg")

private fun storedCoverUri(context: Context, id: Long): android.net.Uri? =
    coverFile(context, id).takeIf { it.isFile }?.let { android.net.Uri.fromFile(it) }

private fun importCover(context: Context, id: Long, source: android.net.Uri): android.net.Uri? = runCatching {
    val target = coverFile(context, id)
    context.contentResolver.openInputStream(source).use { input ->
        requireNotNull(input)
        target.outputStream().use { output -> input.copyTo(output) }
    }
    android.net.Uri.fromFile(target)
}.getOrNull()

@Composable
private fun MusicApp(controllerFuture: ListenableFuture<MediaController>?) {
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(false) }
    var songs by remember { mutableStateOf(emptyList<MusicItem>()) }
    var query by remember { mutableStateOf("") }
    var showPlayer by remember { mutableStateOf(false) }
    var currentId by remember { mutableStateOf<Long?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> permissionGranted = ok }

    LaunchedEffect(Unit) {
        permissionGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    val scanScope = rememberCoroutineScope()

    fun scan() {
        scanScope.launch {
            val result = withContext(Dispatchers.IO) { scanSongs(context) }
            songs = result
        }
    }

    LaunchedEffect(permissionGranted) { if (permissionGranted) scan() }

    val controller by produceState<MediaController?>(initialValue = null, controllerFuture) {
        value = runCatching { controllerFuture?.await() }.getOrNull()
    }

    DisposableEffect(controller) {
        val activeController = controller ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentId = mediaItem?.mediaId?.toLongOrNull()
            }
        }
        activeController.addListener(listener)
        isPlaying = activeController.isPlaying
        currentId = activeController.currentMediaItem?.mediaId?.toLongOrNull()
        onDispose { activeController.removeListener(listener) }
    }

    val filtered = remember(songs, query) {
        if (query.isBlank()) songs else songs.filter {
            it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true)
        }
    }
    val currentSong = songs.firstOrNull { it.id == currentId }

    Scaffold(
        containerColor = Color(0xFFF7F7F7),
        bottomBar = {
            AnimatedVisibility(
                visible = currentSong != null && !showPlayer,
                enter = slideInVertically { it } + fadeIn(),
                exit = fadeOut()
            ) {
                currentSong?.let { MiniPlayer(it, isPlaying, controller) { showPlayer = true } }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(18.dp))
                Text("本地音乐", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("简单、干净，只播放手机里的音乐", color = Color.Gray)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("搜索歌曲、歌手或专辑") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { IconButton(onClick = { scan() }) { Icon(Icons.Default.Refresh, "刷新") } },
                    shape = RoundedCornerShape(18.dp)
                )
                Spacer(Modifier.height(14.dp))
                if (!permissionGranted) {
                    PermissionCard { launcher.launch(permission) }
                } else if (filtered.isEmpty()) {
                    EmptyState(songs.isEmpty())
                } else {
                    Text("全部歌曲 · ${filtered.size}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 90.dp)) {
                        items(filtered, key = { it.id }) { song ->
                            SongRow(song, currentId == song.id && isPlaying) {
                                playSongList(controller, filtered, song)
                                currentId = song.id
                                showPlayer = true
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = showPlayer && currentSong != null,
                enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.96f),
                exit = fadeOut(tween(120))
            ) {
                currentSong?.let { song ->
                    PlayerPage(
                        song = song,
                        songs = songs,
                        controller = controller,
                        isPlaying = isPlaying,
                        onBack = { showPlayer = false },
                        onRefresh = { scan() }
                    )
                }
            }
        }
    }
}

private fun playSongList(controller: MediaController?, songs: List<MusicItem>, song: MusicItem) {
    controller ?: return
    val items = songs.map { item ->
        MediaItem.Builder()
            .setMediaId(item.id.toString())
            .setUri(item.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.artist)
                    .setAlbumTitle(item.album)
                    .apply { item.coverUri?.let { setArtworkUri(it) } }
                    .build()
            ).build()
    }
    val index = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
    controller.setMediaItems(items, index, 0L)
    controller.prepare()
    controller.play()
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(22.dp)) {
            Text("需要音乐权限", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("允许后，App 会读取手机本地音乐文件，不会上传歌曲。", color = Color.Gray)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onGrant) { Text("允许访问音乐") }
        }
    }
}

@Composable
private fun EmptyState(empty: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.MusicNote, null, Modifier.size(54.dp), tint = Color.Gray)
            Spacer(Modifier.height(10.dp))
            Text(if (empty) "没有找到本地音乐" else "没有匹配的歌曲", color = Color.Gray)
            Spacer(Modifier.height(8.dp))
            if (empty) Text("把 MP3 / FLAC / M4A 等音乐放进手机后刷新即可", color = Color.Gray)
        }
    }
}

@Composable
private fun SongRow(song: MusicItem, playing: Boolean, onClick: () -> Unit) {
    val pulse by animateFloatAsState(
        targetValue = if (playing) 1.04f else 1f,
        animationSpec = tween(450),
        label = "pulse"
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (playing) Color(0xFFE8F0FE) else Color.Transparent)
            .clickable { onClick() }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(50.dp).graphicsLayer { scaleX = if (playing) pulse else 1f; scaleY = if (playing) pulse else 1f }
            .clip(RoundedCornerShape(12.dp)).background(Color(0xFFDDE3EA)), contentAlignment = Alignment.Center) {
            Icon(if (playing) Icons.Default.VolumeUp else Icons.Default.MusicNote, null, tint = Color.DarkGray)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
        Text(formatDuration(song.duration), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MiniPlayer(song: MusicItem, playing: Boolean, controller: MediaController?, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color.White).clickable { onOpen() }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0xFFDDE3EA)), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
        IconButton({ controller?.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, "上一首") }
        IconButton({ if (playing) controller?.pause() else controller?.play() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "播放暂停") }
        IconButton({ controller?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, "下一首") }
    }
}

@Composable
private fun PlayerPage(
    song: MusicItem,
    songs: List<MusicItem>,
    controller: MediaController?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    var position by remember { mutableLongStateOf(controller?.currentPosition ?: 0L) }
    var duration by remember { mutableLongStateOf(controller?.duration?.takeIf { it > 0 } ?: song.duration) }
    var repeatMode by remember { mutableIntStateOf(controller?.repeatMode ?: Player.REPEAT_MODE_OFF) }
    var shuffle by remember { mutableStateOf(controller?.shuffleModeEnabled ?: false) }
    var lyricsIndex by remember { mutableIntStateOf(0) }
    val lyrics = remember(song.id, song.lyrics) { parseLyrics(song.lyrics) }

    LaunchedEffect(controller, song.id, lyrics) {
        while (true) {
            controller?.let {
                position = it.currentPosition.coerceAtLeast(0L)
                duration = it.duration.takeIf { d -> d > 0 } ?: song.duration
                if (lyrics.isNotEmpty()) {
                    lyricsIndex = lyrics.indexOfLast { line -> line.timeMs <= position }.coerceAtLeast(0)
                }
            }
            delay(300)
        }
    }

    BackHandlerCompat(onBack)

    Column(Modifier.fillMaxSize().background(Color(0xFFF7F7F7)).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, "返回") }
            Text("正在播放", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            PlayerEditButton(context, song, onRefresh)
            IconButton(onClick = { shuffle = !shuffle; controller?.shuffleModeEnabled = shuffle }) {
                Icon(Icons.Default.Shuffle, "随机播放", tint = if (shuffle) MaterialTheme.colorScheme.primary else Color.Gray)
            }
        }

        Spacer(Modifier.height(22.dp))
        val rotation by animateFloatAsState(if (isPlaying) 360f else 0f, tween(900), label = "cover")
        Box(Modifier.fillMaxWidth().weight(0.34f), contentAlignment = Alignment.Center) {
            val coverBitmap = remember(song.id, song.coverUri) {
                song.coverUri?.let { uri -> runCatching { context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) }.getOrNull() }
            }
            Box(Modifier.size(250.dp).graphicsLayer { rotationZ = rotation / 18f }.clip(RoundedCornerShape(32.dp)).background(Color(0xFFDDE3EA)), contentAlignment = Alignment.Center) {
                if (coverBitmap != null) {
                    Image(coverBitmap.asImageBitmap(), contentDescription = "封面", modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Default.MusicNote, null, Modifier.size(110.dp), tint = Color(0xFF66717E))
                }
            }
        }
        Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song.artist, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))

        Slider(
            value = if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f,
            onValueChange = { position = it.toLong() },
            onValueChangeFinished = { controller?.seekTo(position) }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(position), color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            Text(formatDuration(duration), color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(8.dp))
        if (lyrics.isNotEmpty()) {
            LyricsPanel(lyrics, lyricsIndex)
        } else {
            Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
                Text("这首歌没有读取到内嵌歌词", color = Color.Gray)
            }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                repeatMode = when (repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                controller?.repeatMode = repeatMode
            }) { Icon(Icons.Default.Repeat, "循环", tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else Color.Gray) }
            FilledTonalIconButton(onClick = { controller?.seekToPreviousMediaItem() }, modifier = Modifier.size(58.dp)) { Icon(Icons.Default.SkipPrevious, "上一首") }
            FilledIconButton(onClick = { if (isPlaying) controller?.pause() else controller?.play() }, modifier = Modifier.size(72.dp)) {
                AnimatedContent(targetState = isPlaying, label = "play") { playing -> Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "播放暂停", Modifier.size(36.dp)) }
            }
            FilledTonalIconButton(onClick = { controller?.seekToNextMediaItem() }, modifier = Modifier.size(58.dp)) { Icon(Icons.Default.SkipNext, "下一首") }
            Spacer(Modifier.size(48.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}


@Composable
private fun PlayerEditButton(context: Context, song: MusicItem, onRefresh: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val lyricPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull().orEmpty()
            val lowerName = name.lowercase(java.util.Locale.ROOT)
            if (lowerName.endsWith(".lrc") || lowerName.endsWith(".txt") || lowerName.isBlank()) {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF") }
                }.getOrNull()
                if (!text.isNullOrBlank()) {
                    saveLyrics(context, song.id, text)
                    onRefresh()
                    message = "歌词已导入。"
                } else message = "无法读取歌词文件。"
            } else {
                message = "请选择 .lrc 或 .txt 歌词文件。"
            }
        }
    }
    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            if (importCover(context, song.id, uri) != null) { onRefresh(); message = "封面已导入。" }
            else message = "封面导入失败。"
        }
    }
    IconButton(onClick = { open = true }) { Icon(Icons.Default.Edit, "编辑歌曲") }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("编辑歌曲") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { open = false; lyricPicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("导入歌词（.lrc / .txt）") }
                    Button(onClick = { open = false; coverPicker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) { Text("导入封面图片") }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("关闭") } }
        )
    }
    if (message != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("完成") },
            text = { Text(message!!) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } }
        )
    }
}

@Composable
private fun LyricsPanel(lines: List<LyricLine>, active: Int) {
    val state = rememberLazyListState()
    LaunchedEffect(active) {
        if (lines.isNotEmpty()) state.animateScrollToItem(active.coerceIn(0, lines.lastIndex))
    }
    Card(Modifier.fillMaxWidth().height(145.dp), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        LazyColumn(state = state, contentPadding = PaddingValues(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            itemsIndexed(lines, key = { i, _ -> i }) { index, line ->
                val activeLine = index == active
                Text(
                    line.text,
                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 18.dp),
                    color = if (activeLine) MaterialTheme.colorScheme.primary else Color.Gray,
                    fontWeight = if (activeLine) FontWeight.Bold else FontWeight.Normal,
                    style = if (activeLine) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private data class LyricLine(val timeMs: Long, val text: String)

private fun parseLyrics(raw: String?): List<LyricLine> {
    if (raw.isNullOrBlank()) return emptyList()
    val result = mutableListOf<LyricLine>()
    val regex = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]\\s*")
    raw.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        val matches = regex.findAll(line).toList()
        if (matches.isEmpty()) return@forEach
        val text = line.substring(matches.last().range.last + 1).trim().ifBlank { "♪" }
        matches.forEach { match ->
            val min = match.groupValues[1].toLongOrNull() ?: return@forEach
            val sec = match.groupValues[2].toLongOrNull() ?: 0L
            val fraction = match.groupValues[3]
            val ms = when (fraction.length) { 1 -> fraction.toLong() * 100; 2 -> fraction.toLong() * 10; else -> fraction.toLongOrNull() ?: 0L }
            result += LyricLine(min * 60_000L + sec * 1_000L + ms, text)
        }
    }
    return result.sortedBy { it.timeMs }
}


private fun scanSongs(context: Context): List<MusicItem> {
    val list = mutableListOf<MusicItem>()
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION
    )
    context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        projection,
        "${MediaStore.Audio.Media.IS_MUSIC} != 0",
        null,
        "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
    )?.use { c ->
        val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        while (c.moveToNext()) {
            val mediaId = c.getLong(id)
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
            val imported = storedLyrics(context, mediaId)
            list += MusicItem(
                mediaId,
                c.getString(title) ?: "未知歌曲",
                c.getString(artist) ?: "未知歌手",
                c.getString(album) ?: "未知专辑",
                uri,
                c.getLong(duration),
                imported ?: readLyrics(context, uri),
                storedCoverUri(context, mediaId)
            )
        }
    }
    return list
}

private fun readLyrics(context: android.content.Context, uri: android.net.Uri): String? {
    val lrc = runCatching {
        val projection = arrayOf(MediaStore.Audio.Media.DATA)
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            val dataIndex = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            if (c.moveToFirst() && dataIndex >= 0) {
                val path = c.getString(dataIndex)
                if (!path.isNullOrBlank()) {
                    val base = path.substringBeforeLast('.', path)
                    val file = java.io.File(base + ".lrc")
                    if (file.isFile) file.readText(Charsets.UTF_8) else null
                } else null
            } else null
        }
    }.getOrNull()
    if (!lrc.isNullOrBlank()) return lrc

    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(10)
            if (input.read(header) != 10 || header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return@use null
            val version = header[3].toInt() and 0xFF
            val tagSize = if (version >= 4) {
                ((header[6].toInt() and 0x7F) shl 21) or ((header[7].toInt() and 0x7F) shl 14) or
                    ((header[8].toInt() and 0x7F) shl 7) or (header[9].toInt() and 0x7F)
            } else {
                ((header[6].toInt() and 0xFF) shl 24) or ((header[7].toInt() and 0xFF) shl 16) or
                    ((header[8].toInt() and 0xFF) shl 8) or (header[9].toInt() and 0xFF)
            }
            val bytes = ByteArray(tagSize.coerceAtMost(8 * 1024 * 1024))
            var read = 0
            while (read < bytes.size) {
                val n = input.read(bytes, read, bytes.size - read)
                if (n <= 0) break
                read += n
            }
            extractUsltLyrics(bytes, read, version)
        }
    }.getOrNull()
}

private fun extractUsltLyrics(data: ByteArray, length: Int, version: Int): String? {
    var pos = 0
    while (pos + 10 <= length) {
        val id = String(data, pos, 4, Charsets.ISO_8859_1)
        if (id.all { it == '\u0000' }) break
        val size = if (version >= 4) {
            ((data[pos + 4].toInt() and 0x7F) shl 21) or ((data[pos + 5].toInt() and 0x7F) shl 14) or
                ((data[pos + 6].toInt() and 0x7F) shl 7) or (data[pos + 7].toInt() and 0x7F)
        } else {
            ((data[pos + 4].toInt() and 0xFF) shl 24) or ((data[pos + 5].toInt() and 0xFF) shl 16) or
                ((data[pos + 6].toInt() and 0xFF) shl 8) or (data[pos + 7].toInt() and 0xFF)
        }
        if (size <= 0 || pos + 10 + size > length) break
        if (id == "USLT") {
            val payloadStart = pos + 10
            val encoding = data[payloadStart].toInt() and 0xFF
            val charset = when (encoding) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
            var textStart = payloadStart + 4
            val end = pos + 10 + size
            val terminator = if (encoding == 1 || encoding == 2) 2 else 1
            while (textStart + terminator <= end) {
                val zero = data[textStart] == 0.toByte() && (terminator == 1 || data[textStart + 1] == 0.toByte())
                if (zero) { textStart += terminator; break }
                textStart++
            }
            if (textStart < end) return String(data, textStart, end - textStart, charset).trim()
        }
        pos += 10 + size
    }
    return null
}

private fun formatDuration(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun BackHandlerCompat(onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
}
