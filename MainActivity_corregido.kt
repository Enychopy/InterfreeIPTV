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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
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
    var seriesInfo by remember { mutableStateOf<SeriesInfo?>(null) }
    var movieDetail by remember { mutableStateOf<Item?>(null) }
    var playing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var playerVisible by remember { mutableStateOf(false) }
    var splash by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(1400)
        splash = false
    }

    fun login(server: String, user: String, pass: String) {
        loginBusy = true
        loginError = ""
        if (server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) {
            prefs.edit().putString("server", server).putString("user", user)
                .putString("pass", pass).apply()
        }
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
        if (u.isNotEmpty() && !prefs.getString("pass", "").isNullOrEmpty()) {
            login(
                if (SERVER_URL.isNotBlank()) SERVER_URL else prefs.getString("server", "")!!,
                u, prefs.getString("pass", "")!!
            )
        }
    }

    // Precarga los tres catálogos en paralelo al iniciar sesión.
    // Así, al cambiar entre TV, películas y series, los datos ya estarán en memoria.
    LaunchedEffect(session, reload) {
        val s1 = session ?: return@LaunchedEffect
        error = ""
        coroutineScope {
            (0..2).map { sec1 ->
                launch {
                    // No repetir una descarga si el catálogo ya está disponible.
                    if (data[sec1] == null) {
                        try {
                            val list = withContext(Dispatchers.IO) {
                                Xt.catalog(s1.base, s1.user, s1.pass, sec1)
                            }
                            data = data + (sec1 to list)
                        } catch (e: Exception) {
                            if (error.isBlank()) {
                                error = "No se pudo cargar ${listOf("canales", "películas", "series")[sec1]}: ${e.message}"
                            }
                        }
                    }
                }
            }.joinAll()
        }
    }

    val cur = playing
    val ser = series
    val s = session
    val sec = section

    Box(Modifier.fillMaxSize()) {
        when {
            splash -> SplashScreen()
            movieDetail != null -> MovieDetailScreen(
                item = movieDetail!!,
                onPlay = { playing = movieDetail!!.name to movieDetail!!.url; playerVisible = true },
                onBack = { movieDetail = null }
            )
            ser != null -> EpisodesScreen(
                ser.name, ser.icon, seriesInfo, episodes,
                onPlay = { episode ->
                    playing = episode.title to episode.url
                    playerVisible = true
                },
                onBack = {
                    series = null
                    episodes = null
                    seriesInfo = null
                }
            )
            s == null -> LoginScreen(loginBusy, loginError, prefs) { sv, u, p -> login(sv, u, p) }
            sec == null -> HomeDashboard(
                account = s.account,
                user = s.user,
                onOpen = { section = it },
                onReload = { reloadAll() },
                onLogout = {
                    playerVisible = false
                    playing = null
                    session = null
                    data = emptyMap()
                    section = null
                }
            )
            else -> SectionScreen(
                section = sec,
                catalog = data[sec],
                error = error,
                paused = playerVisible,
                favs = favs,
                cat = cats[sec] ?: "Todos",
                onCat = { cats = cats + (sec to it) },
                q = queries[sec] ?: "",
                onQ = { queries = queries + (sec to it) },
                onOpen = { item ->
                    if (item.seriesId.isNotBlank()) {
                        series = item
                        episodes = null
                        seriesInfo = null
                        scope.launch {
                            val result = try { withContext(Dispatchers.IO) { Xt.seriesInfo(s.base, s.user, s.pass, item.seriesId) } }
                            catch (_: Exception) { SeriesInfo(episodes = emptyList()) }
                            seriesInfo = result
                            episodes = result.episodes
                        }
                    } else if (sec == 1) {
                        movieDetail = item
                        scope.launch {
                            movieDetail = try { withContext(Dispatchers.IO) { Xt.vodInfo(s.base, s.user, s.pass, item.id, item) } }
                            catch (_: Exception) { item }
                        }
                    } else {
                        playing = item.name to item.url
                        playerVisible = true
                    }
                },
                onFav = { key ->
                    favs = if (key in favs) favs - key else favs + key
                    prefs.edit().putStringSet("favs", favs).apply()
                },
                onBack = { section = null }@Composable
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
    paused: Boolean = false,
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
                        PreviewPane(selected, Modifier.width(340.dp).fillMaxHeight(), paused) {
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
                        // En celulares, conservar el canal seleccionado en una vista previa
                        // superior y mantener debajo la lista de la carpeta activa.
                        PreviewPane(
                            selected,
                            Modifier.fillMaxWidth(),
                            paused
                        ) {
                            selected?.let { onOpen(it) }
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.weight(1f)) {
                            items(shown) { ch ->
                                LiveRow(
                                    ch,
                                    ch.key in favs,
                                    ch.key == selected?.key,
                                    {
                                        if (selected?.key == ch.key) onOpen(ch)
                                        else selected = ch
                                    },
                                    { onFav(ch.key) }
                                )
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
    ) {                    g, Modifier.weight(1f), maxLines = 1,
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
fun PreviewPane(item: Item?, modifier: Modifier, paused: Boolean, onFull: () -> Unit) {
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
                PreviewPlayer(item.url, Modifier.fillMaxSize(), paused)
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
fun PreviewPlayer(url: String, modifier: Modifier = Modifier, paused: Boolean = false) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build().apply { playWhenReady = true } }
    LaunchedEffect(url) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
    }
    LaunchedEffect(paused) {
        if (paused) {
            player.pause()
        } else {
            player.seekToDefaultPosition()
            player.play()
        }
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
fun PosterCard(item: Item, fav: Boolean, onClick: () -> Unit, onFav: () -> Unit) {
    Column(
        Modifier.tvFocus(RoundedCornerShape(12.dp), onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box {
            AsyncImage(
                model = item.icon,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.68f)
                    .clip(RoundedCornerShape(12.dp))
            )
            TextButton(onClick = onFav, modifier = Modifier.align(Alignment.TopEnd)) {
                Text(if (fav) "★" else "☆", color = Color.White)
            }
        }
        Text(
            item.name, Modifier.padding(7.dp),
            maxLines = 2, style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ---------------------------------------------------------------- Detalles

@Composable
fun MovieDetailScreen(item: Item, onPlay: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(Bg)) {
        AsyncImage(
            model = item.icon, contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.25f)
        )
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            TextButton(onClick = onBack) { Text("‹ Volver") }
            Text(item.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(item.group, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onPlay) { Text("▶  Reproducir") }
        }
    }
}

@Composable
fun EpisodesScreen(
    title: String,
    icon: String,
    info: SeriesInfo?,
    episodes: List<Episode>?,
    onPlay: (Episode) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("‹ Volver") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = icon, contentDescription = null,
                modifier = Modifier.size(90.dp).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(info?.plot ?: "", maxLines = 3, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(16.dp))
        when {
            episodes == null -> CircularProgressIndicator()
            episodes.isEmpty() -> Text("No hay episodios disponibles")
            else -> LazyColumn {
                items(episodes) { ep ->
                    ListItem(
                        headlineContent = { Text(ep.title) },
                        supportingContent = { Text(ep.seasonEpisode) },
                        modifier = Modifier.clickable { onPlay(ep) }
                    )
                }
            }
        }
    }
}
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
            )@Composable
fun PlayerScreen(url: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val trackSelector = remember {
        DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage("es")
                    .setPreferredTextLanguage("es")
            )
        }
    }
    val player = remember {
        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
                    .build()
            )
            .build()
    }
    var playbackError by remember { mutableStateOf("") }
    var showTracks by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        playbackError = ""
        try {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
                .setUserAgent("VLC/3.0.20")
            val mediaSourceFactory = DefaultMediaSourceFactory(context)
                .setDataSourceFactory(httpFactory)
            player.setMediaSource(mediaSourceFactory.createMediaSource(MediaItem.fromUri(url)))
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            playbackError = e.message ?: "No se pudo iniciar el video"
        }
    }

    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = "Error de reproducción: ${error.errorCodeName}"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    controllerAutoShow = true
                    controllerShowTimeoutMs = 5000
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        Row(
            Modifier.align(Alignment.TopStart).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onBack) { Text("‹ Volver") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { showTracks = true }) { Text("Audio / Subtítulos") }
        }
        if (playbackError.isNotEmpty()) {
            Text(
                playbackError,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
                    .background(Color(0xCC990000), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            )
        }
    }

    if (showTracks) {
        AlertDialog(
            onDismissRequest = { showTracks = false },
            title = { Text("Audio y subtítulos") },
            text = {
                Column {
                    TextButton(onClick = {
                        showTracks = false
                        TrackSelectionDialogBuilder(context, "Audio", player, C.TRACK_TYPE_AUDIO).build().show()
                    }) { Text("Seleccionar audio") }
                    TextButton(onClick = {
                        showTracks = false
                        TrackSelectionDialogBuilder(context, "Subtítulos", player, C.TRACK_TYPE_TEXT).build().show()
                    }) { Text("Seleccionar subtítulos") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTracks = false }) { Text("Cerrar") }
            }
        )
    }
}

// ---------------------------------------------------------------- Login

@Composable
fun LoginScreen(
    busy: Boolean,
    error: String,
    prefs: android.content.SharedPreferences,
    onGo: (String, String, String) -> Unit
) {
    var server by remember { mutableStateOf(prefs.getString("server", "") ?: "") }
    var user by remember { mutableStateOf(prefs.getString("user", "") ?: "") }
    var pass by remember { mutableStateOf(prefs.getString("pass", "") ?: "") }
    Box(
        Modifier.fillMaxSize().background(Bg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
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
                OutlinedTextField(
                    server, { server = it }, label = { Text("URL del servidor (http://host:puerto)") },
                    placeholder = { Text("http://ejemplo.com:8080") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
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
                        onGo(server.trim(), user.trim(), pass.trim())
                    },
                    enabled = !busy, modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Entrando…" else "Entrar") }
            }
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
        }

        // El reproductor solo existe mientras se ve en pantalla completa.
        // Al volver atrás se libera y el audio se detiene.
        if (!splash && cur != null && playerVisible) {
            PlayerScreen(
                url = cur.second,
                onBack = {
                    playerVisible = false
                    playing = null
                }
            )
        }
    }
}
