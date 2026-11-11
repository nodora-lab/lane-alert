package io.lanealert.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 규칙 엔진 단위 시험.
 * 1부: 파이썬 참조 구현 tests/test_all.py 의 안내 규칙 시험(announce_*, tail_*, km_text_*)을 그대로 옮김.
 * 2부: 공공 돌발 기록 해석, 알림 시점, 묻고 답하기, 재생 통합.
 */
class RulesTest {

    private val EV = Announcer.RouteEvent("e1", "ACCIDENT", 3000.0, 60.0, 0.95, true)
    private val PUB = Announcer.PublicLanes(listOf(2), 60.0, 0.9)
    private val RES = Announcer.Residual(30.0, 50.0, 100, true)

    // ---------- 1부: 파이썬에서 옮긴 시험 ----------

    @Test fun announce_full_sentence_and_no_lane_instruction() {
        val u = Announcer().eventUtterance("r1", EV, PUB, RES)!!
        assertEquals("3km 앞 사고. 2차로 통제. 비슷한 사고는 보통 30-50분 더 이어졌어요.", u.text)
        assertTrue(KoText.FORBIDDEN.none { it in u.text })
    }

    @Test fun announce_same_event_approaching_not_repeated() {
        val a = Announcer()
        a.confirm(a.eventUtterance("r1", EV)!!)
        for (off in listOf(2400.0, 1900.0)) assertNull(a.eventUtterance("r1", EV.copy(offsetM = off)))
    }

    @Test fun announce_again_when_content_changes() {
        val a = Announcer()
        a.confirm(a.eventUtterance("r1", EV)!!)
        val u2 = a.eventUtterance("r1", EV.copy(offsetM = 2500.0), PUB)
        assertNotNull(u2); assertTrue("2차로 통제" in u2!!.text)
    }

    @Test fun announce_not_marked_until_confirmed() {
        val a = Announcer()
        assertNotNull(a.eventUtterance("r1", EV))
        assertNotNull(a.eventUtterance("r1", EV))
    }

    @Test fun announce_drops_stale_or_offroute_event() {
        val a = Announcer()
        assertNull(a.eventUtterance("r1", EV.copy(ageS = 3600.0)))
        assertNull(a.eventUtterance("r1", EV.copy(routeConf = 0.3)))
        assertNull(a.eventUtterance("r1", EV.copy(active = false)))
        assertNull(a.eventUtterance("r1", EV.copy(offsetM = -100.0)))
    }

    @Test fun announce_rejects_bad_residual() {
        val a = Announcer()
        assertFalse("분" in a.eventUtterance("r1", EV, PUB, Announcer.Residual(80.0, 20.0, 1, true))!!.text)
        assertFalse("분" in a.eventUtterance("r2", EV, PUB, RES.copy(validated = false))!!.text)
    }

    @Test fun announce_drops_public_lanes_when_stale_or_unmatched() {
        val a = Announcer()
        assertFalse("차로" in a.eventUtterance("r1", EV, PUB.copy(ageS = 1200.0))!!.text)
        assertFalse("차로" in a.eventUtterance("r2", EV, PUB.copy(matchConf = 0.5))!!.text)
    }

    @Test fun tail_uses_mps_and_time_to_reach() {
        val a = Announcer()
        val v = 100 / 3.6
        assertNull(a.tailUtterance("r1", "c1", Cruise.ACTIVE, v, v, 1.0, 1400.0, 20.0))
        assertNotNull(a.tailUtterance("r1", "c1", Cruise.ACTIVE, v, v, 1.0, 1000.0, 20.0))
        assertNull(a.tailUtterance("r1", "c2", Cruise.STANDBY, v, v, 1.0, 1000.0, 20.0))
        assertNull(a.tailUtterance("r1", "c3", Cruise.ACTIVE, v, v + 2, 1.0, 1000.0, 20.0))
        assertNull(a.tailUtterance("r1", "c4", Cruise.ACTIVE, v, v, 0.0, 1000.0, 20.0))
    }

    @Test fun km_text_rounding() {
        assertEquals("1.5km", KoText.km(1500.0))
        assertEquals("3km", KoText.km(3000.0))
        assertEquals("800m", KoText.km(800.0))
        assertEquals("3.3km", KoText.km(3333.0))
    }

    // ---------- 2부: 공공 돌발 기록 해석 (2026-11-12 실제 기록 문구) ----------

    private fun rec(type: String, detail: String, lanes: String, msg: String, end: String = "") = mapOf(
        "eventType" to type, "eventDetailType" to detail, "lanesBlocked" to lanes, "message" to msg,
        "roadName" to "경부선", "roadDrcType" to "종점", "startDate" to "20261111212824", "endDate" to end,
        "linkId" to "1", "coordX" to "127.0", "coordY" to "37.2",
    )

    @Test fun its_message_lanes_win_over_single_field_lane() {
        val i = ItsParser.parse(rec("공사", "작업", "5차로 차단", "(4,5차로)노면 포장 작업 중"))
        assertEquals(listOf(4, 5), i.lanes)
        assertTrue(i.laneFieldMismatch)
        assertEquals(Incident.LaneScope.MAINLINE, i.scope)
        assertEquals("노면 포장 작업 중", i.messageBody)
    }

    @Test fun its_ramp_lane_is_not_mainline() {
        val i = ItsParser.parse(rec("공사", "작업", "2차로 차단", "(진입로 2차로)노면보수 작업중"))
        assertEquals(Incident.LaneScope.RAMP_ENTRY, i.scope)
        assertFalse(i.hasMainlineLanes)
        assertEquals("진입로 공사", i.kindKo)
    }

    @Test fun its_shoulder_only_has_no_lanes() {
        val i = ItsParser.parse(rec("기타돌발", "장애물", "", "(갓길)장애물(석재류) 처리중"))
        assertEquals(Incident.LaneScope.SHOULDER, i.scope)
        assertTrue(i.lanes.isEmpty())
        assertEquals("갓길 장애물", i.kindKo)
    }

    @Test fun its_lane_and_shoulder_mix_keeps_lane() {
        val i = ItsParser.parse(rec("공사", "작업", "3차로 차단", "(3차로,갓길) 옥산육교보수공사 작업중"))
        assertEquals(listOf(3), i.lanes)
        assertEquals(Incident.LaneScope.MAINLINE, i.scope)
        assertFalse(i.laneFieldMismatch)
    }

    @Test fun its_breakdown_and_weather_kinds() {
        assertEquals(Incident.Kind.BREAKDOWN, ItsParser.parse(rec("기타돌발", "고장", "4차로 차단", "(4차로) 고장차 처리중")).kind)
        val fog = ItsParser.parse(rec("기상", "안개", "", "\"안개\"주의"))
        assertEquals(Incident.Kind.WEATHER, fog.kind)
        assertEquals("안개 주의 구간", fog.kindKo)
        assertEquals(Incident.LaneScope.NONE, fog.scope)
    }

    @Test fun its_lane_parser_ignores_truck_lane_word_and_bad_dates() {
        assertTrue(ItsParser.lanesIn("진입로 화물차로").isEmpty())
        assertEquals(listOf(2, 3, 4, 5), ItsParser.lanesIn("2,3,4,5차로"))
        assertNull(ItsParser.ts("bad"))
        assertNull(ItsParser.ts("20261320"))
    }

    // ---------- 알림 시점 ----------

    @Test fun cruise_active_alerts_earlier() {
        val v = 100 / 3.6
        assertTrue(AlertTiming.isDue(3000.0, v, Cruise.ACTIVE))     // 108초
        assertFalse(AlertTiming.isDue(3000.0, v, Cruise.OFF))       // 60초 문턱 밖
        assertTrue(AlertTiming.isDue(1600.0, v, Cruise.OFF))        // 58초
        assertEquals(v * 120, AlertTiming.dueDistanceM(v, Cruise.ACTIVE), 1e-6)
    }

    @Test fun timing_edges() {
        assertTrue(AlertTiming.isDue(500.0, 0.0, Cruise.OFF))       // 거의 서 있어도 가까우면 말함
        assertFalse(AlertTiming.isDue(9000.0, 40.0, Cruise.ACTIVE)) // 너무 멂
        assertFalse(AlertTiming.isDue(-5.0, 30.0, Cruise.ACTIVE))
        assertFalse(AlertTiming.isDue(Double.NaN, 30.0, Cruise.ACTIVE))
    }

    @Test fun pleos_cruise_values_map() {
        assertEquals(Cruise.ACTIVE, Cruise.fromPleosName("ACTIVATED"))
        assertEquals(Cruise.STANDBY, Cruise.fromPleosName("ENABLED"))
        assertEquals(Cruise.OVERRIDE, Cruise.fromPleosName("USER_OVERRIDE"))
        assertEquals(Cruise.UNKNOWN, Cruise.fromPleosName("OTHER"))
    }

    // ---------- 묻고 답하기 ----------

    private fun ctx(i: Incident?) = QaEngine.Context(i, 2000.0, "공공 돌발정보 11-12 00:42 기록", Cruise.ACTIVE, 100.0)
    private val acc = ItsParser.parse(rec("교통사고", "사고", "2차로 차단", "(2차로) 사고 처리중"))

    @Test fun qa_lanes_answer_uses_record() {
        val a = QaEngine.answer("몇 차로 막혔어?", ctx(acc))
        assertEquals(QaEngine.Intent.LANES, a.intent)
        assertEquals("2km 앞 사고, 2차로 통제예요.", a.text)
        assertEquals("근거: 공공 돌발정보 11-12 00:42 기록", a.basis)
    }

    @Test fun qa_refuses_fastest_lane_and_restates_fact() {
        val a = QaEngine.answer("어느 차로가 빨라?", ctx(acc))
        assertEquals(QaEngine.Intent.FASTEST_LANE, a.intent)
        assertEquals("차로별 속도는 확인할 수 없어요. 확인된 건 2km 앞 사고 기록, 2차로 통제예요.", a.text)
    }

    @Test fun qa_never_vouches_for_cruise() {
        val a = QaEngine.answer("크루즈 믿어도 돼?", ctx(acc))
        assertEquals(QaEngine.Intent.TRUST, a.intent)
        assertTrue(KoText.isSafe(a.text))
        assertEquals("크루즈는 작동 중이에요. 설정 속도는 100km/h예요.", QaEngine.answer("크루즈 켜져 있어?", ctx(acc)).text)
    }

    @Test fun qa_when_clear_says_unknown_if_no_end() {
        assertEquals("끝나는 시각은 기록에 없어요. 시작 시각은 21:28이에요.", QaEngine.answer("언제 풀려?", ctx(acc)).text)
    }

    @Test fun qa_ramp_and_no_incident() {
        val ramp = ItsParser.parse(rec("공사", "작업", "2차로 차단", "(진입로 2차로)노면보수 작업중"))
        assertEquals("2km 앞 진입로 공사 기록이에요. 본선 차로 통제 기록은 없어요.", QaEngine.answer("몇 차로야", ctx(ramp)).text)
        assertEquals("지금 확인된 돌발 기록은 없어요.", QaEngine.answer("앞에 왜 막혀?", ctx(null)).text)
    }

    @Test fun qa_why_uses_message_body() {
        val w = ItsParser.parse(rec("공사", "작업", "5차로 차단", "(4,5차로)노면 포장 작업 중"))
        assertEquals("2km 앞, 노면 포장 작업 중이라는 기록이에요. 4, 5차로 통제예요.", QaEngine.answer("앞에 왜 막혀?", ctx(w)).text)
    }

    @Test fun every_answer_is_short_and_safe() {
        val qs = listOf("몇 차로야", "왜 막혀", "얼마나 남았어", "언제 풀려", "어느 차로가 빨라", "크루즈 믿어도 돼", "날씨 어때")
        for (inc in listOf(acc, null)) for (q in qs) {
            val t = QaEngine.answer(q, ctx(inc)).text
            assertTrue(t, KoText.isSafe(t))
            assertTrue(t, t.count { it == '.' } <= 2)
        }
    }

    // ---------- 재생 통합: 같은 기록, 크루즈만 다르게 ----------

    private fun firstAlertDistance(cruise: Cruise): Double {
        val sc = Scenario("t", "t", rec("기타돌발", "고장", "4차로 차단", "(4차로) 고장차 처리중"),
            4300.0, 100.0, cruise, if (cruise == Cruise.ACTIVE) 100.0 else null, null, null)
        val sim = ReplaySim(sc); val eng = DriveEngine()
        var t = 0
        while (!sim.finished) {
            sim.step(1.0); t++
            val u = eng.step(sim.tS, sim.vehicle(), sim.ahead(60.0))
            if (u.isNotEmpty()) {
                assertEquals(true, u[0].text.endsWith("4차로 통제."))
                return sim.distanceM
            }
        }
        error("no alert")
    }

    @Test fun replay_cruise_on_alerts_about_twice_as_far() {
        val on = firstAlertDistance(Cruise.ACTIVE)
        val off = firstAlertDistance(Cruise.OFF)
        assertTrue("on=$on off=$off", on in 3200.0..3340.0 && off in 1600.0..1670.0)
    }

    @Test fun replay_tail_alert_when_cruise_on_and_not_slowing() {
        val sc = Scenario("t", "t", rec("기타돌발", "고장", "4차로 차단", "(4차로) 고장차 처리중"),
            4300.0, 100.0, Cruise.ACTIVE, 100.0, 1500.0, 30.0)
        val sim = ReplaySim(sc); val eng = DriveEngine(); val said = mutableListOf<String>()
        while (!sim.finished) {
            sim.step(1.0)
            eng.step(sim.tS, sim.vehicle(), sim.ahead(60.0)).forEach { eng.announcer.confirm(it); said += it.text }
        }
        assertTrue(said.toString(), said.any { "정체가 시작돼요" in it })
        assertEquals(1, said.count { "고장차" in it })   // 같은 사건은 한 번만
    }
    // ---------- 2라운드 추가 (GPT 1차 지적) ----------

    @Test fun its_range_and_multiple_parentheses() {
        assertEquals(listOf(2, 3, 4), ItsParser.lanesIn("2~4차로"))
        val i = ItsParser.parse(rec("공사", "작업", "4차로 차단", "송추(50K)-노고산터널(56K)(3,4차로)시설물 보수 작업중"))
        assertEquals(listOf(3, 4), i.lanes)
        assertEquals(Incident.LaneScope.MAINLINE, i.scope)
    }

    @Test fun ramp_breakdown_is_not_called_construction() {
        val i = ItsParser.parse(rec("기타돌발", "고장", "1차로 차단", "(진출로 1차로) 고장차 처리중"))
        assertEquals("진출로 고장차", i.kindKo)
        assertEquals("2km 앞 진출로 고장차 기록이에요. 본선 차로 통제 기록은 없어요.", QaEngine.answer("몇 차로야", ctx(i)).text)
    }

    @Test fun why_answer_repeats_record_wording_not_guess() {
        val b = ItsParser.parse(rec("기타돌발", "고장", "4차로 차단", "(4차로) 고장차 발생"))
        assertEquals("2km 앞, 고장차 발생이라는 기록이에요. 4차로 통제예요.", QaEngine.answer("왜 막혀", ctx(b)).text)
    }

    @Test fun stale_record_does_not_state_lanes_as_current() {
        val t = QaEngine.answer("몇 차로야", ctx(acc).copy(recordFresh = false)).text
        assertFalse(t, "2차로" in t)
    }

    @Test fun nearby_record_is_not_called_ahead() {
        val c = ctx(acc).copy(relation = QaEngine.Relation.NEARBY)
        val t = QaEngine.answer("몇 차로야", c).text
        assertFalse(t, "앞" in t.substringBefore("."))
        assertTrue(t, "확인 전" in t)
        assertEquals("내 경로 위 거리는 아직 확인 전이에요.", QaEngine.answer("얼마나 남았어", c).text)
    }

    @Test fun cruise_basis_names_its_source() {
        assertEquals("근거: 크루즈 신호(Pleos Vehicle SDK)",
            QaEngine.answer("크루즈 켜져 있어?", ctx(acc).copy(cruiseSource = "Pleos Vehicle SDK")).basis)
    }

    @Test fun navi_route_event_gets_public_lanes_only_when_matched() {
        val now = java.time.LocalDateTime.of(2026, 11, 12, 1, 0)
        val inc = ItsParser.parse(rec("기타돌발", "고장", "4차로 차단", "(4차로) 고장차 처리중"))   // 37.2, 127.0
        val me = 37.17 to 127.0                                                                  // 남쪽 약 3.3km
        val hit = NaviMatch.aheadFromNavi(me.first, me.second, listOf(NaviMatch.NaviEvent("ACCIDENT", 37.2005, 127.0)),
            emptyList(), listOf(inc), 60.0, now)!!
        assertEquals(listOf(4), hit.incident.lanes)
        assertTrue(hit.distanceM in 3200.0..3400.0)
        assertNull(NaviMatch.aheadFromNavi(me.first, me.second, listOf(NaviMatch.NaviEvent("ACCIDENT", 37.25, 127.0)),
            emptyList(), listOf(inc), 60.0, now))
        assertNull(NaviMatch.aheadFromNavi(me.first, me.second, emptyList(), emptyList(), listOf(inc), 60.0, now))
    }

    @Test fun ended_record_is_not_announced() {
        val now = java.time.LocalDateTime.of(2026, 11, 12, 1, 0)
        val ended = ItsParser.parse(rec("기타돌발", "고장", "4차로 차단", "(4차로) 고장차 처리중", end = "20261112003000"))
        val a = NaviMatch.aheadFromNavi(37.17, 127.0, listOf(NaviMatch.NaviEvent("ACCIDENT", 37.2, 127.0)),
            emptyList(), listOf(ended), 60.0, now)!!
        assertFalse(a.active)
        assertTrue(DriveEngine().step(1.0, VehicleState(27.8, Cruise.ACTIVE, 100.0), a).isEmpty())
    }
}