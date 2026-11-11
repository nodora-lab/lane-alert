package io.lanealert.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 공공 돌발 기록 한 건을 앱이 쓰는 형태로 정리한 것. 사실은 원문 칸에서만 뽑는다. */
data class Incident(
    val id: String,
    val kind: Kind,
    /** 원문 eventType / eventDetailType */
    val eventType: String,
    val eventDetailType: String,
    val roadName: String,
    /** 원문 roadDrcType (기점/종점 방향) */
    val direction: String,
    /** 통제 차로 번호. 비어 있으면 차로 기록 없음 */
    val lanes: List<Int>,
    /** 차로 번호가 본선인지, 진입로·진출로인지, 갓길만인지 */
    val scope: LaneScope,
    /** 원문 lanesBlocked 칸 */
    val lanesBlockedRaw: String,
    /** lanesBlocked 칸과 메시지 괄호의 차로가 서로 다른가 */
    val laneFieldMismatch: Boolean,
    val message: String,
    val startAt: LocalDateTime?,
    val endAt: LocalDateTime?,
    val lat: Double?,
    val lon: Double?,
) {
    enum class Kind { ACCIDENT, BREAKDOWN, OBSTACLE, WORK, WEATHER, DISASTER, OTHER }

    enum class LaneScope { MAINLINE, RAMP_ENTRY, RAMP_EXIT, SHOULDER, NONE }

    /** 짧은 이름: 음성 첫 문장에 씀 */
    /** 사건 종류 이름 (범위와 따로) */
    val baseKo: String
        get() = when (kind) {
            Kind.ACCIDENT -> "사고"
            Kind.BREAKDOWN -> "고장차"
            Kind.OBSTACLE -> "장애물"
            Kind.WORK -> if (scope == LaneScope.SHOULDER) "작업" else "공사"
            Kind.WEATHER -> "${eventDetailType.ifBlank { "기상" }} 주의 구간"
            Kind.DISASTER -> "${eventDetailType.ifBlank { "재난" }} 주의 구간"
            Kind.OTHER -> "돌발 상황"
        }

    /** 범위 + 종류: "진입로 공사", "진입로 고장차", "갓길 장애물", "사고" */
    val kindKo: String
        get() = when (scope) {
            LaneScope.RAMP_ENTRY -> "진입로 $baseKo"
            LaneScope.RAMP_EXIT -> "진출로 $baseKo"
            LaneScope.SHOULDER -> "갓길 $baseKo"
            else -> baseKo
        }

    /** 기록 문구 그대로 (따옴표만 정리). "(4차로) 고장차 처리중" -> "고장차 처리중" */
    val plainMessage: String
        get() = messageBody.replace("\"", "").replace(Regex("\\s+"), " ").trim()

    /** 메시지에서 괄호 머리말을 뗀 본문. "(4,5차로)노면 포장 작업 중" -> "노면 포장 작업 중" */
    val messageBody: String
        get() = message.replace(Regex("^\\s*\\([^)]*\\)\\s*"), "").replace("*", "").trim()

    /** 본선 통제 차로가 있는가 */
    val hasMainlineLanes: Boolean get() = scope == LaneScope.MAINLINE && lanes.isNotEmpty()

    fun startClock(): String? = startAt?.format(DateTimeFormatter.ofPattern("HH:mm"))
    fun endClock(): String? = endAt?.format(DateTimeFormatter.ofPattern("HH:mm"))
}
