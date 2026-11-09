package com.interfree.tv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Dirección de tu servidor Xtream, por ejemplo "http://tuservidor.com:8080".
 *  Si la dejas vacía, la app la pide en la pantalla de inicio de sesión. */
const val SERVER_URL = ""

data class Item(
    val id: String,
    val name: String,
    val group: String,
    val url: String,
    val seriesId: String = "",
    val icon: String = ""
) {
    val key: String get() = url.ifBlank { "s$seriesId" }
}

data class Episode(val title: String, val url: String)

data class Account(val status: String, val expiry: String, val maxCon: String, val activeCon: String)

object Xt {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun http(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 40000
        c.setRequestProperty("User-Agent", "InterfreeTV/1.1")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    fun base(server: String): String {
        var s = server.trim().trimEnd('/')
        if (!s.startsWith("http")) s = "http://$s"
        return s
    }

    private fun api(b: String, u: String, p: String, extra: String = ""): String =
        http("$b/player_api.php?username=${enc(u)}&password=${enc(p)}$extra")

    /** Valida usuario y contraseña y devuelve los datos de la cuenta. */
    fun login(b: String, u: String, p: String): Account {
        val info = JSONObject(api(b, u, p)).optJSONObject("user_info")
            ?: throw Exception("Respuesta no válida del servidor")
        if (info.optInt("auth", 0) != 1) throw Exception("Usuario o contraseña incorrectos")
        val exp = info.optString("exp_date")
        val expiry = try {
            if (exp.isBlank() || exp == "null") "Ilimitado"
            else SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(exp.toLong() * 1000))
        } catch (e: Exception) { exp }
        return Account(
            info.optString("status"), expiry,
            info.optString("max_connections"), info.optString("active_cons")
        )
    }

    /** section: 0 = en vivo, 1 = películas, 2 = series. */
    fun catalog(b: String, u: String, p: String, section: Int): List<Item> {
        val kind = when (section) { 0 -> "live"; 1 -> "vod"; else -> "series" }
        val cats = JSONArray(api(b, u, p, "&action=get_${kind}_categories"))
        val names = HashMap<String, String>()
        for (i in 0 until cats.length()) {
            val o = cats.getJSONObject(i)
            names[o.optString("category_id")] = o.optString("category_name")
        }
        val action = if (section == 2) "get_series" else "get_${kind}_streams"
        val arr = JSONArray(api(b, u, p, "&action=$action"))
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val group = names[o.optString("category_id")] ?: "Otros"
            when (section) {
                0 -> {
                    val id = o.optString("stream_id")
                    out.add(Item(id, o.optString("name"), group, "$b/live/${enc(u)}/${enc(p)}/$id.m3u8", icon = o.optString("stream_icon")))
                }
                1 -> {
                    val id = o.optString("stream_id")
                    val ext = o.optString("container_extension").ifBlank { "mp4" }
                    out.add(Item(id, o.optString("name"), group, "$b/movie/${enc(u)}/${enc(p)}/$id.$ext", icon = o.optString("stream_icon")))
                }
                else -> {
                    val id = o.optString("series_id")
                    out.add(Item(id, o.optString("name"), group, "", id, o.optString("cover")))
                }
            }
        }
        return out
    }

    fun episodes(b: String, u: String, p: String, seriesId: String): List<Episode> {
        val root = JSONObject(api(b, u, p, "&action=get_series_info&series_id=${enc(seriesId)}"))
        val eps = root.optJSONObject("episodes") ?: return emptyList()
        val out = ArrayList<Episode>()
        val seasons = eps.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: 0 }
        for (s in seasons) {
            val arr = eps.optJSONArray(s) ?: continue
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val id = e.optString("id")
                val ext = e.optString("container_extension").ifBlank { "mp4" }
                out.add(
                    Episode(
                        "T$s · E${e.optString("episode_num")} · ${e.optString("title")}",
                        "$b/series/${enc(u)}/${enc(p)}/$id.$ext"
                    )
                )
            }
        }
        return out
    }
}
