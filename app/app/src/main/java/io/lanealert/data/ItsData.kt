package io.lanealert.data

import android.content.Context
import io.lanealert.BuildConfig
import io.lanealert.core.Cruise
import io.lanealert.core.Incident
import io.lanealert.core.ItsParser
import io.lanealert.core.Scenario
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 앱에 넣어 둔 실제 공공 돌발 기록(2026-11-12 00:42 받음)과 시연 시나리오 */
object ScenarioRepo {
    data class Bundle(val fetchedAt: String, val scenarios: List<Scenario>)

    fun load(context: Context): Bundle {
        val txt = context.assets.open("its/scenarios.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        return parse(txt)
    }

    fun parse(txt: String): Bundle {
        val o = JSONObject(txt)
        val arr = o.getJSONArray("scenarios")
        val list = (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            Scenario(
                id = s.getString("id"),
                title = s.getString("title"),
                record = recordMap(s.getJSONObject("record")),
                startDistanceM = s.getDouble("startDistanceM"),
                speedKmh = s.getDouble("speedKmh"),
                cruise = Cruise.valueOf(s.getString("cruise")),
                cruiseTargetKmh = s.optDouble("cruiseTargetKmh").takeIf { !it.isNaN() },
                congestionBeforeM = s.optDouble("congestionBeforeM").takeIf { !it.isNaN() },
                congestionAvgKmh = s.optDouble("congestionAvgKmh").takeIf { !it.isNaN() },
            )
        }
        return Bundle(o.getString("fetchedAt"), list)
    }

    fun recordMap(r: JSONObject): Map<String, String?> =
        r.keys().asSequence().associateWith { k -> if (r.isNull(k)) null else r.get(k).toString() }
}

/**
 * ITS 국가교통정보센터 돌발상황정보 오픈API 실시간 조회.
 * 키는 local.properties(its.apiKey) → BuildConfig 로만 들어온다. 저장소·화면·로그에 키를 남기지 않는다.
 */
object ItsLiveClient {
    val hasKey: Boolean get() = BuildConfig.ITS_API_KEY.isNotBlank()

    /** 전국 고속도로 돌발 기록. 네트워크 스레드에서 부를 것 */
    fun fetchExpressway(): List<Incident> {
        require(hasKey) { "ITS 키 없음" }
        val q = linkedMapOf(
            "apiKey" to BuildConfig.ITS_API_KEY, "type" to "ex", "eventType" to "all",
            "minX" to "124", "maxX" to "132", "minY" to "33", "maxY" to "39", "getType" to "json",
        ).entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val conn = URL("https://openapi.its.go.kr:9443/eventInfo?$q").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 15000
        try {
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val items: JSONArray = JSONObject(body).getJSONObject("body").getJSONArray("items")
            return (0 until items.length()).map { ItsParser.parse(ScenarioRepo.recordMap(items.getJSONObject(it))) }
        } finally {
            conn.disconnect()
        }
    }
}
