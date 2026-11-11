package io.lanealert.core

/**
 * 시연용 재생. 실제 공공 돌발 기록 1건 + 가상 주행(거리·속도)을 만든다.
 * 화면에는 항상 "재생" 표시를 한다. 차로·사건 내용은 기록 원문 그대로이고, 거리·속도만 가상이다.
 */
data class Scenario(
    val id: String,
    val title: String,
    val record: Map<String, String?>,
    val startDistanceM: Double,
    val speedKmh: Double,
    val cruise: Cruise,
    val cruiseTargetKmh: Double?,
    /** 사건 지점보다 몇 m 앞에서 정체가 시작되는지. null 이면 정체 없음 */
    val congestionBeforeM: Double?,
    val congestionAvgKmh: Double?,
) {
    val incident: Incident by lazy { ItsParser.parse(record) }
}

class ReplaySim(val scenario: Scenario) {
    var tS = 0.0; private set
    var distanceM = scenario.startDistanceM; private set
    var speedMps = scenario.speedKmh / 3.6; private set
    var cruise: Cruise = scenario.cruise
    var cruiseTargetKmh: Double? = scenario.cruiseTargetKmh
    val finished get() = distanceM <= 50.0

    /** 정체 꼬리까지 남은 거리 */
    fun congestionOffsetM(): Double? = scenario.congestionBeforeM?.let { distanceM - it }

    /** 차량 신호 사용 시 실제 차속(m/s). 있으면 가상 속도 대신 이 값으로 거리를 줄인다 */
    var carSpeedMps: Double? = null

    fun step(dt: Double) {
        if (finished) return
        tS += dt
        val tail = congestionOffsetM()
        val car = carSpeedMps
        if (car != null) {
            speedMps = car.coerceAtLeast(0.0)
        } else if (tail != null && tail <= 150.0 && scenario.congestionAvgKmh != null) {
            // 정체 꼬리에서 운전자가 브레이크를 밟는다고 가정 → 크루즈는 대기로 바뀐다
            val target = scenario.congestionAvgKmh / 3.6
            speedMps = maxOf(target, speedMps - 2.5 * dt)
            if (cruise == Cruise.ACTIVE) cruise = Cruise.STANDBY
        } else if (cruise == Cruise.ACTIVE && cruiseTargetKmh != null) {
            speedMps = cruiseTargetKmh!! / 3.6
        }
        distanceM = maxOf(0.0, distanceM - speedMps * dt)
    }

    fun vehicle() = VehicleState(speedMps, cruise, cruiseTargetKmh)

    fun ahead(observedAgeS: Double) = AheadState(
        routeId = "replay-${scenario.id}",
        incident = scenario.incident,
        distanceM = distanceM,
        observedAgeS = observedAgeS,
        matchConf = 1.0,
        congestionId = scenario.congestionBeforeM?.let { "tail-${scenario.id}" },
        congestionOffsetM = congestionOffsetM()?.takeIf { it > 0 },
        congestionAvgKmh = scenario.congestionAvgKmh,
    )
}
