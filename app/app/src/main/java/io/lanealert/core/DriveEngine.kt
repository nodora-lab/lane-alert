package io.lanealert.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 한 순간의 차량 신호 (Pleos Vehicle SDK·AAOS 차속, 또는 재생) */
data class VehicleState(
    val speedMps: Double,
    val cruise: Cruise,
    val cruiseTargetKmh: Double?,
)

/** 경로 앞 사건 하나와 그 앞 정체 꼬리 (NaviHelper TrafficStatus, 또는 재생) */
data class AheadState(
    val routeId: String,
    val incident: Incident,
    val distanceM: Double,
    /** 공공 기록을 받은 뒤 지난 초 */
    val observedAgeS: Double,
    /** 내비 사건과 공공 기록이 같은 사건이라는 확신 (재생에선 1.0) */
    val matchConf: Double,
    val congestionId: String?,
    val congestionOffsetM: Double?,
    val congestionAvgKmh: Double?,
    /** 사건이 내 경로 앞이라는 확신. 재생 = 1.0, 내비 경로 사건과 맞춘 경우 = [NaviMatch.ROUTE_CONF] */
    val routeConf: Double = 1.0,
    /** 기록상 끝난 사건이면 false (종료 시각이 관측 시각보다 이르면) */
    val active: Boolean = true,
)

/**
 * 매 초 차량 신호와 앞 사건을 받아, 지금 말할 문장을 고른다.
 * 말할 시점은 [AlertTiming], 말할 내용과 반복 방지는 [Announcer].
 */
class DriveEngine(val announcer: Announcer = Announcer()) {

    private var prevSpeed: Double? = null
    private var prevT: Double? = null

    fun reset() { announcer.reset(); prevSpeed = null; prevT = null }

    fun step(tS: Double, v: VehicleState, a: AheadState?): List<Announcer.Utterance> {
        val out = mutableListOf<Announcer.Utterance>()
        val ps = prevSpeed; val pt = prevT
        prevSpeed = v.speedMps; prevT = tS
        if (a == null) return out

        // 1) 크루즈 작동 중 정체 꼬리 접근 (1초 이상 간격의 두 속도로 감속 여부 판단)
        if (a.congestionOffsetM != null && a.congestionAvgKmh != null && ps != null && pt != null) {
            announcer.tailUtterance(
                a.routeId, a.congestionId ?: "c", v.cruise, v.speedMps, ps, tS - pt,
                a.congestionOffsetM, a.congestionAvgKmh,
            )?.let { out += it }
        }
        // 2) 사건 안내: 도달 시간이 문턱 안에 들어왔을 때
        if (AlertTiming.isDue(a.distanceM, v.speedMps, v.cruise)) {
            val inc = a.incident
            val ev = Announcer.RouteEvent(
                id = inc.id, type = inc.kind.name, offsetM = a.distanceM, ageS = a.observedAgeS,
                routeConf = a.routeConf, active = a.active, name = inc.kindKo,
            )
            val pub = Announcer.PublicLanes(inc.lanes, a.observedAgeS, a.matchConf, inc.scope)
            announcer.eventUtterance(a.routeId, ev, pub)?.let { out += it }
        }
        return out
    }
}

/** 내비가 준 사건 좌표와 공공 돌발 기록을 같은 사건으로 맞춘다 */
object IncidentMatcher {
    const val MAX_MATCH_M = 300.0

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = (lat2 - lat1) * PI / 180
        val dLon = (lon2 - lon1) * PI / 180
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(h))
    }

    /** 가장 가까운 공공 기록과 확신(0-1). [MAX_MATCH_M] 밖이면 null */
    fun match(lat: Double, lon: Double, incidents: List<Incident>): Pair<Incident, Double>? {
        val best = incidents.filter { it.lat != null && it.lon != null }
            .map { it to distanceM(lat, lon, it.lat!!, it.lon!!) }
            .minByOrNull { it.second } ?: return null
        if (best.second > MAX_MATCH_M) return null
        return best.first to (1.0 - best.second / MAX_MATCH_M * 0.5)
    }
}
