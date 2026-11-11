package io.lanealert.pleos

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.listener.CruiseControlStateListener
import ai.pleos.playground.vehicle.listener.CruiseControlTargetSpeedListener
import io.lanealert.core.Cruise
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 실제 차량 신호.
 * - 크루즈 상태·설정 속도: Pleos Vehicle SDK 2.3.2 (DrivingMode, 권한 pleos.car.permission.CAR_DRIVE)
 * - 차속: AAOS 표준 CarPropertyManager PERF_VEHICLE_SPEED (m/s). Pleos FAQ 가 안내하는 방법.
 * 연결이 안 되면(일반 휴대폰·에뮬레이터) 값은 null 로 남고, 앱은 재생 신호를 쓴다.
 */
class PleosVehicleSource(private val context: Context) {

    data class Signals(
        val cruise: Cruise? = null,
        val cruiseTargetKmh: Double? = null,
        val speedMps: Double? = null,
        val vehicleStatus: String = "연결 전",
        val speedStatus: String = "연결 전",
    )

    private val _signals = MutableStateFlow(Signals())
    val signals: StateFlow<Signals> = _signals

    private var vehicle: Vehicle? = null
    private var car: Car? = null

    private val cruiseListener = object : CruiseControlStateListener {
        override fun onCruiseControlStateUpdated(state: CruiseControl?) {
            _signals.value = _signals.value.copy(cruise = Cruise.fromPleosName(state?.name), vehicleStatus = "Vehicle SDK 수신 중")
        }
        override fun onFailed(e: Exception) {
            _signals.value = _signals.value.copy(vehicleStatus = "크루즈 수신 실패: ${e.javaClass.simpleName}")
        }
    }
    private val targetListener = object : CruiseControlTargetSpeedListener {
        override fun onCruiseControlTargetSpeedUpdated(mps: Float?) {
            _signals.value = _signals.value.copy(cruiseTargetKmh = mps?.let { it * 3.6 })
        }
        override fun onFailed(e: Exception) {}
    }
    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(v: CarPropertyValue<*>) {
            val mps = (v.value as? Float)?.toDouble() ?: return
            _signals.value = _signals.value.copy(speedMps = mps, speedStatus = "PERF_VEHICLE_SPEED 수신 중")
        }
        override fun onErrorEvent(propId: Int, zone: Int) {
            _signals.value = _signals.value.copy(speedStatus = "차속 수신 오류")
        }
    }

    fun start() {
        // 1) Pleos Vehicle SDK
        try {
            val v = Vehicle(context)
            v.initialize()
            val dm = v.getDrivingMode()
            dm.getCruiseControlState({ s ->
                _signals.value = _signals.value.copy(cruise = Cruise.fromPleosName(s?.name), vehicleStatus = "Vehicle SDK 연결됨")
            }, { e ->
                _signals.value = _signals.value.copy(vehicleStatus = "Vehicle SDK 응답 없음: ${e.javaClass.simpleName}")
            })
            dm.getCruiseControlTargetSpeed({ f -> _signals.value = _signals.value.copy(cruiseTargetKmh = f?.let { it * 3.6 }) }, { })
            dm.registerCruiseControlState(cruiseListener)
            dm.registerCruiseControlTargetSpeed(targetListener)
            vehicle = v
        } catch (t: Throwable) {
            Log.w(TAG, "Vehicle SDK unavailable", t)
            _signals.value = _signals.value.copy(vehicleStatus = "Vehicle SDK 없음(${t.javaClass.simpleName})")
        }
        // 2) AAOS 차속
        try {
            if (context.packageManager.hasSystemFeature("android.hardware.type.automotive")) {
                val c = Car.createCar(context)
                val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                pm.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                car = c
                _signals.value = _signals.value.copy(speedStatus = "차속 구독함")
            } else {
                _signals.value = _signals.value.copy(speedStatus = "차량 기기 아님")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Car speed unavailable", t)
            _signals.value = _signals.value.copy(speedStatus = "차속 없음(${t.javaClass.simpleName})")
        }
    }

    fun stop() {
        try {
            vehicle?.getDrivingMode()?.unregisterCruiseControlState(cruiseListener)
            vehicle?.getDrivingMode()?.unregisterCruiseControlTargetSpeed(targetListener)
            vehicle?.release()
        } catch (_: Throwable) {}
        try {
            (car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager)?.unregisterCallback(speedCallback)
            car?.disconnect()
        } catch (_: Throwable) {}
        vehicle = null; car = null
    }

    companion object { private const val TAG = "PleosVehicle" }
}
