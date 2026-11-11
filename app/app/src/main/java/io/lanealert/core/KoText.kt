package io.lanealert.core

import kotlin.math.roundToInt

/** 음성·화면 문장에 쓰는 한국어 표기 도우미 (speak/announce.py 의 km_text·topic·lane_text 이식). */
object KoText {

    /** 1km 이상은 0.1km 단위, 미만은 100m 단위. 1500 -> "1.5km", 3000 -> "3km", 3333 -> "3.3km", 800 -> "800m"
     *  (파이썬 판은 0.5km 단위였으나 3.33km 를 "3.5km"로 말해 실제보다 멀게 들려 0.1km 로 바꿈) */
    fun km(m: Double): String {
        if (m >= 1000) {
            val tenths = (m / 100).roundToInt()
            return if (tenths % 10 == 0) "${tenths / 10}km" else "${tenths / 10}.${tenths % 10}km"
        }
        return "${((m / 100).roundToInt() * 100)}m"
    }

    /** 받침에 따라 은/는 */
    fun topic(word: String): String {
        val ch = word.lastOrNull() ?: return word
        if (ch in '가'..'힣') return word + if ((ch.code - 0xAC00) % 28 != 0) "은" else "는"
        return word + "은(는)"
    }

    /** 받침에 따라 이/가 */
    fun subject(word: String): String {
        val ch = word.lastOrNull() ?: return word
        if (ch in '가'..'힣') return word + if ((ch.code - 0xAC00) % 28 != 0) "이" else "가"
        return word + "이(가)"
    }

    /** [2] -> "2차로", [4,5] -> "4, 5차로" */
    fun lanes(lanes: List<Int>): String? {
        if (lanes.isEmpty()) return null
        return lanes.sorted().joinToString(", ") + "차로"
    }

    /** [2] -> "2차로 통제" */
    fun laneControl(lanes: List<Int>): String? = lanes(lanes)?.let { "$it 통제" }

    /** 운전자에게 차로 이동을 지시하는 말. 앱은 이 말을 절대 하지 않는다. */
    val FORBIDDEN = listOf("바꾸세요", "변경하세요", "이동하세요", "들어가세요", "빠지세요", "가세요", "타세요")

    /** 크루즈를 믿어도 된다/앞차를 인식했다 류의 말. 앱은 하지 않는다. */
    val FORBIDDEN_TRUST = listOf("믿어도", "안전해요", "인식했", "알아서 멈")

    fun isSafe(text: String): Boolean =
        FORBIDDEN.none { it in text } && FORBIDDEN_TRUST.none { it in text }
}
