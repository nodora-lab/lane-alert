package io.lanealert.core

import java.time.LocalDateTime

/**
 * 실시간 모드: 내비(NaviHelper)가 "내 경로 위 사건"이라고 준 좌표와 공공 돌발 기록을 맞춘다.
 * - 앞 사건 여부는 내비가 정한다(경로 위 사건 목록). 공공 기록은 차로 정보만 붙인다.
 * - 내비 사건과 300m 안에서 맞는 공공 기록이 없으면 차로를 말하지 않는다.
 * - 내비 경로 위 사건이 하나도 없으면, 공공 기록은 "주변 기록"으로만 보여 주고 음성 안내는 하지 않는다.
 */
object NaviMatch {
    /** 내비가 경로 위 사건이라고 준 경우의 확신 [가정] */
    const val ROUTE_CONF = 0.9

    data class NaviEvent(val type: String, val lat: Double, val lon: Double)
    data class NaviCongestion(val offsetM: Double, val avgKmh: Double)

    /** 내 위치에서 가장 가까운 '경로 위 사건'에 공공 기록을 붙여 AheadState 로 만든다. 없으면 null */
    fun aheadFromNavi(
        myLat: Double, myLon: Double,
        events: List<NaviEvent>, congestions: List<NaviCongestion>,
        incidents: List<Incident>, observedAgeS: Double, now: LocalDateTime,
    ): AheadState? {
        val nearest = events.minByOrNull { IncidentMatcher.distanceM(myLat, myLon, it.lat, it.lon) } ?: return null
        val dist = IncidentMatcher.distanceM(myLat, myLon, nearest.lat, nearest.lon)
        val m = IncidentMatcher.match(nearest.lat, nearest.lon, incidents) ?: return null
        val inc = m.first
        val tail = congestions.filter { it.offsetM in 0.0..dist }.minByOrNull { it.offsetM }
        return AheadState(
            routeId = "navi",
            incident = inc,
            distanceM = dist,
            observedAgeS = observedAgeS,
            matchConf = m.second,
            congestionId = tail?.let { "navi-tail-${it.offsetM.toInt() / 100}" },
            congestionOffsetM = tail?.offsetM,
            congestionAvgKmh = tail?.avgKmh,
            routeConf = ROUTE_CONF,
            active = inc.endAt == null || inc.endAt.isAfter(now),
        )
    }
}
