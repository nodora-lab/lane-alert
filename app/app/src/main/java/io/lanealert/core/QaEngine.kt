package io.lanealert.core

/**
 * "앞에 왜 막혀? 몇 차로야?" 에 확인된 사실로만 1-2문장으로 답한다.
 * 질문 이해는 규칙(낱말)으로 한다. 사실·숫자·차로는 공공 기록과 차량 신호에서만 가져온다.
 * 답하지 않는 것: 어느 차로가 빠른지, 어느 차로로 가야 하는지, 크루즈를 믿어도 되는지.
 */
object QaEngine {

    enum class Intent { WHY, LANES, DISTANCE, WHEN_CLEAR, FASTEST_LANE, CRUISE, TRUST, UNKNOWN }

    data class Context(
        val incident: Incident?,
        val distanceM: Double?,
        /** 공공 기록을 받은 지 몇 분 됐는지 */
        val observedMinutesAgo: Int?,
        val cruise: Cruise,
        val cruiseTargetKmh: Double?,
        val speedLimitKmh: Int?,
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

    private fun basis(ctx: Context): String {
        val ago = ctx.observedMinutesAgo
        return if (ago == null) "근거: 공공 돌발정보" else "근거: 공공 돌발정보, ${ago}분 전 받음"
    }

    private fun where(ctx: Context): String = ctx.distanceM?.let { "${KoText.km(it)} 앞" } ?: "앞"

    fun answer(question: String, ctx: Context): Answer = answer(classify(question), ctx)

    fun answer(intent: Intent, ctx: Context): Answer {
        val inc = ctx.incident
        val text: String = when (intent) {
            Intent.TRUST -> "그 질문엔 답하지 않아요. 제가 알려 드리는 건 앞 사건과 통제 차로예요."
            Intent.CRUISE -> when (ctx.cruise) {
                Cruise.ACTIVE -> "크루즈는 작동 중이에요." + (ctx.cruiseTargetKmh?.let { " 설정 속도는 ${it.toInt()}km/h예요." } ?: "")
                Cruise.STANDBY -> "크루즈는 대기 상태예요."
                Cruise.OFF -> "크루즈는 꺼져 있어요."
                else -> "크루즈 상태는 지금 ${ctx.cruise.ko}이에요."
            }
            else -> if (inc == null) "지금 경로 앞에 확인된 돌발 기록은 없어요." else when (intent) {
                Intent.LANES -> laneAnswer(inc, ctx)
                Intent.WHY -> whyAnswer(inc, ctx)
                Intent.DISTANCE -> ctx.distanceM?.let { "${inc.kindKo} 지점까지 ${KoText.km(it)} 남았어요." }
                    ?: "사건 지점까지 거리는 지금 알 수 없어요."
                Intent.WHEN_CLEAR -> {
                    val end = inc.endClock()
                    val start = inc.startClock()
                    when {
                        end != null -> "기록상 끝나는 시각은 ${end}이에요."
                        start != null -> "끝나는 시각은 기록에 없어요. 시작 시각은 ${start}이에요."
                        else -> "끝나는 시각은 기록에 없어요."
                    }
                }
                Intent.FASTEST_LANE -> "차로별 속도는 확인할 수 없어요. " + factLine(inc, ctx)
                Intent.UNKNOWN -> "제가 답할 수 있는 건 앞 사건, 통제 차로, 남은 거리예요."
                else -> "제가 답할 수 있는 건 앞 사건, 통제 차로, 남은 거리예요."
            }
        }
        check(KoText.isSafe(text)) { text }
        val b = when (intent) {
            Intent.CRUISE -> "근거: 차량 크루즈 신호"
            Intent.TRUST, Intent.UNKNOWN -> ""
            else -> if (inc == null) "" else basis(ctx)
        }
        return Answer(intent, text, b)
    }

    /** "확인된 건 2km 앞 사고로 2차로가 통제됐다는 기록이에요." */
    private fun factLine(inc: Incident, ctx: Context): String = when {
        inc.hasMainlineLanes -> "확인된 건 ${where(ctx)} ${inc.kindKo}로 ${KoText.subject(KoText.lanes(inc.lanes)!!)} 통제됐다는 기록이에요."
        else -> "확인된 건 ${where(ctx)} ${inc.kindKo} 기록이에요."
    }

    private fun laneAnswer(inc: Incident, ctx: Context): String = when (inc.scope) {
        Incident.LaneScope.MAINLINE ->
            if (inc.lanes.isEmpty()) "${where(ctx)} ${inc.kindKo}예요. 통제 차로는 기록에 없어요."
            else "${where(ctx)} ${inc.kindKo}로 ${KoText.subject(KoText.lanes(inc.lanes)!!)} 통제됐어요."
        Incident.LaneScope.RAMP_ENTRY -> "${where(ctx)} 진입로 공사예요. 본선 차로 통제 기록은 없어요."
        Incident.LaneScope.RAMP_EXIT -> "${where(ctx)} 진출로 공사예요. 본선 차로 통제 기록은 없어요."
        Incident.LaneScope.SHOULDER -> "${where(ctx)} 갓길이에요. 본선 차로 통제 기록은 없어요."
        Incident.LaneScope.NONE -> "${where(ctx)} ${inc.kindKo}예요. 통제 차로는 기록에 없어요."
    }

    private fun whyAnswer(inc: Incident, ctx: Context): String {
        val body = inc.messageBody.removeSuffix(".")
        val first = when (inc.kind) {
            Incident.Kind.BREAKDOWN -> "${where(ctx)}에서 고장차를 처리하고 있어요."
            Incident.Kind.ACCIDENT -> "${where(ctx)}에서 사고를 처리하고 있어요."
            Incident.Kind.WEATHER, Incident.Kind.DISASTER -> "${where(ctx)}에 ${KoText.subject(inc.kindKo)} 있어요."
            else -> if (body.isNotBlank()) "${where(ctx)} ${body.trimEnd('중', ' ')} 중이에요." else "${where(ctx)} ${inc.kindKo}예요."
        }
        val second = if (inc.hasMainlineLanes) " ${KoText.subject(KoText.lanes(inc.lanes)!!)} 통제됐어요." else ""
        return (first + second).replace("  ", " ")
    }
}
