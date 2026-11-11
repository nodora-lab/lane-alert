package io.lanealert.core

/**
 * 앱 안에서 쓰는 크루즈 상태. Pleos Vehicle SDK 2.3.2 의 CruiseControl 6값을 묶은 것.
 * OTHER -> UNKNOWN, ENABLED -> STANDBY, ACTIVATED -> ACTIVE, USER_OVERRIDE -> OVERRIDE,
 * SUSPENDED -> STANDBY, FORCED_DEACTIVATION_WARNING -> WARNING
 */
enum class Cruise(val ko: String) {
    OFF("꺼짐"), STANDBY("대기"), ACTIVE("작동 중"), OVERRIDE("가속 페달 우선"), WARNING("해제 경고"), UNKNOWN("확인 안 됨");

    companion object {
        fun fromPleosName(name: String?): Cruise = when (name) {
            "ENABLED", "SUSPENDED" -> STANDBY
            "ACTIVATED" -> ACTIVE
            "USER_OVERRIDE" -> OVERRIDE
            "FORCED_DEACTIVATION_WARNING" -> WARNING
            "OTHER" -> UNKNOWN
            null -> UNKNOWN
            else -> UNKNOWN
        }
    }
}

/**
 * 언제 말할지. 내 속도로 사건 지점까지 걸리는 시간(거리/속도)이 [leadSeconds] 안으로 들어오면 말한다.
 * 크루즈가 작동 중이면 차가 같은 속도를 유지하므로 더 일찍 말한다.
 * 문턱값은 [가정]. 실차·에뮬레이터 시험으로 정한다.
 */
object AlertTiming {
    const val BASE_LEAD_S = 60.0
    const val CRUISE_LEAD_S = 120.0
    /** 속도와 상관없이 이 거리 안이면 말한다(정체로 거의 서 있을 때) */
    const val ALWAYS_WITHIN_M = 700.0
    /** 이보다 멀면 말하지 않는다 */
    const val MAX_DISTANCE_M = 8000.0

    fun leadSeconds(cruise: Cruise): Double = if (cruise == Cruise.ACTIVE) CRUISE_LEAD_S else BASE_LEAD_S

    /** 지금 속도에서 몇 m 앞에서 말하게 되는지 (화면 표시용) */
    fun dueDistanceM(speedMps: Double, cruise: Cruise): Double =
        maxOf(ALWAYS_WITHIN_M, minOf(MAX_DISTANCE_M, speedMps.coerceAtLeast(0.0) * leadSeconds(cruise)))

    fun isDue(distanceM: Double, speedMps: Double, cruise: Cruise): Boolean {
        if (!distanceM.isFinite() || distanceM <= 0 || distanceM > MAX_DISTANCE_M) return false
        if (distanceM <= ALWAYS_WITHIN_M) return true
        if (!speedMps.isFinite() || speedMps < 0.5) return false
        return distanceM / speedMps <= leadSeconds(cruise)
    }
}
