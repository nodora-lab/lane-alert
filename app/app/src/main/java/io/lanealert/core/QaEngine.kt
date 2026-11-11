package io.lanealert.core

/**
 * "앞에 왜 막혀? 몇 차로야?" 에 확인된 사실로만 1-2문장으로 답한다.
 * 질문 이해는 규칙(낱말)으로 한다. 사실·숫자·차로는 공공 기록과 차량 신호에서만 가져온다.
 * 답하지 않는 것: 어느 차로가 빠른지, 어느 차로로 가야 하는지, 크루즈를 믿어도 되는지.
 * 기록에 없는 상태("처리 중", "해제됨")를 사건 종류로 짐작해 말하지 않는다. 기록 문구를 그대로 옮긴다.
 */
object QaEngine {

    enum class Intent { WHY, LANES, DISTANCE, WHEN_CLEAR, FASTEST_LANE, CRUISE, TRUST, UNKNOWN }

    /** 사건이 내 경로 앞이라고 확인됐는지 */
    enum class Relation { AHEAD, NEARBY }

    data class Context(
        val incident: Incident?,
        val distanceM: Double?,
        /** 근거 표시: 예) "공공 돌발정보 11-12 00:42 기록(재생)" */
        val recordLabel: String,
        val cruise: Cruise,
        val cruiseTargetKmh: Double?,
        /** 크루즈 값 출처: "재생" 또는 "Pleos Vehicle SDK" */
        val cruiseSource: String = "재생",
        val relation: Relation = Relation.AHEAD,
        /** 받은 지 오래된 기록이면 false → 통제 차로를 지금 사실처럼 말하지 않는다 */
        val recordFresh: Boolean = true,
        /** 기록상 끝난 사건이면 false */
        val recordActive: Boolean = true,
    )

    data class Answer(val intent: Intent, val text: String, val basis: String)

    private fun norm(q: String) = q.replace(" ", "").replace("?", "").replace("？", "")

    fun classify(question: String): Intent {
        val q = norm(question)
        fun has(vararg w: String) = w.any { it in q }
        return when {
            has("믿어", "믿을", "안전해", "맡겨", "괜찮아") -> Intent.TRUST
            has("빨라", "빠른", "빠르", "어디로가", "어느차로로", "어느쪽", "피해", "추천") -> Intent.FASTEST_LANE
            has("크루즈", "설정속도", "스마트크루즈") -> Intent.CRUISE
            has("언제풀", "언제끝", "언제까지", "얼마나걸", "풀려", "끝나") -> Intent.WHEN_CLEAR
            has("몇차로", "차로", "통제") -> Intent.LANES
            has("얼마나남", "몇키로", "몇km", "거리", "남았", "얼마나가") -> Intent.DISTANCE
            has("왜", "무슨일", "뭐야", "무슨", "막혀", "막혔", "뭔데") -> Intent.WHY
            else -> Intent.UNKNOWN
        }
    }

    private fun where(ctx: Context): String = when (ctx.relation) {
        Relation.AHEAD -> ctx.distanceM?.let { "${KoText.km(it)} 앞" } ?: "앞"
        Relation.NEARBY -> "주변 기록상"
    }

    private var gate: Context? = null

    /** 통제 차로를 지금 사실로 말해도 되는가: 신선하고, 끝나지 않은 기록일 때만 (모든 질문에 같은 검사) */
    private fun lanesFact(inc: Incident): String? {
        val c = gate
        if (c != null && (!c.recordFresh || !c.recordActive)) return null
        return if (inc.hasMainlineLanes) "${KoText.lanes(inc.lanes)} 통제" else null
    }

    fun answer(question: String, ctx: Context): Answer = answer(classify(question), ctx)

    fun answer(intent: Intent, ctx: Context): Answer {
        gate = ctx
        val inc = ctx.incident
        val nearbyTail = if (ctx.relation == Relation.NEARBY) " 내 경로 앞인지는 확인 전이에요." else ""
        val text: String = when (intent) {
            Intent.TRUST -> "그 질문엔 답하지 않아요. 제가 알려 드리는 건 앞 사건과 통제 차로예요."
            Intent.CRUISE -> when (ctx.cruise) {
                Cruise.ACTIVE -> "크루즈는 작동 중이에요." + (ctx.cruiseTargetKmh?.let { " 설정 속도는 ${it.toInt()}km/h예요." } ?: "")
                Cruise.STANDBY -> "크루즈는 대기 상태예요."
                Cruise.OFF -> "크루즈는 꺼져 있어요."
                else -> "크루즈 상태는 지금 ${ctx.cruise.ko}이에요."
            }
            else -> if (inc == null) "지금 확인된 돌발 기록은 없어요." else when (intent) {
                Intent.LANES ->
                    if (!ctx.recordActive) "기록상 이미 끝난 사건이에요."
                    else if (!ctx.recordFresh) "받은 지 오래된 기록이라 지금도 통제 중인지는 확인이 필요해요."
                    else laneAnswer(inc, ctx) + nearbyTail
                Intent.WHY -> whyAnswer(inc, ctx) + nearbyTail
                Intent.DISTANCE -> when {
                    ctx.relation == Relation.NEARBY -> "내 경로 위 거리는 아직 확인 전이에요."
                    ctx.distanceM != null -> "${inc.kindKo} 지점까지 ${KoText.km(ctx.distanceM)} 남았어요."
                    else -> "사건 지점까지 거리는 지금 알 수 없어요."
                }
                Intent.WHEN_CLEAR -> {
                    val end = inc.endClock(); val start = inc.startClock()
                    when {
                        end != null -> "기록상 끝나는 시각은 ${end}이에요."
                        start != null -> "끝나는 시각은 기록에 없어요. 시작 시각은 ${start}이에요."
                        else -> "끝나는 시각은 기록에 없어요."
                    }
                }
                Intent.FASTEST_LANE -> "차로별 속도는 확인할 수 없어요. " + factLine(inc, ctx)
                else -> "제가 답할 수 있는 건 앞 사건, 통제 차로, 남은 거리예요."
            }
        }
        check(KoText.isSafe(text)) { text }
        val basis = when (intent) {
            Intent.CRUISE -> "근거: 크루즈 신호(${ctx.cruiseSource})"
            Intent.TRUST, Intent.UNKNOWN -> ""
            else -> if (inc == null) "" else "근거: ${ctx.recordLabel}"
        }
        return Answer(intent, text, basis)
    }

    /** "확인된 건 2km 앞 고장차 기록, 4차로 통제예요." */
    private fun factLine(inc: Incident, ctx: Context): String {
        val lanes = lanesFact(inc)
        return if (lanes != null) "확인된 건 ${where(ctx)} ${inc.kindKo} 기록, ${lanes}예요."
        else "확인된 건 ${where(ctx)} ${inc.kindKo} 기록이에요."
    }

    private fun laneAnswer(inc: Incident, ctx: Context): String = when (inc.scope) {
        Incident.LaneScope.MAINLINE ->
            if (inc.lanes.isEmpty()) "${where(ctx)} ${inc.kindKo} 기록이에요. 통제 차로는 기록에 없어요."
            else "${where(ctx)} ${inc.kindKo}, ${lanesFact(inc)}예요."
        Incident.LaneScope.RAMP_ENTRY, Incident.LaneScope.RAMP_EXIT, Incident.LaneScope.SHOULDER ->
            "${where(ctx)} ${inc.kindKo} 기록이에요. 본선 차로 통제 기록은 없어요."
        Incident.LaneScope.NONE -> "${where(ctx)} ${inc.kindKo} 기록이에요. 통제 차로는 기록에 없어요."
    }

    /** 기록 문구를 그대로 옮긴다: "2.5km 앞, 고장차 처리중이라는 기록이에요. 4차로 통제예요." */
    private fun whyAnswer(inc: Incident, ctx: Context): String {
        val msg = inc.plainMessage
        val first = if (msg.isNotBlank()) "${where(ctx)}, ${msg}${if (endsWithBatchim(msg)) "이라는" else "라는"} 기록이에요."
        else "${where(ctx)} ${inc.kindKo} 기록이에요."
        val lanes = lanesFact(inc)
        return if (lanes != null) "$first ${lanes}예요." else first
    }

    private fun endsWithBatchim(s: String): Boolean {
        val ch = s.lastOrNull() ?: return false
        return ch in '가'..'힣' && (ch.code - 0xAC00) % 28 != 0
    }
}
