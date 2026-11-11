package io.lanealert.pleos

import android.content.Context
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pleos NaviHelper SDK 3.0.5 (권한 pleos.car.permission.NAVI_ROUTE).
 * - 도로 제한속도(onCurrentRoadSpeedLimit, 매초)
 * - 경로 상태의 정체 목록(offset·avrSpeed)과 사건 목록(좌표·eventType) → 공공 돌발 기록과 좌표로 맞춤
 * - 현재 위치 → 실시간 모드에서 가까운 공공 기록 찾기
 */
class PleosNaviSource(private val context: Context) {

    data class Congestion(val offsetM: Double, val lengthM: Double, val avgKmh: Double)
    data class NaviEvent(val type: String, val lat: Double, val lon: Double, val description: String)

    data class State(
        val status: String = "연결 전",
        val navStatus: String? = null,
        val speedLimitKmh: Int? = null,
        val lat: Double? = null,
        val lon: Double? = null,
        val congestions: List<Congestion> = emptyList(),
        val events: List<NaviEvent> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var helper: NaviHelper? = null

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentRoadSpeedLimit(limit: Int) {
            _state.value = _state.value.copy(speedLimitKmh = limit.takeIf { it > 0 })
        }
        override fun onNavigationStatus(status: NavigationStatus) {
            _state.value = _state.value.copy(navStatus = status.name)
        }
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            _state.value = _state.value.copy(lat = info.latitude, lon = info.longitude)
        }
        override fun onRouteStateInfo(info: RouteStateInfo) {
            val ts = info.trafficStatus ?: return
            _state.value = _state.value.copy(
                congestions = ts.congestionItemList.orEmpty().map { Congestion(it.offset, it.distance, it.avrSpeed) },
                events = ts.trafficEventList.orEmpty().map { NaviEvent(it.eventType.name, it.latitude, it.longitude, it.description.orEmpty()) },
            )
        }
        override fun onError(code: NaviErrorCode) {
            _state.value = _state.value.copy(status = "NaviHelper 오류: ${code.name}")
        }
    }

    fun start() {
        try {
            val h = NaviHelper(context)
            h.initialize({
                _state.value = _state.value.copy(status = "NaviHelper 연결됨")
                try { h.getRouteStateInfo(); h.getCurrentLocationInfo() } catch (_: Throwable) {}
            }, { t ->
                _state.value = _state.value.copy(status = "NaviHelper 연결 실패(${t.javaClass.simpleName})")
            })
            h.addListener(listener)
            helper = h
        } catch (t: Throwable) {
            Log.w("PleosNavi", "NaviHelper unavailable", t)
            _state.value = _state.value.copy(status = "NaviHelper 없음(${t.javaClass.simpleName})")
        }
    }

    /** 경로 상태·현재 위치를 다시 묻는다 (결과는 리스너로 옴) */
    fun refresh() {
        try { helper?.getRouteStateInfo(); helper?.getCurrentLocationInfo() } catch (_: Throwable) {}
    }

    fun stop() {
        try { helper?.removeListener(listener); helper?.release() } catch (_: Throwable) {}
        helper = null
    }
}
