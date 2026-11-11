package io.lanealert.core

import kotlin.math.roundToInt

/**
 * 차 안 짧은 음성 안내 규칙. 파이썬 참조 구현 speak/announce.py(시험 통과판)를 Kotlin으로 옮김.
 *
 * 원칙
 * - 차로를 바꾸라고 말하지 않는다. "2차로 통제"까지만.
 * - 한두 문장. 운전과 관련된 알림만.
 * - 사건이 내 경로와 관련 있고, 활성이고, 신선할 때만 말한다. 공공 차로 정보는 추가 조건을 통과할 때만 붙인다.
 * - 같은 사건은 내용이 바뀌지 않으면 다시 말하지 않는다. 실제로 말한 뒤(TTS 성공)에만 '말함'으로 기록한다.
 * - 크루즈를 믿어도 된다, 앞차를 인식했다는 말은 하지 않는다.
 */
class Announcer {

    companion object {
        // 아래 문턱값은 전부 [가정]. 에뮬레이터·실차 시험으로 정한다.
        const val MAX_EVENT_AGE_S = 900.0
        const val MIN_ROUTE_CONF = 0.8
        const val MAX_PUBLIC_AGE_S = 600.0
        const val MIN_MATCH_CONF = 0.7
        const val MIN_RESIDUAL_AT_RISK = 20
        const val TAIL_MAX_TIME_TO_REACH_S = 45.0
        const val TAIL_MIN_SPEED_GAP_KMH = 30.0
        val TAIL_DECEL_WINDOW_S = 1.0..5.0

        val EVENT_KO = mapOf(
            "ACCIDENT" to "사고", "ROAD_DAMAGE" to "도로 파손", "REPAIRING" to "공사",
            "EVENT" to "행사", "DISASTER" to "재난", "ETC" to "돌발 상황",
        )
    }

    /** 내비(또는 재생)에서 온 경로 앞 사건 */
    data class RouteEvent(
        val id: String,
        val type: String,
        val offsetM: Double,
        val ageS: Double,
        val routeConf: Double,
        val active: Boolean,
        /** 공공 기록에서 온 더 구체적인 이름(예: "고장차"). 없으면 type 으로 정함 */
        val name: String? = null,
    )

    /** 같은 사건으로 맞춰진 공공 돌발 기록의 차로 정보 */
    data class PublicLanes(
        val lanes: List<Int>,
        val ageS: Double,
        val matchConf: Double,
        val scope: Incident.LaneScope = Incident.LaneScope.MAINLINE,
    )

    /** 잔여 시간 모델 결과. 검증된 모델일 때만 validated = true (지금은 실데이터 검증 0건이라 쓰지 않음) */
    data class Residual(val q25: Double, val q75: Double, val nAtRisk: Int, val validated: Boolean)

    data class Utterance(val key: String, val content: String, val text: String)

    private val said = HashMap<String, String>()

    private fun shouldSay(key: String, content: String): Boolean = said[key] != content

    /** TTS가 실제로 말한 뒤 호출 */
    fun confirm(u: Utterance) { said[u.key] = u.content }

    /** 사건 종료·취소 시 상태 제거 */
    fun endEvent(routeId: String, eventId: String) {
        said.keys.removeAll { it.startsWith("$routeId|$eventId|") }
    }

    fun reset() = said.clear()

    private fun finite(vararg xs: Double) = xs.all { it.isFinite() }

    fun eventUtterance(routeId: String, ev: RouteEvent?, pub: PublicLanes? = null, res: Residual? = null): Utterance? {
        if (ev == null || !ev.active) return null
        if (!finite(ev.offsetM, ev.ageS, ev.routeConf)) return null
        if (ev.offsetM <= 0 || ev.ageS > MAX_EVENT_AGE_S || ev.routeConf < MIN_ROUTE_CONF) return null

        val name = ev.name ?: EVENT_KO[ev.type] ?: "돌발 상황"
        val parts = mutableListOf("${KoText.km(ev.offsetM)} 앞 $name")
        val content = mutableListOf(ev.type, name)

        if (pub != null && finite(pub.ageS, pub.matchConf) &&
            pub.ageS <= MAX_PUBLIC_AGE_S && pub.matchConf >= MIN_MATCH_CONF
        ) {
            if (pub.scope == Incident.LaneScope.MAINLINE) {
                KoText.laneControl(pub.lanes)?.let { parts += it; content += it }
            } else if (pub.scope == Incident.LaneScope.RAMP_ENTRY || pub.scope == Incident.LaneScope.RAMP_EXIT ||
                pub.scope == Incident.LaneScope.SHOULDER
            ) {
                parts += "본선 차로 통제 기록은 없어요"
                content += "no-mainline"
            }
            val r = res
            if (r != null && r.validated && finite(r.q25, r.q75) && r.q25 > 0 && r.q25 <= r.q75 &&
                r.nAtRisk >= MIN_RESIDUAL_AT_RISK && pub.scope == Incident.LaneScope.MAINLINE
            ) {
                val a = r.q25.roundToInt(); val b = r.q75.roundToInt()
                parts += "비슷한 ${KoText.topic(name)} 보통 $a-${b}분 더 이어졌어요"
                content += "$a-$b"
            }
        }
        val key = "$routeId|${ev.id}|event"
        val c = content.joinToString("|")
        if (!shouldSay(key, c)) return null
        val text = parts.joinToString(". ") + "."
        check(KoText.isSafe(text)) { text }
        return Utterance(key, c, text)
    }

    /**
     * 크루즈가 작동 중이고, 정체 시작 지점에 내 속도로 곧 닿는데, 실제 속도가 줄지 않을 때만.
     * 도달 시간 = 거리 / 내 속도 (정체 꼬리를 고정 위치로 보는 보수적 정의).
     * 감속 판단은 브레이크 페달이 아니라 속도 변화로 한다(크루즈·회생제동도 감속하므로).
     */
    fun tailUtterance(
        routeId: String, congestionId: String, cruise: Cruise,
        speedMps: Double, prevSpeedMps: Double, dtS: Double, offsetM: Double, avrSpeedKmh: Double,
    ): Utterance? {
        if (cruise != Cruise.ACTIVE) return null
        if (!finite(speedMps, prevSpeedMps, dtS, offsetM, avrSpeedKmh)) return null
        if (offsetM <= 0 || speedMps <= 0 || dtS !in TAIL_DECEL_WINDOW_S) return null
        if (speedMps * 3.6 - avrSpeedKmh < TAIL_MIN_SPEED_GAP_KMH) return null
        if (offsetM / speedMps > TAIL_MAX_TIME_TO_REACH_S) return null
        val decel = (prevSpeedMps - speedMps) / dtS
        if (decel > 0.5) return null
        val key = "$routeId|$congestionId|tail"
        if (!shouldSay(key, "tail")) return null
        val text = "${KoText.km(offsetM)} 앞부터 정체가 시작돼요. 크루즈가 켜져 있어요."
        check(KoText.isSafe(text)) { text }
        return Utterance(key, "tail", text)
    }
}
