package io.lanealert.pleos

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import android.speech.tts.TextToSpeech as AndroidTts
import android.speech.tts.UtteranceProgressListener
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError

/**
 * 말하기. Pleos TextToSpeech SDK ON_DEVICE 모드(앱 등록 불필요, 네트워크 불필요)를 먼저 쓰고,
 * 안 되면 안드로이드 기본 TTS 로 넘어간다. 실제로 말을 끝냈을 때만 onDone 을 부른다(→ Announcer.confirm).
 */
class Speaker(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val _engine = MutableStateFlow("준비 중")
    val engine: StateFlow<String> = _engine
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    private var pleos: TextToSpeech? = null
    private var pleosReady = false
    private var fallback: AndroidTts? = null
    private var fallbackReady = false
    private var pending: (() -> Unit)? = null

    fun start() {
        try {
            val t = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
            val events = object : EventListener {
                override fun onStart() { main.post { _speaking.value = true } }
                override fun onReady() {}
                override fun onDone() { main.post { finish(true) } }
                override fun onError(e: TtsError) { Log.w(TAG, "pleos tts error $e"); main.post { finish(false) } }
                override fun onStop() { main.post { finish(false) } }
                override fun onUpdatedRms(rms: Double) {}
            }
            // initialize() 뒤에 리스너를 붙여야 한다(먼저 붙이면 SDK 내부 lateinit 예외)
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    try { t.addEventListener(events) } catch (e: Throwable) { Log.w(TAG, "listener", e) }
                    pleosReady = true
                    try { t.setLocale(Locale.KOREA) } catch (_: Throwable) {}
                    _engine.value = "Pleos TTS (ON_DEVICE)"
                }
                override fun onFailure(e: TtsError) {
                    Log.w(TAG, "pleos tts init fail $e"); startAndroid("Pleos TTS 초기화 실패($e)")
                }
            })
            pleos = t
            // 3초 안에 준비가 안 되면 기본 TTS 도 준비해 둔다
            main.postDelayed({ if (!pleosReady) startAndroid("Pleos TTS 응답 없음") }, 3000)
        } catch (t: Throwable) {
            Log.w(TAG, "pleos tts unavailable", t)
            startAndroid("Pleos TTS 없음")
        }
    }

    private fun startAndroid(reason: String) {
        if (fallback != null) return
        fallback = AndroidTts(context.applicationContext) { st ->
            fallbackReady = st == AndroidTts.SUCCESS
            fallback?.language = Locale.KOREA
            if (!pleosReady) _engine.value = if (fallbackReady) "기본 TTS ($reason)" else "음성 출력 없음 ($reason)"
        }
        fallback?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { _speaking.value = true }
            override fun onDone(id: String?) { main.post { finish(true) } }
            @Deprecated("") override fun onError(id: String?) { main.post { finish(false) } }
        })
    }

    private var onDoneCb: ((Boolean) -> Unit)? = null

    private fun finish(ok: Boolean) {
        _speaking.value = false
        val cb = onDoneCb; onDoneCb = null
        cb?.invoke(ok)
    }

    /** 말한다. 다 말하면 onDone(true), 실패하면 onDone(false). 음성 엔진이 없으면 화면 카드만 보이고 false. */
    fun speak(text: String, onDone: (Boolean) -> Unit) {
        onDoneCb?.invoke(false)
        onDoneCb = onDone
        try {
            when {
                pleosReady -> { _speaking.value = true; pleos!!.speak(text, true) }
                fallbackReady -> {
                    _speaking.value = true
                    fallback!!.speak(text, AndroidTts.QUEUE_FLUSH, null, "u${System.nanoTime()}")
                }
                else -> {
                    // 소리 없이 카드만: 읽는 시간만큼 보여 준 뒤 실패로 돌려준다(말함으로 기록하지 않음)
                    _speaking.value = true
                    main.postDelayed({ finish(false) }, 2500)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "speak failed", t); finish(false)
        }
    }

    fun stopSpeaking() {
        try { pleos?.stop() } catch (_: Throwable) {}
        try { fallback?.stop() } catch (_: Throwable) {}
        finish(false)
    }

    fun release() {
        try { pleos?.release() } catch (_: Throwable) {}
        try { fallback?.shutdown() } catch (_: Throwable) {}
    }

    companion object { private const val TAG = "Speaker" }
}

/**
 * 듣기. Pleos SpeechToText SDK ON_DEVICE 모드. 버튼을 누를 때만 마이크를 켠다(상시 청취 없음).
 * 안 되면 화면의 질문 버튼으로 묻는다.
 */
class Hearer(private val context: Context) {
    private val _status = MutableStateFlow("준비 중")
    val status: StateFlow<String> = _status
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial
    var ready = false; private set
    private var stt: SpeechToText? = null
    var onFinal: ((String) -> Unit)? = null

    fun start() {
        try {
            val s = SpeechToText(context.applicationContext, SttMode.ON_DEVICE)
            s.addListener(object : ResultListener {
                override fun onUpdated(text: String, isFinal: Boolean) {
                    _partial.value = text
                    if (isFinal) { _listening.value = false; if (text.isNotBlank()) onFinal?.invoke(text) }
                }
                override fun onUpdatedEpdData(a: Long, b: Long) {}
                override fun onStartedRecognition() { _listening.value = true }
                override fun onEndedRecognition() { _listening.value = false }
                override fun onError(e: SttError) { _listening.value = false; _status.value = "STT 오류($e)" }
                override fun onReady() {}
                override fun onUpdatedRms(rms: Double) {}
            })
            s.initialize(object : SttInit {
                override fun onSuccess() {
                    ready = true
                    try { s.setLocale(Locale.KOREA) } catch (_: Throwable) {}
                    _status.value = "Pleos STT (ON_DEVICE)"
                }
                override fun onFailure(e: SttError) { _status.value = "STT 초기화 실패($e) · 버튼으로 묻기" }
            })
            stt = s
        } catch (t: Throwable) {
            _status.value = "STT 없음 · 버튼으로 묻기"
        }
    }

    fun listen(): Boolean {
        val s = stt ?: return false
        if (!ready) return false
        return try { _partial.value = ""; s.request(); _listening.value = true; true } catch (t: Throwable) { false }
    }

    fun stop() { try { stt?.stop() } catch (_: Throwable) {}; _listening.value = false }
    fun release() { try { stt?.release() } catch (_: Throwable) {} }
}
