package com.campusquest.app.logic

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class WeatherInfo(val icon: String, val text: String, val loc: String)

object Weather {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    private val WMO = mapOf(
        0 to ("晴" to "☀"), 1 to ("晴间多云" to "🌤"), 2 to ("多云" to "⛅"), 3 to ("阴" to "☁"),
        45 to ("雾" to "🌫"), 48 to ("雾" to "🌫"),
        51 to ("小毛毛雨" to "🌦"), 53 to ("毛毛雨" to "🌦"), 55 to ("大毛毛雨" to "🌦"),
        56 to ("冻毛毛雨" to "🌧"), 57 to ("冻毛毛雨" to "🌧"),
        61 to ("小雨" to "🌧"), 63 to ("中雨" to "🌧"), 65 to ("大雨" to "🌧"),
        66 to ("冻雨" to "🌧"), 67 to ("冻雨" to "🌧"),
        71 to ("小雪" to "❄"), 73 to ("中雪" to "❄"), 75 to ("大雪" to "❄"), 77 to ("雪粒" to "❄"),
        80 to ("小阵雨" to "🌦"), 81 to ("阵雨" to "🌧"), 82 to ("强阵雨" to "🌧"),
        85 to ("阵雪" to "❄"), 86 to ("阵雪" to "❄"),
        95 to ("雷阵雨" to "⛈"), 96 to ("雷阵雨" to "⛈"), 99 to ("强雷暴" to "⛈"),
    )

    private suspend fun get(url: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val r = client.newCall(Request.Builder().url(url).build()).execute()
            val body = r.body?.string()
            if (r.isSuccessful && body != null) JSONObject(body) else null
        }.getOrNull()
    }

    private suspend fun load(lat: Double, lon: Double, locName: String): WeatherInfo? {
        val j = get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1") ?: return null
        val daily = j.optJSONObject("daily") ?: return null
        val code = daily.optJSONArray("weather_code")?.optInt(0) ?: return null
        val hi = daily.optJSONArray("temperature_2m_max")?.optDouble(0)?.let { Math.round(it) } ?: return null
        val lo = daily.optJSONArray("temperature_2m_min")?.optDouble(0)?.let { Math.round(it) } ?: return null
        val w = WMO[code] ?: ("未知" to "◌")
        return WeatherInfo(w.second, "${w.first} $lo~${hi}°C", "· $locName")
    }

    private suspend fun geocode(city: String): Triple<Double, Double, String>? {
        val j = get("https://geocoding-api.open-meteo.com/v1/search?name=${java.net.URLEncoder.encode(city, "UTF-8")}&count=1&language=zh") ?: return null
        val g = j.optJSONArray("results")?.optJSONObject(0) ?: return null
        return Triple(g.getDouble("latitude"), g.getDouble("longitude"), g.optString("name", city))
    }

    @SuppressLint("MissingPermission")
    private fun gps(ctx: Context): Pair<Double, Double>? = runCatching {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val ok = lm.getProviders(true).any { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }
        if (!ok) return null
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        loc?.let { it.latitude to it.longitude }
    }.getOrNull()

    /** 定位优先级:手动城市 > GPS > IP 归属地 > 长沙校本部兜底 */
    suspend fun fetch(ctx: Context, city: String): WeatherInfo {
        if (city.isNotBlank()) {
            geocode(city)?.let { (la, lo, name) -> load(la, lo, name)?.let { return it } }
        }
        gps(ctx)?.let { (la, lo) -> load(la, lo, "当前位置")?.let { return it } }
        get("https://get.geojs.io/v1/ip/geo.json")?.let { j ->
            val la = j.optString("latitude").toDoubleOrNull()
            val lo = j.optString("longitude").toDoubleOrNull()
            if (la != null && lo != null) {
                load(la, lo, "${j.optString("city", "")}·IP定位")?.let { return it }
            }
        }
        load(28.2282, 112.9388, "长沙·校本部")?.let { return it }
        return WeatherInfo("◌", "天气暂不可用", "")
    }
}
