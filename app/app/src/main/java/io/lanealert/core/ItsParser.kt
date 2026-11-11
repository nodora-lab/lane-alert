package io.lanealert.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * ITS 국가교통정보센터 돌발상황정보(eventInfo) 한 줄을 [Incident]로 바꾼다.
 *
 * 2026-11-12 00:42 받은 고속도로 기록 195건을 직접 보고 정한 규칙:
 * - lanesBlocked 칸에는 차로 번호가 하나만 들어 있는 경우가 많다("5차로 차단").
 *   같은 기록의 메시지 괄호에는 "(4,5차로)"처럼 전체 목록이 있다. → 메시지 괄호를 먼저 쓰고, 칸은 대조용.
 * - 괄호에 "진입로"·"진출로"가 있으면 본선 차로가 아니다. → 본선 차로 통제라고 말하지 않는다.
 * - 괄호에 "갓길"만 있고 차로 번호가 없으면 본선 차로 통제가 아니다.
 * - 끝나는 시각(endDate)은 대부분 비어 있다. → 비어 있으면 "기록에 없음"으로 답한다.
 */
object ItsParser {

    private val TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val PAREN = Regex("\\(([^)]*)\\)")
    private val LANE_LIST = Regex("((?:\\d+\\s*,\\s*)*\\d+)\\s*차로")

    fun parse(r: Map<String, String?>): Incident {
        val eventType = r["eventType"].orEmpty().trim()
        val detail = r["eventDetailType"].orEmpty().trim()
        val message = r["message"].orEmpty().trim()
        val lanesRaw = r["lanesBlocked"].orEmpty().trim()

        val head = PAREN.find(message)?.groupValues?.get(1).orEmpty()
        val msgLanes = lanesIn(head)
        val fieldLanes = lanesIn(lanesRaw)

        val scope = when {
            "진입로" in head -> Incident.LaneScope.RAMP_ENTRY
            "진출로" in head -> Incident.LaneScope.RAMP_EXIT
            msgLanes.isNotEmpty() -> Incident.LaneScope.MAINLINE
            "갓길" in head -> Incident.LaneScope.SHOULDER
            fieldLanes.isNotEmpty() -> Incident.LaneScope.MAINLINE
            else -> Incident.LaneScope.NONE
        }
        val lanes = when {
            msgLanes.isNotEmpty() -> msgLanes
            scope == Incident.LaneScope.SHOULDER -> emptyList()
            else -> fieldLanes
        }
        val mismatch = msgLanes.isNotEmpty() && fieldLanes.isNotEmpty() && msgLanes.toSet() != fieldLanes.toSet()

        val kind = when {
            eventType.contains("사고") || detail.contains("사고") -> Incident.Kind.ACCIDENT
            detail.contains("고장") -> Incident.Kind.BREAKDOWN
            detail.contains("장애물") || detail.contains("낙하물") -> Incident.Kind.OBSTACLE
            eventType.contains("공사") || detail.contains("작업") -> Incident.Kind.WORK
            eventType.contains("기상") -> Incident.Kind.WEATHER
            eventType.contains("재난") -> Incident.Kind.DISASTER
            else -> Incident.Kind.OTHER
        }

        val start = ts(r["startDate"])
        return Incident(
            id = listOf(r["linkId"].orEmpty(), r["startDate"].orEmpty(), eventType).joinToString(":"),
            kind = kind,
            eventType = eventType,
            eventDetailType = detail,
            roadName = r["roadName"].orEmpty().trim(),
            direction = r["roadDrcType"].orEmpty().trim(),
            lanes = lanes,
            scope = scope,
            lanesBlockedRaw = lanesRaw,
            laneFieldMismatch = mismatch,
            message = message,
            startAt = start,
            endAt = ts(r["endDate"]),
            lat = r["coordY"]?.toDoubleOrNull(),
            lon = r["coordX"]?.toDoubleOrNull(),
        )
    }

    /** "(2,3,4,5차로)" -> [2,3,4,5], "3차로,갓길" -> [3], "화물차로" -> [] */
    fun lanesIn(s: String): List<Int> =
        LANE_LIST.findAll(s).flatMap { m -> m.groupValues[1].split(",").mapNotNull { it.trim().toIntOrNull() } }
            .filter { it in 1..12 }.distinct().sorted().toList()

    fun ts(s: String?): LocalDateTime? {
        val t = s?.trim().orEmpty()
        if (t.length != 14) return null
        return try { LocalDateTime.parse(t, TS) } catch (e: Exception) { null }
    }
}
