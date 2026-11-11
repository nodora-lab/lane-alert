package io.lanealert.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.lanealert.core.AlertTiming
import io.lanealert.core.Announcer
import io.lanealert.core.Cruise
import io.lanealert.core.DriveEngine
import io.lanealert.core.Incident
import io.lanealert.core.IncidentMatcher
import io.lanealert.core.NaviMatch
import io.lanealert.core.QaEngine
import io.lanealert.core.ReplaySim
import io.lanealert.core.Scenario
import io.lanealert.core.VehicleState
import io.lanealert.data.ItsLiveClient
import io.lanealert.data.ScenarioRepo
import io.lanealert.pleos.Hearer
import io.lanealert.pleos.PleosNaviSource
import io.lanealert.pleos.PleosVehicleSource
import io.lanealert.pleos.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class DriveViewModel(app: Application) : AndroidViewModel(app) {

    enum class Mode { REPLAY, LIVE }
    enum class CardKind(val label: String) { EVENT("앞 사건 안내"), TAIL("크루즈 접근 안내"), ANSWER("답") }

    data class Card(val kind: CardKind, val text: String)
    data class QA(val q: String, val a: String, val basis: String)

    data class UiState(
        val mode: Mode = Mode.REPLAY,
        val dataLabel: String = "",
        val scenarios: List<Scenario> = emptyList(),
        val scenarioIdx: Int = 0,
        val playing: Boolean = false,
        val fast: Boolean = false,
        val incident: Incident? = null,
        val relation: QaEngine.Relation = QaEngine.Relation.AHEAD,
        val distanceM: Double? = null,
        val startDistanceM: Double = 5000.0,
        val congestionM: Double? = null,
        val speedKmh: Double? = null,
        val speedSource: String = "재생",
        val cruise: Cruise = Cruise.UNKNOWN,
        val cruiseTargetKmh: Double? = null,
        val cruiseSource: String = "재생",
        val useCarSignals: Boolean = false,
        val dueAtM: Double? = null,
        val dueOnM: Double? = null,
        val dueOffM: Double? = null,
        val leadS: Int = AlertTiming.BASE_LEAD_S.toInt(),
        val announcedAtM: Double? = null,
        val recordLabel: String = "",
        val card: Card? = null,
        val speaking: Boolean = false,
        val qaOpen: Boolean = false,
        val qa: List<QA> = emptyList(),
        val listening: Boolean = false,
        val partial: String = "",
        val sttReady: Boolean = false,
        val ttsEngine: String = "",
        val sttStatus: String = "",
        val vehicleStatus: String = "",
        val speedStatus: String = "",
        val naviStatus: String = "",
        val liveInfo: String = "",
        val demoOpen: Boolean = false,
        val log: List<String> = emptyList(),
        val finished: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val ctx get() = getApplication<Application>()
    private val vehicle = PleosVehicleSource(ctx)
    private val navi = PleosNaviSource(ctx)
    val speaker = Speaker(ctx)
    private val hearer = Hearer(ctx)
    private val engine = DriveEngine()

    private var bundle: ScenarioRepo.Bundle? = null
    private var sim: ReplaySim? = null
    private var loop: Job? = null
    private var lastStepS = -1.0
    private val queue = ArrayDeque<Pair<Announcer.Utterance?, Card>>()
    private var busy = false
    private val cooldown = HashMap<String, Long>()
    private var liveIncidents: List<Incident> = emptyList()
    private var liveFetchedAt: LocalDateTime? = null
    private var qaCloseJob: Job? = null

    private val replayLabel get() = bundle?.let { "공공 돌발정보 ${it.fetchedAt.substring(5, 16)} 기록(재생)" } ?: "공공 돌발정보"

    init {
        bundle = runCatching { ScenarioRepo.load(ctx) }.getOrNull()
        _ui.value = _ui.value.copy(scenarios = bundle?.scenarios.orEmpty())
        vehicle.start(); navi.start(); speaker.start(); hearer.start()
        hearer.onFinal = { text -> ask(text) }
        viewModelScope.launch { vehicle.signals.collect { s -> _ui.value = _ui.value.copy(vehicleStatus = s.vehicleStatus, speedStatus = s.speedStatus) } }
        viewModelScope.launch { navi.state.collect { n -> _ui.value = _ui.value.copy(naviStatus = n.status) } }
        viewModelScope.launch { speaker.engine.collect { _ui.value = _ui.value.copy(ttsEngine = it) } }
        viewModelScope.launch { speaker.speaking.collect { _ui.value = _ui.value.copy(speaking = it) } }
        viewModelScope.launch { hearer.status.collect { _ui.value = _ui.value.copy(sttStatus = it, sttReady = hearer.ready) } }
        viewModelScope.launch { hearer.listening.collect { _ui.value = _ui.value.copy(listening = it) } }
        viewModelScope.launch { hearer.partial.collect { _ui.value = _ui.value.copy(partial = it) } }
        selectScenario(0, autoplay = false)
    }

    // ---------- 재생 ----------

    fun selectScenario(i: Int, autoplay: Boolean = true) {
        val sc = bundle?.scenarios?.getOrNull(i) ?: return
        loop?.cancel()
        speaker.stopSpeaking(); queue.clear(); busy = false; cooldown.clear()
        engine.reset(); lastStepS = -1.0
        sim = ReplaySim(sc)
        _ui.value = _ui.value.copy(
            mode = Mode.REPLAY, scenarioIdx = i, card = null, qa = emptyList(), qaOpen = false, finished = false,
            relation = QaEngine.Relation.AHEAD, startDistanceM = sc.startDistanceM, announcedAtM = null,
            dataLabel = "공공 기록 재생 · 주행 조건 가상", recordLabel = replayLabel, liveInfo = "",
        )
        publish()
        if (autoplay) play()
    }

    fun togglePlay() = if (_ui.value.playing) pause() else play()

    fun play() {
        if (_ui.value.mode != Mode.REPLAY) return
        if (sim?.finished == true) { selectScenario(_ui.value.scenarioIdx); return }
        loop?.cancel()
        _ui.value = _ui.value.copy(playing = true)
        loop = viewModelScope.launch {
            while (isActive) {
                val s = sim ?: break
                val dt = 0.2 * (if (_ui.value.fast) 4 else 1)
                applyCarSignals(s)
                s.step(dt)
                if (s.tS - lastStepS >= 1.0) {
                    lastStepS = s.tS
                    engine.step(s.tS, s.vehicle(), s.ahead(observedAgeS = 60.0 + s.tS)).forEach { u ->
                        val kind = if (u.content == "tail") CardKind.TAIL else CardKind.EVENT
                        enqueue(u, Card(kind, u.text))
                    }
                }
                publish()
                if (s.finished) { _ui.value = _ui.value.copy(playing = false, finished = true); log("사건 지점 통과 · 재생 끝"); break }
                delay(200)
            }
        }
    }

    fun pause() { loop?.cancel(); _ui.value = _ui.value.copy(playing = false) }
    fun setFast(on: Boolean) { _ui.value = _ui.value.copy(fast = on) }
    fun toggleFast() = setFast(!_ui.value.fast)
    fun toggleDemo() { _ui.value = _ui.value.copy(demoOpen = !_ui.value.demoOpen) }

    /** 재생 중 크루즈를 손으로 바꿔 보기(시연용). 차량 신호 사용 중이면 무시 */
    fun toggleReplayCruise() {
        val s = sim ?: return
        if (_ui.value.useCarSignals) return
        s.cruise = if (s.cruise == Cruise.ACTIVE) Cruise.OFF else Cruise.ACTIVE
        if (s.cruise == Cruise.ACTIVE && s.cruiseTargetKmh == null) s.cruiseTargetKmh = s.speedMps * 3.6
        log("크루즈 ${s.cruise.ko} (시연 조작)")
        publish()
    }

    fun setCarSignals(on: Boolean) { _ui.value = _ui.value.copy(useCarSignals = on); publish() }
    fun toggleCarSignals() = setCarSignals(!_ui.value.useCarSignals)

    /** 차량 신호 사용: 크루즈 상태·설정 속도(Pleos Vehicle SDK)와 차속(PERF_VEHICLE_SPEED)을 재생 차량에 넣는다 */
    private fun applyCarSignals(s: ReplaySim) {
        if (!_ui.value.useCarSignals) { s.carSpeedMps = null; return }
        val sig = vehicle.signals.value
        sig.cruise?.let { s.cruise = it }
        sig.cruiseTargetKmh?.let { s.cruiseTargetKmh = it }
        sig.speedMps?.let { s.carSpeedMps = it }
    }

    private fun publish() {
        val s = sim ?: return
        val sig = vehicle.signals.value
        val car = _ui.value.useCarSignals
        _ui.value = _ui.value.copy(
            incident = s.scenario.incident,
            distanceM = s.distanceM,
            congestionM = s.congestionOffsetM()?.takeIf { it > 0 },
            speedKmh = s.speedMps * 3.6,
            speedSource = if (car && sig.speedMps != null) "PERF_VEHICLE_SPEED" else "재생",
            cruise = s.cruise,
            cruiseTargetKmh = s.cruiseTargetKmh,
            cruiseSource = if (car && sig.cruise != null) "Pleos Vehicle SDK" else "재생",
            dueAtM = AlertTiming.dueDistanceM(s.speedMps, s.cruise),
            dueOnM = AlertTiming.dueDistanceM(s.speedMps, Cruise.ACTIVE),
            dueOffM = AlertTiming.dueDistanceM(s.speedMps, Cruise.OFF),
            leadS = AlertTiming.leadSeconds(s.cruise).toInt(),
        )
    }

    // ---------- 말하기 ----------

    private fun enqueue(u: Announcer.Utterance?, card: Card) {
        val now = System.currentTimeMillis()
        if (u != null) {
            val until = cooldown[u.key + u.content]
            if (until != null && now < until) return
            cooldown[u.key + u.content] = now + 30_000
            if (queue.any { it.first?.key == u.key && it.first?.content == u.content }) return
        }
        queue.addLast(u to card)
        log("말함: ${card.text}")
        drain()
    }

    private fun drain() {
        if (busy) return
        val (u, card) = queue.removeFirstOrNull() ?: return
        busy = true
        _ui.value = _ui.value.copy(card = card)
        speaker.speak(card.text) { ok ->
            if (ok && u != null) engine.announcer.confirm(u)
            // '말함' 표시는 TTS 가 실제로 말을 끝낸 뒤에만
            if (ok && card.kind == CardKind.EVENT && _ui.value.announcedAtM == null) _ui.value = _ui.value.copy(announcedAtM = _ui.value.distanceM)
            busy = false
            viewModelScope.launch {
                delay(2500)
                if (!busy && queue.isEmpty() && _ui.value.card == card) _ui.value = _ui.value.copy(card = null)
            }
            if (card.kind == CardKind.ANSWER) scheduleQaClose()
            drain()
        }
    }

    // ---------- 묻고 답하기 ----------

    /** 질문하기: 한 번 누르면 바로 듣는다(STT 가 없으면 질문 버튼 화면만 연다) */
    fun startQuestion() {
        qaCloseJob?.cancel()
        _ui.value = _ui.value.copy(qaOpen = true)
        speaker.stopSpeaking()
        if (!hearer.listen()) log("음성 인식 없음 · 버튼으로 물어 주세요")
    }

    fun closeQa() { hearer.stop(); qaCloseJob?.cancel(); _ui.value = _ui.value.copy(qaOpen = false) }

    fun mic() {
        qaCloseJob?.cancel()
        if (_ui.value.listening) { hearer.stop(); return }
        speaker.stopSpeaking()
        if (!hearer.listen()) log("음성 인식 없음 · 버튼으로 물어 주세요")
    }

    private fun scheduleQaClose() {
        qaCloseJob?.cancel()
        qaCloseJob = viewModelScope.launch { delay(9000); if (!_ui.value.listening) _ui.value = _ui.value.copy(qaOpen = false) }
    }

    fun ask(question: String) {
        qaCloseJob?.cancel()
        val st = _ui.value
        val fresh = when (st.mode) {
            Mode.REPLAY -> true
            Mode.LIVE -> liveFetchedAt?.let { Duration.between(it, LocalDateTime.now()).seconds <= Announcer.MAX_PUBLIC_AGE_S } ?: false
        }
        val qctx = QaEngine.Context(
            incident = st.incident, distanceM = st.distanceM, recordLabel = st.recordLabel,
            cruise = st.cruise, cruiseTargetKmh = st.cruiseTargetKmh, cruiseSource = st.cruiseSource,
            relation = st.relation, recordFresh = fresh,
            recordActive = st.incident?.endAt?.isAfter(LocalDateTime.now()) ?: true,
        )
        val a = QaEngine.answer(question, qctx)
        _ui.value = _ui.value.copy(qaOpen = true, qa = (st.qa + QA(question, a.text, a.basis)).takeLast(4))
        log("물음: $question → ${a.intent}")
        speaker.stopSpeaking(); queue.clear(); busy = false
        enqueue(null, Card(CardKind.ANSWER, a.text))
    }

    // ---------- 실시간 공공 기록 ----------

    fun toggleLive() {
        if (_ui.value.mode == Mode.LIVE) { selectScenario(_ui.value.scenarioIdx, autoplay = false); return }
        pause()
        engine.reset(); speaker.stopSpeaking(); queue.clear(); busy = false
        _ui.value = _ui.value.copy(mode = Mode.LIVE, liveInfo = "받는 중…", card = null, qa = emptyList(), announcedAtM = null)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ItsLiveClient.fetchExpressway() } }
            r.onSuccess { list ->
                liveIncidents = list; liveFetchedAt = LocalDateTime.now()
                log("실시간 공공 기록 ${list.size}건 받음")
                liveLoop()
            }.onFailure { e ->
                _ui.value = _ui.value.copy(liveInfo = if (!ItsLiveClient.hasKey) "ITS 키 없음(local.properties)" else "받기 실패: ${e.javaClass.simpleName}")
            }
        }
    }

    /** 실시간: 차량 신호는 실제 값만, 앞 사건은 내비 경로 위 사건과 맞춘 것만 말한다 */
    private fun liveLoop() {
        loop?.cancel()
        loop = viewModelScope.launch {
            var t = 0.0
            while (isActive && _ui.value.mode == Mode.LIVE) {
                navi.refresh()
                val sig = vehicle.signals.value
                val n = navi.state.value
                val fetched = liveFetchedAt!!
                val ageS = Duration.between(fetched, LocalDateTime.now()).seconds.toDouble()
                val v = VehicleState(sig.speedMps ?: 0.0, sig.cruise ?: Cruise.UNKNOWN, sig.cruiseTargetKmh)
                val ahead = if (n.lat != null && n.lon != null) NaviMatch.aheadFromNavi(
                    n.lat, n.lon, n.events.map { NaviMatch.NaviEvent(it.type, it.lat, it.lon) },
                    n.congestions.map { NaviMatch.NaviCongestion(it.offsetM, it.avgKmh) },
                    liveIncidents, ageS, LocalDateTime.now(),
                ) else null
                if (ahead != null) engine.step(t, v, ahead).forEach { enqueue(it, Card(if (it.content == "tail") CardKind.TAIL else CardKind.EVENT, it.text)) }
                val near = if (ahead == null && n.lat != null && n.lon != null)
                    liveIncidents.filter { it.lat != null }.minByOrNull { IncidentMatcher.distanceM(n.lat, n.lon, it.lat!!, it.lon!!) } else null
                val shown = ahead?.incident ?: near ?: liveIncidents.firstOrNull { it.hasMainlineLanes }
                val lanes = liveIncidents.count { it.hasMainlineLanes }
                _ui.value = _ui.value.copy(
                    dataLabel = "실시간 · ${fetched.format(DateTimeFormatter.ofPattern("HH:mm"))} 받음",
                    recordLabel = "공공 돌발정보 ${fetched.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))} 받음",
                    relation = if (ahead != null) QaEngine.Relation.AHEAD else QaEngine.Relation.NEARBY,
                    incident = shown, distanceM = ahead?.distanceM, congestionM = ahead?.congestionOffsetM,
                    speedKmh = sig.speedMps?.let { it * 3.6 }, speedSource = if (sig.speedMps != null) "PERF_VEHICLE_SPEED" else "없음",
                    cruise = v.cruise, cruiseTargetKmh = sig.cruiseTargetKmh, cruiseSource = if (sig.cruise != null) "Pleos Vehicle SDK" else "없음",
                    dueAtM = AlertTiming.dueDistanceM(v.speedMps, v.cruise),
                    dueOnM = AlertTiming.dueDistanceM(v.speedMps, Cruise.ACTIVE), dueOffM = AlertTiming.dueDistanceM(v.speedMps, Cruise.OFF),
                    leadS = AlertTiming.leadSeconds(v.cruise).toInt(),
                    liveInfo = "고속도로 기록 ${liveIncidents.size}건 · 본선 차로 통제 ${lanes}건 · " +
                        if (ahead != null) "내비 경로 위 사건과 맞춤" else "내비 경로 위 사건 없음 → 주변 기록만 표시, 음성 안내 안 함",
                )
                t += 1.0
                delay(1000)
            }
        }
    }

    private fun log(s: String) {
        val t = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        _ui.value = _ui.value.copy(log = (_ui.value.log + "$t $s").takeLast(30))
    }

    override fun onCleared() {
        loop?.cancel(); vehicle.stop(); navi.stop(); speaker.release(); hearer.release()
    }
}
