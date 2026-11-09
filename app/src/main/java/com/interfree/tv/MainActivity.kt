package com.interfree.tv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Blue = Color(0xFF2F8CFF)
val Red = Color(0xFFE5222B)
val Violet = Color(0xFF8B3DFF)
val Bg = Color(0xFF030515)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = BrandColors) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

data class Session(val base: String, val user: String, val pass: String, val account: Account)

/** Borde, zoom y clic para que funcione bien con el control remoto de la TV. */
@Composable
fun Modifier.tvFocus(shape: Shape = RoundedCornerShape(12.dp), onClick: () -> Unit): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .scale(if (focused) 1.04f else 1f)
        .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
        .clip(shape)
        .clickable(onClick = onClick)
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<Session?>(null) }
    var loginBusy by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf("") }
    var section by remember { mutableStateOf<Int?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<Map<Int, List<Item>>>(emptyMap()) }
    var error by remember { mutableStateOf("") }
    var favs by remember { mutableStateOf(prefs.getStringSet("favs", emptySet())!!.toSet()) }
    var cats by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var queries by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var series by remember { mutableStateOf<Item?>(null) }
    var episodes by remember { mutableStateOf<List<Episode>?>(null) }
    var playing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var splash by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(1400)
        splash = false
    }

    fun login(server: String, user: String, pass: String) {
        loginBusy = true
        loginError = ""
        scope.launch {
            try {
                val b = Xt.base(server)
                val acc = withContext(Dispatchers.IO) { Xt.login(b, user, pass) }
                prefs.edit().putString("server", server).putString("user", user)
                    .putString("pass", pass).apply()
                session = Session(b, user, pass, acc)
            } catch (e: Exception) {
                loginError = "No se pudo entrar: ${e.message}"
            }
            loginBusy = false
        }
    }

    fun reloadAll() {
        data = emptyMap()
        error = ""
        reload++
        val s0 = session ?: return
        scope.launch {
            try {
                val acc = withContext(Dispatchers.IO) { Xt.login(s0.base, s0.user, s0.pass) }
                session = s0.copy(account = acc)
            } catch (e: Exception) {
            }
        }
    }

    LaunchedEffect(Unit) {
        val u = prefs.getString("user", "") ?: ""
        if (u.isNotEmpty()) {
            login(
                if (SERVER_URL.isNotBlank()) SERVER_URL else prefs.getString("server", "")!!,
                u, prefs.getString("pass", "")!!
            )
        }
    }

    LaunchedEffect(session, section, reload) {
        val s1 = session ?: return@LaunchedEffect
        val sec1 = section ?: return@LaunchedEffect
        if (data[sec1] == null) {
            error = ""
            try {
                val list = withContext(Dispatchers.IO) { Xt.catalog(s1.base, s1.user, s1.pass, sec1) }
                data = data + (sec1 to list)
            } catch (e: Exception) {
                error = "No se pudo cargar: ${e.message}"
            }
        }
    }

    val cur = playing
    val ser = series
    val s = session
    val sec = section
    when {
        splash -> SplashScreen()
        cur != null -> PlayerScreen(cur.second) { playing = null }
        ser != null -> EpisodesScreen(ser.name, ser.icon, episodes, { playing = it.title to it.url }) {
            series = null
            episodes = null
        }
        s == null -> LoginScreen(loginBusy, loginError, prefs) { sv, u, p -> login(sv, u, p) }
        sec == null -> HomeDashboard(
            account = s.account,
            user = s.user,
            onOpen = { section = it },
            onReload = { reloadAll() },
            onLogout = {
                session = null
                data = emptyMap()
                section = null
                prefs.edit().remove("pass").remove("user").apply()
            }
        )
        else -> SectionScreen(
            section = sec,
            catalog = data[sec],
            error = error,
            favs = favs,
            cat = cats[sec] ?: "Todos",
            onCat = { cats = cats + (sec to it) },
            q = queries[sec] ?: "",
            onQ = { queries = queries + (sec to it) },
            onOpen = { item ->
                if (item.seriesId.isNotBlank()) {
                    series = item
                    episodes = null
                    scope.launch {
                        episodes = try {
                            withContext(Dispatchers.IO) { Xt.episodes(s.base, s.user, s.pass, item.seriesId) }
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                } else {
                    playing = item.name to item.url
                }
            },
            onFav = { key ->
                favs = if (key in favs) favs - key else favs + key
                prefs.edit().putStringSet("favs", favs).apply()
            },
            onBack = { section = null }
        )
    }
}

@Composable
fun rememberClock(): String {
    var t by remember { mutableStateOf(nowText()) }
    LaunchedEffect(Unit) {
        while (true) {
            t = nowText()
            delay(20_000)
        }
    }
    return t
}

private fun nowText(): String =
    SimpleDateFormat("HH:mm  ·  EEE d MMM", Locale.getDefault()).format(Date())

// ---------------------------------------------------------------- Inicio

@Composable
fun HomeDashboard(
    account: Account,
    user: String,
    onOpen: (Int) -> Unit,
    onReload: () -> Unit,
    onLogout: () -> Unit
) {
    var showAccount by remember { mutableStateOf(false) }
    val clock = rememberClock()
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Bg, Color(0xFF0B1030))))
    ) {
        val wide = maxWidth >= 640.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.logo_mark), contentDescription = null, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(10.dp))
                BrandTitle(24)
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(clock, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Vence: ${account.expiry}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            if (wide) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BigTile("TV en vivo", "Canales y deportes", "📺", listOf(Blue, Color(0xFF1B56C4)),
                        Modifier.weight(1f).fillMaxHeight()) { onOpen(0) }
                    BigTile("Películas", "Estrenos y clásicos", "🎬", listOf(Red, Color(0xFF9E141B)),
                        Modifier.weight(1f).fillMaxHeight()) { onOpen(1) }
                    BigTile("Series", "Temporadas completas", "🍿", listOf(Violet, Color(0xFF5B22B8)),
                        Modifier.weight(1f).fillMaxHeight()) { onOpen(2) }
                }
            } else {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigTile("TV en vivo", "Canales y deportes", "📺", listOf(Blue, Color(0xFF1B56C4)),
                        Modifier.weight(1f).fillMaxWidth()) { onOpen(0) }
                    BigTile("Películas", "Estrenos y clásicos", "🎬", listOf(Red, Color(0xFF9E141B)),
                        Modifier.weight(1f).fillMaxWidth()) { onOpen(1) }
                    BigTile("Series", "Temporadas completas", "🍿", listOf(Violet, Color(0xFF5B22B8)),
                        Modifier.weight(1f).fillMaxWidth()) { onOpen(2) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallTile("👤  Mi cuenta", Modifier.weight(1f)) { showAccount = true }
                SmallTile("🔄  Recargar", Modifier.weight(1f)) { onReload() }
                SmallTile("⏻  Salir", Modifier.weight(1f)) { onLogout() }
            }
        }
    }
    if (showAccount) {
        AlertDialog(
            onDismissRequest = { showAccount = false },
            confirmButton = { TextButton(onClick = { showAccount = false }) { Text("Cerrar") } },
            title = { Text("Mi cuenta") },
            text = {
                Column {
                    Text("Usuario: $user")
                    Text("Estado: ${account.status}")
                    Text("Vence: ${account.expiry}")
                    Text("Conexiones: ${account.activeCon}/${account.maxCon}")
                }
            }
        )
    }
}

@Composable
fun BigTile(
    title: String,
    subtitle: String,
    icon: String,
    colors: List<Color>,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier.tvFocus(shape, onClick).background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 52.sp)
            Spacer(Modifier.height(6.dp))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text(subtitle, fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
fun SmallTile(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.tvFocus(RoundedCornerShape(14.dp), onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------- Secciones

@Composable
fun SectionScreen(
    section: Int,
    catalog: List<Item>?,
    error: String,
    favs: Set<String>,
    cat: String,
    onCat: (String) -> Unit,
    q: String,
    onQ: (String) -> Unit,
    onOpen: (Item) -> Unit,
    onFav: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val title = listOf("TV en vivo", "Películas", "Series")[section]
    val all = catalog ?: emptyList()
    val counts = remember(catalog) { all.groupingBy { it.group }.eachCount() }
    val groups = remember(catalog) { listOf("Todos", "Favoritos") + counts.keys.toList() }
    val shown = remember(catalog, favs, q, cat) {
        all.filter {
            (cat == "Todos" || (cat == "Favoritos" && it.key in favs) || it.group == cat) &&
                it.name.contains(q, ignoreCase = true)
        }
    }
    var selected by remember { mutableStateOf<Item?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Bg)) {
        val wide = maxWidth >= 640.dp
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Inicio") }
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (wide) {
                    OutlinedTextField(
                        q, onQ, placeholder = { Text("Buscar") },
                        singleLine = true, modifier = Modifier.width(280.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                error.isNotEmpty() -> Text(error, color = MaterialTheme.colorScheme.error)
                catalog == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                wide -> Row(Modifier.fillMaxSize()) {
                    CategoryPane(groups, counts, all.size, cat, onCat, Modifier.width(220.dp).fillMaxHeight())
                    Spacer(Modifier.width(12.dp))
                    if (section == 0) {
                        LazyColumn(Modifier.weight(1f).fillMaxHeight()) {
                            items(shown) { ch ->
                                LiveRow(
                                    ch, ch.key in favs, ch.key == selected?.key,
                                    {
                                        if (selected?.key == ch.key) {
                                            onOpen(ch)
                                        } else {
                                            selected = ch
                                        }
                                    },
                                    { onFav(ch.key) }
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        PreviewPane(selected, Modifier.width(340.dp).fillMaxHeight()) {
                            selected?.let { onOpen(it) }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(130.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) {
                            gridItems(shown) { it2 ->
                                PosterCard(it2, it2.key in favs, { onOpen(it2) }, { onFav(it2.key) })
                            }
                        }
                    }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        q, onQ, placeholder = { Text("Buscar") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow {
                        items(groups) { g ->
                            FilterChip(
                                selected = cat == g, onClick = { onCat(g) }, label = { Text(g) },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                    if (section == 0) {
                        LazyColumn {
                            items(shown) { ch ->
                                LiveRow(ch, ch.key in favs, false, { onOpen(ch) }, { onFav(ch.key) })
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(112.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            gridItems(shown) { it2 ->
                                PosterCard(it2, it2.key in favs, { onOpen(it2) }, { onFav(it2.key) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryPane(
    groups: List<String>,
    counts: Map<String, Int>,
    total: Int,
    cat: String,
    onCat: (String) -> Unit,
    modifier: Modifier
) {
    LazyColumn(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(6.dp)
    ) {
        items(groups) { g ->
            val isSel = g == cat
            val n: Int? = when (g) {
                "Todos" -> total
                "Favoritos" -> null
                else -> counts[g]
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .tvFocus(RoundedCornerShape(10.dp)) { onCat(g) }
                    .background(if (isSel) Blue.copy(alpha = 0.35f) else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    g, Modifier.weight(1f), maxLines = 1,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                )
                if (n != null) {
                    Text(
                        "$n", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun PreviewPane(item: Item?, modifier: Modifier, onFull: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .tvFocus(RoundedCornerShape(10.dp)) { if (item != null) onFull() }
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (item == null) {
                Text("Elige un canal", color = Color.White.copy(alpha = 0.7f))
            } else {
                PreviewPlayer(item.url, Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(item?.name ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 2)
        Text(
            item?.group ?: "", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (item == null) "Selecciona un canal para verlo aquí" else "Pulsa OK otra vez para ver en pantalla completa",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun PreviewPlayer(url: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build().apply { playWhenReady = true } }
    LaunchedEffect(url) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    AndroidView(
        factory = { c -> PlayerView(c).apply { this.player = player; useController = false } },
        modifier = modifier
    )
}

@Composable
fun LiveRow(item: Item, fav: Boolean, selected: Boolean, onClick: () -> Unit, onFav: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .tvFocus(RoundedCornerShape(12.dp), onClick)
            .background(if (selected) Blue.copy(alpha = 0.25f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(item.name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
            if (item.icon.isNotBlank()) {
                AsyncImage(
                    model = item.icon, contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(4.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1)
            Text(item.group, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        TextButton(onClick = onFav) { Text(if (fav) "★" else "☆") }
    }
}

@Composable
fun PosterCard(item: Item, fav: Boolean, onOpen: () -> Unit, onFav: () -> Unit) {
    Column(Modifier.tvFocus(RoundedCornerShape(12.dp), onOpen)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text(
                item.name.take(1).uppercase(), Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.headlineLarge
            )
            if (item.icon.isNotBlank()) {
                AsyncImage(
                    model = item.icon, contentDescription = item.name,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
            }
            Text(
                if (fav) "★" else "☆",
                Modifier.align(Alignment.TopEnd).clickable { onFav() }.padding(8.dp),
                color = Color.White
            )
        }
        Text(
            item.name, maxLines = 2, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp, bottom = 2.dp)
        )
    }
}

// ---------------------------------------------------------------- Episodios, reproductor, acceso

@Composable
fun EpisodesScreen(title: String, cover: String, eps: List<Episode>?, onPlay: (Episode) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Bg).padding(12.dp)) {
        TextButton(onClick = onBack) { Text("‹ Volver") }
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(84.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (cover.isNotBlank()) {
                    AsyncImage(
                        model = cover, contentDescription = title,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        when {
            eps == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            eps.isEmpty() -> Text("No hay episodios disponibles")
            else -> LazyColumn {
                items(eps) { e ->
                    Text(
                        e.title,
                        Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .tvFocus(RoundedCornerShape(10.dp)) { onPlay(e) }
                            .padding(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PlayerScreen(url: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var playbackError by remember { mutableStateOf("") }
    var retryCount by remember { mutableIntStateOf(0) }

    // Buffer amplio para absorber variaciones normales de redes Wi-Fi y moviles.
    // No puede corregir una fuente caida ni crear calidades que el proveedor no entregue.
    val loadControl = remember {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }
    val trackSelector = remember { DefaultTrackSelector(ctx) }
    val player = remember {
        ExoPlayer.Builder(ctx)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .build()
            .apply {
                playWhenReady = true
                setMediaItem(MediaItem.fromUri(url))
                prepare()
            }
    }

    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = error.errorCodeName
                if (retryCount < 2) {
                    retryCount++
                    scope.launch {
                        delay(1800L * retryCount)
                        playbackError = ""
                        player.prepare()
                        player.playWhenReady = true
                    }
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    playbackError = ""
                    retryCount = 0
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c -> PlayerView(c).apply {
                this.player = player
                keepScreenOn = true
                useController = true
                controllerShowTimeoutMs = 4500
            } },
            update = { it.player = player },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF080D20)).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("Volver") }
            TextButton(onClick = {
                TrackSelectionDialogBuilder(ctx, "Idioma de audio", trackSelector, C.TRACK_TYPE_AUDIO).build().show()
            }) { Text("Audio") }
            TextButton(onClick = {
                TrackSelectionDialogBuilder(ctx, "Subtitulos", trackSelector, C.TRACK_TYPE_TEXT).build().show()
            }) { Text("Subtitulos") }
            if (playbackError.isNotBlank()) {
                TextButton(onClick = {
                    playbackError = ""
                    retryCount = 0
                    player.seekToDefaultPosition()
                    player.prepare()
                    player.play()
                }) { Text("Reintentar") }
            }
        }
        if (playbackError.isNotBlank()) {
            Text(
                "Problema de reproduccion: $playbackError",
                color = Color(0xFFFFB4AB),
                modifier = Modifier.fillMaxWidth().background(Color(0xFF241010)).padding(8.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun LoginScreen(
    busy: Boolean,
    error: String,
    prefs: android.content.SharedPreferences,
    onGo: (String, String, String) -> Unit
) {
    var server by remember { mutableStateOf(prefs.getString("server", "")!!) }
    var user by remember { mutableStateOf(prefs.getString("user", "")!!) }
    var pass by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painterResource(R.drawable.logo_full),
            contentDescription = "INTERFREE TV",
            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
            contentScale = ContentScale.Fit
        )
        Text("Inicia sesión con tu usuario")
        Spacer(Modifier.height(16.dp))
        Column(Modifier.widthIn(max = 420.dp)) {
            if (SERVER_URL.isBlank()) {
                OutlinedTextField(
                    server, { server = it }, label = { Text("Servidor (http://host:puerto)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                user, { user = it }, label = { Text("Usuario") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                pass, { pass = it }, label = { Text("Contraseña") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            if (error.isNotEmpty()) {
                Text(error, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = {
                    onGo(if (SERVER_URL.isNotBlank()) SERVER_URL else server.trim(), user.trim(), pass.trim())
                },
                enabled = !busy, modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Entrando…" else "Entrar") }
        }
    }
}

val BrandColors = darkColorScheme(
    primary = Color(0xFF2F8CFF),
    onPrimary = Color(0xFF031026),
    secondary = Color(0xFF8B3DFF),
    tertiary = Color(0xFFE5222B),
    background = Color(0xFF030515),
    onBackground = Color(0xFFF2F5FF),
    surface = Color(0xFF030515),
    onSurface = Color(0xFFF2F5FF),
    surfaceVariant = Color(0xFF111A3D),
    onSurfaceVariant = Color(0xFFA9B4D8),
    outline = Color(0xFF2A3566)
)

@Composable
fun BrandTitle(size: Int = 30) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Color.White)) { append("INTERFREE ") }
            withStyle(SpanStyle(color = Color(0xFFE5222B))) { append("TV") }
        },
        fontSize = size.sp,
        fontWeight = FontWeight.ExtraBold,
        fontStyle = FontStyle.Italic
    )
}

@Composable
fun SplashScreen() {
    Box(
        Modifier.fillMaxSize().background(Color(0xFF030515)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painterResource(R.drawable.logo_full),
            contentDescription = "INTERFREE TV",
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            contentScale = ContentScale.Fit
        )
    }
}
