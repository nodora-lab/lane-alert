package io.lanealert.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.lanealert.core.Cruise
import io.lanealert.core.Incident
import io.lanealert.core.KoText
import io.lanealert.core.QaEngine
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object C {
    val bg = Color(0xFF0A0D11)
    val panel = Color(0xFF141920)
    val panel2 = Color(0xFF1C222B)
    val line = Color(0xFF2A313B)
    val text = Color(0xFFF4F6F8)
    val sub = Color(0xFFB4BCC6)
    val dim = Color(0xFF7D8793)
    val orange = Color(0xFFFFB020)
    val ink = Color(0xFF1A1200)
    val cyan = Color(0xFF4CC9F0)
    val blue = Color(0xFF1F6FEB)
    val slate = Color(0xFF3A4554)
}

/** 1600dp 폭 기준으로 그린 화면을 실제 화면 폭에 맞춰 키운다(Pleos 2560x1440, 일반 기기 모두) */
@Composable
fun Scaled(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(C.bg)) {
        val d = LocalDensity.current
        val scale = maxWidth.value / 1600f
        CompositionLocalProvider(LocalDensity provides Density(d.density * scale, 1f)) { content() }
    }
}

@Composable
fun DriveScreen(vm: DriveViewModel) {
    val s by vm.ui.collectAsState()
    Scaled {
        Column(Modifier.fillMaxSize()) {
            TopBar(s)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (s.qaOpen) QaPanel(s, vm) else MainPanel(s, vm)
            }
            DemoBar(s, vm)
        }
    }
}

@Composable
private fun TopBar(s: DriveViewModel.UiState) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalTime.now(); delay(10_000) } }
    val inc = s.incident
    Row(
        Modifier.fillMaxWidth().height(72.dp).background(Color(0xFF07090C)).padding(horizontal = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("막힌 차로 알림", color = C.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(18.dp))
        if (inc != null) Text("${inc.roadName} ${inc.direction} 방향", color = C.sub, fontSize = 26.sp, maxLines = 1)
        Spacer(Modifier.weight(1f))
        val live = s.mode == DriveViewModel.Mode.LIVE
        Box(Modifier.border(1.5.dp, if (live) C.cyan else C.dim, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text(s.dataLabel, color = if (live) C.cyan else C.sub, fontSize = 20.sp)
        }
        Spacer(Modifier.width(24.dp))
        Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), color = C.text, fontSize = 28.sp)
    }
}

// ---------------- 주행 화면 ----------------

@Composable
private fun MainPanel(s: DriveViewModel.UiState, vm: DriveViewModel) {
    Row(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 26.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1.58f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AlertCard(s, Modifier.fillMaxWidth().weight(1f))
            val c = s.card
            if (c != null && c.kind != DriveViewModel.CardKind.EVENT) SpokenStrip(c, s.speaking)
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CruiseBlock(s)
            ApproachBlock(s)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MiniStat("현재 속도", s.speedKmh?.let { "${it.toInt()}km/h" } ?: "-", s.speedSource, Modifier.weight(1f))
                MiniStat("정체 시작까지", s.congestionM?.let { KoText.km(it) } ?: "정보 없음",
                    if (s.mode == DriveViewModel.Mode.LIVE) "NaviHelper" else "재생", Modifier.weight(1f))
            }
            Spacer(Modifier.weight(1f))
            AskButton(s.sttReady) { vm.startQuestion() }
        }
    }
}

@Composable
private fun AlertCard(s: DriveViewModel.UiState, modifier: Modifier) {
    val inc = s.incident
    val nearby = s.relation == QaEngine.Relation.NEARBY
    val mainline = inc?.hasMainlineLanes == true
    val hot = inc != null && mainline && !nearby
    val stripText = when {
        inc == null -> "확인된 돌발 기록 없음"
        nearby -> "주변 기록 · 내 경로 앞인지 확인 전"
        else -> "앞 사건 · 공공 돌발정보"
    }
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(C.panel)) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).background(if (hot) C.orange else C.slate).padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stripText, color = if (hot) C.ink else C.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (s.speaking && s.card?.kind == DriveViewModel.CardKind.EVENT) {
                Text("말하는 중", color = if (hot) C.ink else C.cyan, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Bars(if (hot) C.ink else C.cyan)
            }
        }
        if (inc == null) {
            Text("경로 앞에 확인된 돌발 기록이 없어요", color = C.text, fontSize = 48.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(32.dp))
            return@Column
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                val dist = s.distanceM
                if (dist != null && !nearby) {
                    Text(KoText.km(dist), color = C.text, fontSize = 76.sp, fontWeight = FontWeight.Black, lineHeight = 82.sp)
                    Text(" 앞  ", color = C.sub, fontSize = 44.sp, fontWeight = FontWeight.Bold, lineHeight = 60.sp)
                }
                Text(inc.kindKo, color = C.text, fontSize = 56.sp, fontWeight = FontWeight.Bold, lineHeight = 66.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (mainline) {
                Text(KoText.laneControl(inc.lanes)!!, color = C.orange, fontSize = 124.sp, fontWeight = FontWeight.Black, lineHeight = 132.sp)
            } else {
                Text(
                    if (inc.scope == Incident.LaneScope.NONE) "통제 차로 기록 없음" else "본선 통제 기록 없음",
                    color = C.sub, fontSize = 70.sp, fontWeight = FontWeight.Bold, lineHeight = 86.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (mainline) LaneStrip(inc, Modifier.fillMaxWidth().weight(1f))
            else { ScopeBox(inc, Modifier.fillMaxWidth()); Spacer(Modifier.weight(1f)) }
            Spacer(Modifier.height(12.dp))
            Text(
                "근거: ${s.recordLabel}. 차로별 속도는 알 수 없어요." +
                    if (inc.laneFieldMismatch) " 차로 칸 \"${inc.lanesBlockedRaw}\" 대신 기록 문구의 차로 목록을 따름." else "",
                color = C.sub, fontSize = 20.sp, maxLines = 2,
            )
        }
    }
}

/** 차로 번호 그림. 기록에 전체 차로 수가 없어서 막힌 차로 번호까지만 그리고, 오른쪽 끝은 열어 둔다 */
@Composable
private fun LaneStrip(inc: Incident, modifier: Modifier) {
    val shown = maxOf(2, inc.lanes.max())
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Text("차로 번호 (진행 방향 ↑)", color = C.sub, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            Text("전체 차로 수는 기록에 없음", color = C.dim, fontSize = 20.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))) {
            for (n in 1..shown) LaneCell(n, n in inc.lanes, Modifier.weight(1f).fillMaxHeight())
            Canvas(Modifier.weight(0.3f).fillMaxHeight()) {
                drawRect(Color(0xFF181D24), size = size)
                drawLine(Color(0x66FFFFFF), Offset(0f, 0f), Offset(0f, size.height), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f)))
            }
        }
    }
}

@Composable
private fun LaneCell(n: Int, blocked: Boolean, modifier: Modifier) {
    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(0xFF262C36), size = size)
            if (blocked) {
                drawRect(C.orange, size = size)
                var x = -size.height
                while (x < size.width) { drawLine(Color(0xFFC98700), Offset(x, size.height), Offset(x + size.height, 0f), 16f); x += 44f }
            }
            if (n > 1) drawLine(Color(0xAAFFFFFF), Offset(0f, 0f), Offset(0f, size.height), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f)))
            else drawLine(Color(0xDDFFFFFF), Offset(2f, 0f), Offset(2f, size.height), 4f)
        }
        Box(
            Modifier.clip(RoundedCornerShape(14.dp)).background(if (blocked) Color(0xFF111111) else Color.Transparent)
                .padding(horizontal = 18.dp, vertical = 2.dp),
        ) { Text(if (blocked) "$n ✕" else "$n", color = if (blocked) C.orange else C.dim, fontSize = 44.sp, fontWeight = FontWeight.Black) }
    }
}

@Composable
private fun ScopeBox(inc: Incident, modifier: Modifier) {
    val label = when (inc.scope) {
        Incident.LaneScope.RAMP_ENTRY -> "진입로"
        Incident.LaneScope.RAMP_EXIT -> "진출로"
        Incident.LaneScope.SHOULDER -> "갓길"
        else -> "구간"
    }
    Row(modifier.clip(RoundedCornerShape(16.dp)).background(C.panel2).padding(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(130.dp).clip(RoundedCornerShape(16.dp)).background(C.slate), contentAlignment = Alignment.Center) {
            Text(label, color = C.text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(28.dp))
        Column {
            Text("기록 문구: ${inc.plainMessage}", color = C.text, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            Text(if (inc.scope == Incident.LaneScope.NONE) "차로 정보가 없는 기록이에요" else "본선 차로가 막혔다는 기록은 없어요", color = C.sub, fontSize = 28.sp)
        }
    }
}

@Composable
private fun SpokenStrip(card: DriveViewModel.Card, speaking: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF0E1A22))
            .border(2.dp, C.cyan, RoundedCornerShape(20.dp)).padding(horizontal = 28.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(card.kind.label + if (speaking) ", 말하는 중" else "", color = C.cyan, fontSize = 22.sp)
            Text(card.text, color = C.text, fontSize = 38.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp)
        }
        if (speaking) Bars(C.cyan)
    }
}

@Composable
private fun CruiseBlock(s: DriveViewModel.UiState) {
    val on = s.cruise == Cruise.ACTIVE
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (on) Color(0xFF0E1E27) else C.panel2)
            .border(2.dp, if (on) C.cyan else C.line, RoundedCornerShape(20.dp)).padding(horizontal = 28.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("크루즈", color = C.sub, fontSize = 26.sp)
            Spacer(Modifier.weight(1f))
            Text("출처: ${s.cruiseSource}", color = C.dim, fontSize = 18.sp)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(s.cruise.ko, color = if (on) C.cyan else C.text, fontSize = 52.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(16.dp))
            if (on && s.cruiseTargetKmh != null) Text("설정 ${s.cruiseTargetKmh.toInt()}km/h", color = C.sub, fontSize = 28.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        Text(if (on) "그래서 도착 ${s.leadS}초 전에 먼저 말해요" else "도착 ${s.leadS}초 전에 말해요",
            color = if (on) C.text else C.sub, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 말하는 지점 막대: 사건까지 남은 거리 위에 "크루즈 켬/끔일 때 말하는 곳"과 내 차 위치를 그린다 */
@Composable
private fun ApproachBlock(s: DriveViewModel.UiState) {
    val total = maxOf(s.startDistanceM, (s.dueOnM ?: 0.0) + 500.0)
    val on = s.cruise == Cruise.ACTIVE
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.panel2).padding(horizontal = 28.dp, vertical = 16.dp)) {
        Row {
            Text("말하는 지점", color = C.sub, fontSize = 26.sp)
            Spacer(Modifier.weight(1f))
            s.announcedAtM?.let { Text("${KoText.km(it)} 앞에서 말함", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(66.dp)) {
            val w = size.width; val y = size.height * 0.55f
            fun xOf(m: Double) = (w * (1 - (m / total).coerceIn(0.0, 1.0))).toFloat()
            drawRoundRect(Color(0xFF2E3640), Offset(0f, y - 7f), Size(w, 14f), CornerRadius(7f))
            s.dueOffM?.let { m -> val x = xOf(m); drawLine(if (!on) C.text else C.dim, Offset(x, y - 26f), Offset(x, y + 26f), if (!on) 7f else 4f) }
            s.dueOnM?.let { m -> val x = xOf(m); drawLine(if (on) C.cyan else C.dim, Offset(x, y - 26f), Offset(x, y + 26f), if (on) 7f else 4f) }
            drawLine(C.orange, Offset(w - 16f, y - 16f), Offset(w, y + 16f), 6f); drawLine(C.orange, Offset(w - 16f, y + 16f), Offset(w, y - 16f), 6f)
            s.distanceM?.let { m ->
                val x = xOf(m)
                drawPath(Path().apply { moveTo(x, y - 30f); lineTo(x - 16f, y + 6f); lineTo(x + 16f, y + 6f); close() }, Color.White)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("크루즈 켬 ${s.dueOnM?.let { KoText.km(it) } ?: "-"}", color = if (on) C.cyan else C.dim, fontSize = 22.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
            Spacer(Modifier.width(18.dp))
            Text("끔 ${s.dueOffM?.let { KoText.km(it) } ?: "-"}", color = if (!on) C.text else C.dim, fontSize = 22.sp, fontWeight = if (!on) FontWeight.Bold else FontWeight.Normal)
            Spacer(Modifier.weight(1f))
            Text("사건 지점", color = C.orange, fontSize = 22.sp)
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, source: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(C.panel2).padding(horizontal = 22.dp, vertical = 12.dp)) {
        Text(label, color = C.sub, fontSize = 22.sp)
        Text(value, color = C.text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text(source, color = C.dim, fontSize = 16.sp, maxLines = 1)
    }
}

@Composable
private fun AskButton(sttReady: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(108.dp).clip(RoundedCornerShape(54.dp)).background(C.blue).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MicGlyph(Color.White)
            Spacer(Modifier.width(18.dp))
            Text(if (sttReady) "말로 묻기" else "질문하기", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MicGlyph(color: Color) {
    Canvas(Modifier.size(width = 30.dp, height = 44.dp)) {
        val w = size.width; val h = size.height
        drawRoundRect(color, Offset(w * 0.25f, 0f), Size(w * 0.5f, h * 0.62f), CornerRadius(w * 0.25f))
        drawArc(color, 0f, 180f, false, Offset(w * 0.05f, h * 0.2f), Size(w * 0.9f, h * 0.6f), style = Stroke(4f))
        drawLine(color, Offset(w / 2, h * 0.8f), Offset(w / 2, h), 4f)
    }
}

@Composable
private fun Bars(color: Color) {
    var t by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { t++; delay(160) } }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf(0.5f, 0.9f, 0.6f, 1f, 0.7f).forEachIndexed { i, h ->
            val k = 0.45f + 0.55f * (((t + i * 2) % 5) / 4f)
            Box(Modifier.width(7.dp).height((30 * h * k).dp).clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}

// ---------------- 묻고 답하기 ----------------

private val PRIMARY = listOf("몇 차로야?", "왜 막혀?", "얼마나 남았어?")
private val SECONDARY = listOf("언제 풀려?", "어느 차로가 빨라?")

@Composable
private fun QaPanel(s: DriveViewModel.UiState, vm: DriveViewModel) {
    Row(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 26.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(
            Modifier.weight(1.58f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(C.panel).padding(36.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val last = s.qa.lastOrNull()
            if (last == null) {
                Text(if (s.listening) "듣고 있어요" else "짧게 물어보세요", color = C.text, fontSize = 52.sp, fontWeight = FontWeight.Bold)
                Text("확인된 기록으로만 한두 문장으로 답해요", color = C.sub, fontSize = 30.sp)
                if (s.partial.isNotBlank()) Text("“${s.partial}”", color = C.cyan, fontSize = 34.sp)
            } else {
                Box(Modifier.clip(RoundedCornerShape(18.dp)).background(C.blue).padding(horizontal = 24.dp, vertical = 10.dp)) {
                    Text(last.q, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Medium)
                }
                Text(last.a, color = C.text, fontSize = 50.sp, fontWeight = FontWeight.Bold, lineHeight = 66.sp)
                if (last.basis.isNotBlank()) Text(last.basis, color = C.sub, fontSize = 22.sp)
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.speaking) { Bars(C.cyan); Spacer(Modifier.width(12.dp)); Text("말하는 중", color = C.cyan, fontSize = 22.sp) }
                    else Text("잠시 뒤 주행 화면으로 돌아가요", color = C.dim, fontSize = 22.sp)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(180.dp).clip(CircleShape).background(if (s.listening) Color(0xFF0E1E27) else C.panel2)
                    .border(7.dp, if (s.listening) C.cyan else C.line, CircleShape).clickable { vm.mic() },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MicGlyph(if (s.listening) C.cyan else C.text)
                    Text(if (s.listening) "듣는 중" else "말하기", color = if (s.listening) C.cyan else C.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            PRIMARY.forEach { q -> QaChip(q, big = true, Modifier.fillMaxWidth()) { vm.ask(q) } }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { SECONDARY.forEach { q -> QaChip(q, big = false, Modifier.weight(1f)) { vm.ask(q) } } }
            Spacer(Modifier.weight(1f))
            QaChip("주행 화면으로", big = false, Modifier.fillMaxWidth(), muted = true) { vm.closeQa() }
        }
    }
}

@Composable
private fun QaChip(label: String, big: Boolean, modifier: Modifier, muted: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.height(if (big) 84.dp else 68.dp).clip(RoundedCornerShape(42.dp)).background(if (muted) C.panel else C.panel2)
            .border(1.5.dp, C.line, RoundedCornerShape(42.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (muted) C.sub else C.text, fontSize = if (big) 32.sp else 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
}

// ---------------- 발표자 조작 (정차 중) ----------------

@Composable
private fun DemoBar(s: DriveViewModel.UiState, vm: DriveViewModel) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF05070A)).padding(horizontal = 24.dp, vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Chip(if (s.demoOpen) "시연 조작 ▾" else "시연 조작 ▸", selected = s.demoOpen) { vm.toggleDemo() }
            if (s.demoOpen) {
                s.scenarios.forEachIndexed { i, sc ->
                    Chip("${i + 1}. ${sc.title}", selected = s.mode == DriveViewModel.Mode.REPLAY && i == s.scenarioIdx) { vm.selectScenario(i) }
                }
                Chip(if (s.playing) "멈춤" else "재생", selected = s.playing) { vm.togglePlay() }
                Chip("4배속", selected = s.fast) { vm.toggleFast() }
                Chip("크루즈 켜기/끄기", selected = false) { vm.toggleReplayCruise() }
                Chip("차량 신호 사용", selected = s.useCarSignals) { vm.toggleCarSignals() }
                Chip("실시간 공공 기록", selected = s.mode == DriveViewModel.Mode.LIVE) { vm.toggleLive() }
            }
        }
        if (s.demoOpen) Text(
            listOf("Vehicle: ${s.vehicleStatus}", "차속: ${s.speedStatus}", "Navi: ${s.naviStatus}",
                "TTS: ${s.ttsEngine}", "STT: ${s.sttStatus}", s.liveInfo).filter { it.isNotBlank() }.joinToString("   |   "),
            color = C.dim, fontSize = 15.sp, maxLines = 1,
        )
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(if (selected) Color(0xFF233244) else C.panel)
            .border(1.dp, if (selected) C.cyan else C.line, RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(label, color = if (selected) C.cyan else C.sub, fontSize = 18.sp) }
}
