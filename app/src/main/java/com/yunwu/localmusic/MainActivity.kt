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
import androidx.compose.ui.text.style.TextAlign
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
import org.json.JSONArray
import org.json.JSONObject

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


private const val KEY_PLAYLISTS = "user_playlists_v1"
private fun loadPlaylists(context: Context): Map<String, List<Long>> = runCatching {
    val root = JSONObject(metadataPrefs(context).getString(KEY_PLAYLISTS, "{}") ?: "{}")
    buildMap { val keys = root.keys(); while (keys.hasNext()) { val name = keys.next(); val arr = root.optJSONArray(name) ?: JSONArray(); put(name, (0 until arr.length()).mapNotNull { arr.optLong(it).takeIf { id -> id > 0L } }) } }
}.getOrDefault(emptyMap())
private fun savePlaylists(context: Context, playlists: Map<String, List<Long>>) {
    val root = JSONObject(); playlists.forEach { (name, ids) -> root.put(name, JSONArray(ids)) }
    metadataPrefs(context).edit().putString(KEY_PLAYLISTS, root.toString()).apply()
}

@Composable
private fun MusicApp(controllerFuture: ListenableFuture<MediaController>?) {
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(false) }
    var songs by remember { mutableStateOf(emptyList<MusicItem>()) }
    var query by remember { mutableStateOf("") }
    var showPlayer by remember { mutableStateOf(false) }
    var currentId by remember { mutableStateOf<Long?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var playlists by remember { mutableStateOf(loadPlaylists(context)) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var activePlaylist by remember { mutableStateOf<String?>(null) }
    var addToPlaylist by remember { mutableStateOf<String?>(null) }
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
        containerColor = Color(0xFFF4F2FA),
        bottomBar = {
            Column {
                AnimatedVisibility(visible = currentSong != null && !showPlayer, enter = slideInVertically { it } + fadeIn(), exit = fadeOut()) {
                    currentSong?.let { MiniPlayer(it, isPlaying, controller) { showPlayer = true } }
                }
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0; activePlaylist = null }, icon = { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("音乐") })
                    NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.QueueMusic, null) }, label = { Text("播放列表") })
                    NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Default.Campaign, null) }, label = { Text("公告栏") })
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (selectedTab == 1) {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(20.dp))
                    Text("播放列表", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color(0xFF24213A))
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { showCreatePlaylist = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("创建播放列表") }
                    Spacer(Modifier.height(12.dp))
                    if (playlists.isEmpty()) Text("还没有播放列表，点击上方创建。", color = Color.Gray, modifier = Modifier.padding(12.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(playlists.keys.toList(), key = { it }) { name ->
                            Card(Modifier.fillMaxWidth().clickable { activePlaylist = if (activePlaylist == name) null else name }, colors = CardDefaults.cardColors(containerColor = if (activePlaylist == name) Color(0xFFE9E6FF) else Color.White)) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.QueueMusic, null, tint = Color(0xFF7167D9)); Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.SemiBold); Text("${playlists[name]?.size ?: 0} 首歌曲", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
                                    IconButton(onClick = { addToPlaylist = name }) { Icon(Icons.Default.Add, "添加歌曲") }
                                    IconButton(onClick = { playlists = playlists - name; savePlaylists(context, playlists); if (activePlaylist == name) activePlaylist = null }) { Icon(Icons.Default.DeleteOutline, "删除列表") }
                                }
                            }
                            if (activePlaylist == name) {
                                val members = songs.filter { it.id in (playlists[name] ?: emptyList()) }
                                if (members.isEmpty()) Text("列表为空，点右侧 + 添加歌曲。", color = Color.Gray, modifier = Modifier.padding(12.dp))
                                members.forEach { item -> SongRow(item, currentId == item.id && isPlaying) { playSongList(controller, members, item); currentId = item.id; showPlayer = true } }
                            }
                        }
                    }
                }
            } else if (selectedTab == 2) {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    Spacer(Modifier.height(20.dp))
                    Text("公告栏", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(20.dp)) {
                            Text("本地音乐 V2.1", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("• 修复播放进度条拖动与跳转\n• 播放封面保持静止\n• 新增音乐、播放列表和公告栏 Dock\n• 播放列表支持创建、添加歌曲和删除", color = Color.DarkGray)
                            Spacer(Modifier.height(12.dp))
                            Text("提示：卸载 App 可能会清除本地播放列表和导入的封面/歌词，请先备份。", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(18.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF24213A))
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("LOCAL MUSIC", color = Color(0xFFB9B3FF), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(5.dp))
                            Text("本地音乐", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                            Text("你的音乐，只属于你的设备", color = Color(0xFFD0CDDF), style = MaterialTheme.typography.bodySmall)
                        }
                        Box(Modifier.size(58.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF7167D9)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.GraphicEq, null, tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("搜索歌曲、歌手或专辑") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { IconButton(onClick = { scan() }) { Icon(Icons.Default.Refresh, "刷新") } },
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, focusedBorderColor = Color(0xFF7167D9), unfocusedBorderColor = Color(0xFFE5E1EF))
                )
                Spacer(Modifier.height(14.dp))
                if (!permissionGranted) {
                    PermissionCard { launcher.launch(permission) }
                } else if (filtered.isEmpty()) {
                    EmptyState(songs.isEmpty())
                } else {
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("全部歌曲", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF24213A))
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = CircleShape, color = Color(0xFFE5E1FB)) { Text("${filtered.size} 首", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = Color(0xFF5147B8), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
                    }
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 100.dp)) {
                        items(filtered, key = { it.id }, contentType = { "song" }) { song ->
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
            if (showCreatePlaylist) AlertDialog(
                onDismissRequest = { showCreatePlaylist = false },
                title = { Text("新建播放列表") },
                text = { OutlinedTextField(newPlaylistName, { newPlaylistName = it }, label = { Text("列表名称") }, singleLine = true) },
                confirmButton = { TextButton(onClick = { val n = newPlaylistName.trim(); if (n.isNotEmpty() && n !in playlists) { playlists = playlists + (n to emptyList()); savePlaylists(context, playlists); newPlaylistName = ""; showCreatePlaylist = false } }) { Text("创建") } },
                dismissButton = { TextButton(onClick = { showCreatePlaylist = false }) { Text("取消") } }
            )
            addToPlaylist?.let { name ->
                AlertDialog(onDismissRequest = { addToPlaylist = null }, title = { Text("添加歌曲到 $name") },
                    text = { LazyColumn(Modifier.heightIn(max = 360.dp)) { items(songs, key = { it.id }) { item -> Row(Modifier.fillMaxWidth().clickable { val updated = (playlists[name] ?: emptyList()) + item.id; playlists = playlists + (name to updated.distinct()); savePlaylists(context, playlists); addToPlaylist = null }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.MusicNote, null); Spacer(Modifier.width(10.dp)); Column { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(item.artist, color = Color.Gray, style = MaterialTheme.typography.bodySmall) } } } } },
                    confirmButton = { TextButton(onClick = { addToPlaylist = null }) { Text("完成") } })
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(if (playing) Color(0xFFE9E6FF) else Color.White)
            .clickable { onClick() }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(50.dp).graphicsLayer { scaleX = if (playing) pulse else 1f; scaleY = if (playing) pulse else 1f }
            .clip(RoundedCornerShape(14.dp)).background(if (playing) Color(0xFF7167D9) else Color(0xFFEDEAF5)), contentAlignment = Alignment.Center) {
            Icon(if (playing) Icons.Default.VolumeUp else Icons.Default.MusicNote, null, tint = if (playing) Color.White else Color(0xFF625B83))
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
    var isSeeking by remember { mutableStateOf(false) }
    var seekTarget by remember(song.id) { mutableFloatStateOf(0f) }
    val sliderScale by animateFloatAsState(
        targetValue = if (isSeeking) 1.12f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "seek-slider-emphasis"
    )
    val lyrics = remember(song.id, song.lyrics) { parseLyrics(song.lyrics) }

    LaunchedEffect(controller, song.id, lyrics) {
        while (true) {
            controller?.let {
                duration = it.duration.takeIf { d -> d > 0 && d != androidx.media3.common.C.TIME_UNSET } ?: song.duration
                if (!isSeeking) {
                    position = it.currentPosition.coerceAtLeast(0L)
                    seekTarget = position.toFloat()
                }
                if (lyrics.isNotEmpty() && !isSeeking) {
                    lyricsIndex = lyrics.indexOfLast { line -> line.timeMs <= position }.coerceAtLeast(0)
                }
            }
            delay(200)
        }
    }

    BackHandlerCompat(onBack)

    Column(Modifier.fillMaxSize().background(Color(0xFFF4F2FA)).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, "返回") }
            Text("正在播放", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            PlayerEditButton(context, song, onRefresh)
            IconButton(onClick = { shuffle = !shuffle; controller?.shuffleModeEnabled = shuffle }) {
                Icon(Icons.Default.Shuffle, "随机播放", tint = if (shuffle) MaterialTheme.colorScheme.primary else Color.Gray)
            }
        }

        Spacer(Modifier.height(22.dp))
        Box(Modifier.fillMaxWidth().weight(0.34f), contentAlignment = Alignment.Center) {
            val coverBitmap = remember(song.id, song.coverUri) {
                song.coverUri?.let { uri -> runCatching { context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) }.getOrNull() }
            }
            Box(Modifier.size(250.dp).clip(RoundedCornerShape(32.dp)).background(Color(0xFFDDE3EA)), contentAlignment = Alignment.Center) {
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
            value = if (duration > 0) (if (isSeeking) seekTarget else position.toFloat()).coerceIn(0f, duration.toFloat()) else 0f,
            onValueChange = { value ->
                // Update the thumb immediately and send the seek while dragging. This avoids
                // the playback polling loop or a stale finish callback swallowing the seek.
                isSeeking = true
                seekTarget = value.coerceIn(0f, duration.coerceAtLeast(1L).toFloat())
                position = seekTarget.toLong()
                controller?.let { player ->
                    if (player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
                        player.seekTo(position.coerceIn(0L, duration.coerceAtLeast(0L)))
                    }
                }
            },
            onValueChangeFinished = {
                val target = seekTarget.toLong().coerceIn(0L, duration.coerceAtLeast(0L))
                controller?.let { player ->
                    if (player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) player.seekTo(target)
                }
                position = target
                isSeeking = false
            },
            modifier = Modifier.fillMaxWidth().graphicsLayer { scaleY = sliderScale }
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
        if (lines.isNotEmpty()) state.animateScrollToItem(active.coerceIn(0, lines.lastIndex), scrollOffset = -48)
    }
    Card(Modifier.fillMaxWidth().height(145.dp), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        LazyColumn(state = state, contentPadding = PaddingValues(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            itemsIndexed(lines, key = { i, line -> "${line.timeMs}_$i" }, contentType = { _, _ -> "lyric-line" }) { index, line ->
                val activeLine = index == active
                Text(
                    line.text,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 18.dp),
                    textAlign = TextAlign.Center,
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
