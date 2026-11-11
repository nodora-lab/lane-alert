package io.lanealert.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.lanealert.core.AheadState
import io.lanealert.core.AlertTiming
import io.lanealert.core.Announcer
import io.lanealert.core.Cruise
import io.lanealert.core.DriveEngine
import io.lanealert.core.Incident
import io.lanealert.core.IncidentMatcher
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
        val distanceM: Double? = null,
        val congestionM: Double? = null,
        val speedKmh: Double? = null,
        val cruise: Cruise = Cruise.UNKNOWN,
        val cruiseTargetKmh: Double? = null,
        val cruiseSource: String = "재생",
        val useCarSignals: Boolean = false,
        val speedLimitKmh: Int? = null,
        val dueAtM: Double? = null,
        val leadS: Int = AlertTiming.BASE_LEAD_S.toInt(),
        val observedMinAgo: Int? = null,
        val card: Card? = null,
        val speaking: Boolean = false,
        val qaOpen: Boolean = false,
        val qa: List<QA> = emptyList(),
        val listening: Boolean = false,
        val partial: String = "",
        val ttsEngine: String = "",
        val sttStatus: String = "",
        val vehicleStatus: String = "",
        val speedStatus: String = "",
        val naviStatus: String = "",
        val liveInfo: String = "",
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
    private val cooldown = HashMap<String, Double>()
    private var liveIncidents: List<Incident> = emptyList()
    private var liveFetchedAt: LocalDateTime? = null

    init {
        bundle = runCatching { ScenarioRepo.load(ctx) }.getOrNull()
        val b = bundle
        _ui.value = _ui.value.copy(
            scenarios = b?.scenarios.orEmpty(),
            dataLabel = b?.let { "재생 · 실제 기록 ${it.fetchedAt.substring(5, 16)}" } ?: "기록 없음",
        )
        vehicle.start(); navi.start(); speaker.start(); hearer.start()
        hearer.onFinal = { text -> ask(text) }
        viewModelScope.launch { vehicle.signals.collect { s -> _ui.value = _ui.value.copy(vehicleStatus = s.vehicleStatus, speedStatus = s.speedStatus) } }
        viewModelScope.launch { navi.state.collect { n -> _ui.value = _ui.value.copy(naviStatus = n.status, speedLimitKmh = n.speedLimitKmh) } }
        viewModelScope.launch { speaker.engine.collect { _ui.value = _ui.value.copy(ttsEngine = it) } }
        viewModelScope.launch { speaker.speaking.collect { _ui.value = _ui.value.copy(speaking = it) } }
        viewModelScope.launch { hearer.status.collect { _ui.value = _ui.value.copy(sttStatus = it) } }
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
        _ui.value = _ui.value.copy(mode = Mode.REPLAY, scenarioIdx = i, card = null, qa = emptyList(), qaOpen = false,
            finished = false, log = emptyList(), dataLabel = bundle?.let { "재생 · 실제 기록 ${it.fetchedAt.substring(5, 16)}" } ?: "")
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
                    val v = s.vehicle()
                    val a = s.ahead(observedAgeS = 60.0 + s.tS)
                    engine.step(s.tS, v, a).forEach { u -> enqueue(u, Card(if (u.content == "tail") CardKind.TAIL else CardKind.EVENT, u.text)) }
                }
                publish()
                if (s.finished) { _ui.value = _ui.value.copy(playing = false, finished = true); log("사건 지점 통과 · 재생 끝"); break }
                delay(200)
            }
        }
    }

    fun pause() { loop?.cancel(); _ui.value = _ui.value.copy(playing = false) }
    fun toggleFast() { _ui.value = _ui.value.copy(fast = !_ui.value.fast) }

    /** 재생 중 크루즈를 손으로 바꿔 보기(시연용). 차량 신호 사용 중이면 무시 */
    fun toggleReplayCruise() {
        val s = sim ?: return
        if (_ui.value.useCarSignals) return
        s.cruise = if (s.cruise == Cruise.ACTIVE) Cruise.OFF else Cruise.ACTIVE
        if (s.cruise == Cruise.ACTIVE && s.cruiseTargetKmh == null) s.cruiseTargetKmh = s.speedMps * 3.6
        log("크루즈 ${s.cruise.ko} (시연 조작)")
        publish()
    }

    fun toggleCarSignals() {
        _ui.value = _ui.value.copy(useCarSignals = !_ui.value.useCarSignals)
        publish()
    }

    private fun applyCarSignals(s: ReplaySim) {
        if (!_ui.value.useCarSignals) return
        val sig = vehicle.signals.value
        sig.cruise?.let { s.cruise = it }
        sig.cruiseTargetKmh?.let { s.cruiseTargetKmh = it }
    }

    private fun publish() {
        val s = sim ?: return
        val sig = vehicle.signals.value
        val carCruise = _ui.value.useCarSignals && sig.cruise != null
        val due = AlertTiming.dueDistanceM(s.speedMps, s.cruise)
        _ui.value = _ui.value.copy(
            incident = s.scenario.incident,
            distanceM = s.distanceM,
            congestionM = s.congestionOffsetM()?.takeIf { it > 0 },
            speedKmh = s.speedMps * 3.6,
            cruise = s.cruise,
            cruiseTargetKmh = s.cruiseTargetKmh,
            cruiseSource = if (carCruise) "Pleos Vehicle SDK" else "재생",
            dueAtM = due,
            leadS = AlertTiming.leadSeconds(s.cruise).toInt(),
            observedMinAgo = ((60.0 + s.tS) / 60).toInt(),
        )
    }

    // ---------- 말하기 ----------

    private fun enqueue(u: Announcer.Utterance?, card: Card) {
        val now = sim?.tS ?: 0.0
        if (u != null) {
            val until = cooldown[u.key + u.content]
            if (until != null && now < until) return
            cooldown[u.key + u.content] = now + 30.0
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
            busy = false
            viewModelScope.launch {
                delay(2500)
                if (!busy && queue.isEmpty() && _ui.value.card == card) _ui.value = _ui.value.copy(card = null)
            }
            drain()
        }
    }

    // ---------- 묻고 답하기 ----------

    fun openQa() { _ui.value = _ui.value.copy(qaOpen = true) }
    fun closeQa() { hearer.stop(); _ui.value = _ui.value.copy(qaOpen = false) }

    fun mic() {
        if (_ui.value.listening) { hearer.stop(); return }
        speaker.stopSpeaking()
        if (!hearer.listen()) log("음성 인식 없음 · 아래 버튼으로 물어 주세요")
    }

    fun ask(question: String) {
        val st = _ui.value
        val qctx = QaEngine.Context(
            incident = st.incident, distanceM = st.distanceM, observedMinutesAgo = st.observedMinAgo,
            cruise = st.cruise, cruiseTargetKmh = st.cruiseTargetKmh, speedLimitKmh = st.speedLimitKmh,
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
        _ui.value = _ui.value.copy(mode = Mode.LIVE, liveInfo = "받는 중…", card = null)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ItsLiveClient.fetchExpressway() } }
            r.onSuccess { list ->
                liveIncidents = list; liveFetchedAt = LocalDateTime.now()
                val lanes = list.count { it.hasMainlineLanes }
                val n = navi.state.value
                val near = if (n.lat != null && n.lon != null)
                    list.filter { it.lat != null }.minByOrNull { IncidentMatcher.distanceM(n.lat, n.lon, it.lat!!, it.lon!!) } else null
                val shown = near ?: list.firstOrNull { it.hasMainlineLanes }
                val dist = if (near != null && n.lat != null && n.lon != null) IncidentMatcher.distanceM(n.lat, n.lon, near.lat!!, near.lon!!) else null
                _ui.value = _ui.value.copy(
                    dataLabel = "실시간 · ${liveFetchedAt!!.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))} 받음",
                    liveInfo = "고속도로 기록 ${list.size}건 · 본선 차로 통제 ${lanes}건" +
                        if (near == null) " · 현재 위치 없음(내비 연결 전), 예시 1건 표시" else " · 가장 가까운 기록 표시(직선거리)",
                    incident = shown, distanceM = dist, observedMinAgo = 0, congestionM = null,
                )
                log("실시간 공공 기록 ${list.size}건 받음")
            }.onFailure { e ->
                _ui.value = _ui.value.copy(liveInfo = if (!ItsLiveClient.hasKey) "ITS 키 없음(local.properties)" else "받기 실패: ${e.javaClass.simpleName}")
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
