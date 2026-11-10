package com.interfree.tv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Dirección Xtream. Vacía para solicitarla en el inicio de sesión. */
const val SERVER_URL = ""

data class Item(
    val id: String,
    val name: String,
    val group: String,
    val url: String,
    val seriesId: String = "",
    val icon: String = "",
    val plot: String = "",
    val year: String = "",
    val genre: String = "",
    val duration: String = "",
    val rating: String = "",
    val sortOrder: Int = Int.MAX_VALUE
) {
    val key: String get() = url.ifBlank { "s$seriesId" }
}

data class Episode(
    val title: String,
    val url: String,
    val icon: String = "",
    val plot: String = "",
    val duration: String = "",
    val rating: String = "",
    val season: String = "",
    val episodeNumber: String = ""
)

data class SeriesInfo(val plot: String = "", val genre: String = "", val year: String = "", val rating: String = "", val episodes: List<Episode>)
data class Account(val status: String, val expiry: String, val maxCon: String, val activeCon: String)

object Xt {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun clean(o: JSONObject?, vararg keys: String): String {
        if (o == null) return ""
        for (key in keys) {
            val v = o.optString(key, "").trim()
            if (v.isNotEmpty() && v != "null" && v != "0") return v
        }
        return ""
    }
    private fun http(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 40000
        c.setRequestProperty("User-Agent", "InterfreeTV/1.1")
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }
    fun base(server: String): String {
        var s = server.trim().trimEnd('/')
        if (!s.startsWith("http")) s = "http://$s"
        return s
    }
    private fun api(b: String, u: String, p: String, extra: String = ""): String =
        http("$b/player_api.php?username=${enc(u)}&password=${enc(p)}$extra")

    fun login(b: String, u: String, p: String): Account {
        val info = JSONObject(api(b, u, p)).optJSONObject("user_info") ?: throw Exception("Respuesta no válida del servidor")
        if (info.optInt("auth", 0) != 1) throw Exception("Usuario o contraseña incorrectos")
        val exp = info.optString("exp_date")
        val expiry = try { if (exp.isBlank() || exp == "null") "Ilimitado" else SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(exp.toLong() * 1000)) } catch (_: Exception) { exp }
        return Account(info.optString("status"), expiry, info.optString("max_connections"), info.optString("active_cons"))
    }

    fun catalog(b: String, u: String, p: String, section: Int): List<Item> {
        val kind = when (section) { 0 -> "live"; 1 -> "vod"; else -> "series" }
        val cats = JSONArray(api(b, u, p, "&action=get_${kind}_categories"))
        val names = HashMap<String, String>()
        for (i in 0 until cats.length()) { val o = cats.getJSONObject(i); names[o.optString("category_id")] = o.optString("category_name") }
        val action = if (section == 2) "get_series" else "get_${kind}_streams"
        val arr = JSONArray(api(b, u, p, "&action=$action"))
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i); val group = names[o.optString("category_id")] ?: "Otros"
            when (section) {
                0 -> {
                    val id = o.optString("stream_id")
                    // Xtream suele enviar "num" con la posición/número original del canal.
                    // Si falta o no es numérico, se conserva el orden recibido del servidor.
                    val providerOrder = o.optString("num", "").toIntOrNull()
                        ?: o.optString("order", "").toIntOrNull()
                        ?: (i + 1)
                    out.add(
                        Item(
                            id, o.optString("name"), group,
                            "$b/live/${enc(u)}/${enc(p)}/$id.m3u8",
                            icon = clean(o, "stream_icon"),
                            sortOrder = providerOrder
                        )
                    )
                }
                1 -> { val id = o.optString("stream_id"); val ext = clean(o, "container_extension").ifBlank { "mp4" }; out.add(Item(id, o.optString("name"), group, "$b/movie/${enc(u)}/${enc(p)}/$id.$ext", icon = clean(o, "stream_icon", "movie_image"), plot = clean(o, "plot"), year = clean(o, "releasedate", "year"), genre = clean(o, "genre"), duration = clean(o, "duration"), rating = clean(o, "rating"))) }
                else -> { val id = o.optString("series_id"); out.add(Item(id, o.optString("name"), group, "", id, clean(o, "cover", "cover_big"), plot = clean(o, "plot"), year = clean(o, "releaseDate", "year"), genre = clean(o, "genre"), rating = clean(o, "rating"))) }
            }
        }
        // Solo TV en vivo usa el número de orden enviado por el proveedor.
        // Películas y series mantienen exactamente el orden recibido.
        return if (section == 0) out.sortedBy { it.sortOrder } else out
    }

    /** Datos completos de una película: Xtream suele entregar sinopsis en info.plot. */
    fun vodInfo(b: String, u: String, p: String, id: String, fallback: Item): Item {
        val root = JSONObject(api(b, u, p, "&action=get_vod_info&vod_id=${enc(id)}"))
        val info = root.optJSONObject("info") ?: JSONObject()
        val movie = root.optJSONObject("movie_data") ?: JSONObject()
        return fallback.copy(
            icon = clean(info, "movie_image", "cover_big").ifBlank { clean(movie, "stream_icon", "movie_image") }.ifBlank { fallback.icon },
            plot = clean(info, "plot", "description").ifBlank { fallback.plot },
            year = clean(info, "releasedate", "releaseDate", "year").ifBlank { fallback.year },
            genre = clean(info, "genre").ifBlank { fallback.genre },
            duration = clean(info, "duration", "duration_secs").ifBlank { fallback.duration },
            rating = clean(info, "rating").ifBlank { fallback.rating }
        )
    }

    /** Información de serie y episodios agrupados por temporada desde get_series_info. */
    fun seriesInfo(b: String, u: String, p: String, seriesId: String): SeriesInfo {
        val root = JSONObject(api(b, u, p, "&action=get_series_info&series_id=${enc(seriesId)}"))
        val info = root.optJSONObject("info") ?: JSONObject()
        val eps = root.optJSONObject("episodes") ?: JSONObject()
        val out = ArrayList<Episode>()
        val seasons = eps.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: 0 }
        for (season in seasons) {
            val arr = eps.optJSONArray(season) ?: continue
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val id = clean(e, "id"); if (id.isBlank()) continue
                val ei = e.optJSONObject("info") ?: JSONObject()
                val ext = clean(e, "container_extension").ifBlank { "mp4" }
                val num = clean(e, "episode_num").ifBlank { (i + 1).toString() }
                val name = clean(e, "title").ifBlank { "Episodio $num" }
                out.add(Episode(
                    "T$season · E$num · $name",
                    "$b/series/${enc(u)}/${enc(p)}/$id.$ext",
                    icon = clean(ei, "movie_image", "cover_big", "cover").ifBlank { clean(e, "movie_image", "cover") },
                    plot = clean(ei, "plot", "description"), duration = clean(ei, "duration", "duration_secs"),
                    rating = clean(ei, "rating"), season = season, episodeNumber = num
                ))
            }
        }
        return SeriesInfo(clean(info, "plot", "description", "overview"), clean(info, "genre"), clean(info, "releaseDate", "year"), clean(info, "rating"), out)
    }

    fun episodes(b: String, u: String, p: String, seriesId: String): List<Episode> = seriesInfo(b, u, p, seriesId).episodes
}
