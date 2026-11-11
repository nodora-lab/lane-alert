package io.lanealert.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.lanealert.core.Cruise
import io.lanealert.core.Incident
import io.lanealert.core.KoText
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object C {
    val bg = Color(0xFF0A0D11)
    val panel = Color(0xFF151A21)
    val panel2 = Color(0xFF1C222B)
    val line = Color(0xFF2A313B)
    val text = Color(0xFFF4F6F8)
    val sub = Color(0xFFA9B2BD)
    val dim = Color(0xFF6E7884)
    val orange = Color(0xFFFFB020)
    val cyan = Color(0xFF4CC9F0)
    val blue = Color(0xFF1F6FEB)
    val red = Color(0xFFE5484D)
    val green = Color(0xFF3DD68C)
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
        Modifier.fillMaxWidth().height(76.dp).background(Color(0xFF07090C)).padding(horizontal = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("막힌 차로 알림", color = C.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(18.dp))
        if (inc != null) Text("${inc.roadName} ${inc.direction} 방향", color = C.sub, fontSize = 26.sp)
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.border(1.5.dp, if (s.mode == DriveViewModel.Mode.LIVE) C.green else C.dim, RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) { Text(s.dataLabel, color = if (s.mode == DriveViewModel.Mode.LIVE) C.green else C.sub, fontSize = 20.sp) }
        Spacer(Modifier.width(24.dp))
        Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), color = C.text, fontSize = 28.sp)
    }
}

@Composable
private fun MainPanel(s: DriveViewModel.UiState, vm: DriveViewModel) {
    Row(Modifier.fillMaxSize()) {
        // 왼쪽: 지금 한 줄 + 차로 그림
        Box(Modifier.weight(1.62f).fillMaxHeight().padding(start = 48.dp, end = 40.dp, top = 36.dp, bottom = 28.dp)) {
            Headline(s)
            Box(Modifier.align(Alignment.TopStart)) {
                s.card?.let { SpeakingCard(it, s.speaking) }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(C.line))
        // 오른쪽: 내 차 상태 + 질문하기
        Column(Modifier.weight(1f).fillMaxHeight().padding(36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusRow("크루즈", cruiseText(s), if (s.cruise == Cruise.ACTIVE) C.cyan else C.text, s.cruiseSource)
            StatusRow("내 속도", s.speedKmh?.let { "${it.toInt()}km/h" } ?: "-", C.text, null)
            StatusRow("정체 시작까지", s.congestionM?.let { KoText.km(it) } ?: "정체 정보 없음", if (s.congestionM != null) C.text else C.dim, null)
            StatusRow(
                "알림 시점", s.dueAtM?.let { "${KoText.km(it)} 앞에서" } ?: "-", C.text,
                if (s.cruise == Cruise.ACTIVE) "크루즈 작동 중 → 도착 ${s.leadS}초 전" else "도착 ${s.leadS}초 전",
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(52.dp)).background(C.blue).clickable { vm.openQa() },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(Color.White))
                    Spacer(Modifier.width(16.dp))
                    Text("질문하기", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun cruiseText(s: DriveViewModel.UiState): String =
    if (s.cruise == Cruise.ACTIVE && s.cruiseTargetKmh != null) "작동 중 ${s.cruiseTargetKmh.toInt()}km/h" else s.cruise.ko

@Composable
private fun StatusRow(label: String, value: String, color: Color, note: String?) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.panel2).padding(horizontal = 28.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = C.sub, fontSize = 26.sp)
            Spacer(Modifier.weight(1f))
            Text(value, color = color, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
        if (note != null) Text(note, color = C.dim, fontSize = 19.sp, modifier = Modifier.align(Alignment.End))
    }
}

@Composable
private fun Headline(s: DriveViewModel.UiState) {
    val inc = s.incident
    Column(Modifier.fillMaxSize()) {
        if (inc == null) {
            Text("경로 앞에 확인된 돌발 기록이 없어요", color = C.text, fontSize = 52.sp, fontWeight = FontWeight.Bold)
            return@Column
        }
        val where = s.distanceM?.let { "${KoText.km(it)} 앞" } ?: "앞"
        Text("$where ${inc.kindKo}", color = C.text, fontSize = 58.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        when {
            inc.hasMainlineLanes -> Text(KoText.laneControl(inc.lanes)!!, color = C.orange, fontSize = 112.sp, fontWeight = FontWeight.Black, lineHeight = 120.sp)
            inc.scope == Incident.LaneScope.NONE -> Text("통제 차로 기록 없음", color = C.sub, fontSize = 64.sp, fontWeight = FontWeight.Bold, lineHeight = 80.sp)
            else -> Text("본선 통제 기록 없음", color = C.sub, fontSize = 64.sp, fontWeight = FontWeight.Bold, lineHeight = 80.sp)
        }
        Spacer(Modifier.height(20.dp))
        LaneStrip(inc, Modifier.fillMaxWidth().weight(1f))
        Spacer(Modifier.height(14.dp))
        val ago = s.observedMinAgo?.let { "${it}분 전 받음" } ?: ""
        Text(
            "공공 돌발정보 기록 기준 $ago. 차로별 속도는 알 수 없어요." +
                if (inc.laneFieldMismatch) " 차로 칸(${inc.lanesBlockedRaw})과 기록 메시지가 달라 메시지를 따름." else "",
            color = C.sub, fontSize = 21.sp,
        )
    }
}

/** 차로 번호 그림. 기록에 전체 차로 수가 없어서 막힌 차로 번호까지만 그리고, 오른쪽 끝은 열어 둔다 */
@Composable
private fun LaneStrip(inc: Incident, modifier: Modifier) {
    val mainline = inc.hasMainlineLanes
    val shownLanes = if (mainline) maxOf(2, inc.lanes.max()) else 3
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Text("차로 번호, 진행 방향 ↑", color = C.sub, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            Text(if (mainline) "전체 차로 수는 기록에 없음" else when (inc.scope) {
                Incident.LaneScope.RAMP_ENTRY -> "진입로 기록"
                Incident.LaneScope.RAMP_EXIT -> "진출로 기록"
                Incident.LaneScope.SHOULDER -> "갓길 기록"
                else -> ""
            }, color = C.dim, fontSize = 20.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxSize()) {
            if (inc.scope == Incident.LaneScope.SHOULDER) ShoulderCell(Modifier.weight(0.5f).fillMaxHeight())
            for (n in 1..shownLanes) {
                val blocked = mainline && n in inc.lanes
                LaneCell(n, blocked, dim = !mainline, modifier = Modifier.weight(1f).fillMaxHeight())
            }
            // 열린 끝: 차로가 더 있을 수 있음
            Canvas(Modifier.weight(0.35f).fillMaxHeight()) {
                drawRect(Color(0xFF1A1F27), size = size)
                drawLine(Color(0x55FFFFFF), Offset(0f, 0f), Offset(0f, size.height), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f)))
            }
        }
    }
}

@Composable
private fun ShoulderCell(modifier: Modifier) {
    Box(modifier.background(C.orange.copy(alpha = 0.85f)), contentAlignment = Alignment.BottomCenter) {
        Text("갓길", color = Color.Black, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
    }
}

@Composable
private fun LaneCell(n: Int, blocked: Boolean, dim: Boolean, modifier: Modifier) {
    Box(modifier.clipToBounds()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(if (dim) Color(0xFF1E232B) else Color(0xFF262C36), size = size)
            if (blocked) {
                drawRect(C.orange, size = size)
                val step = 46f
                var x = -size.height
                while (x < size.width) {
                    drawLine(Color(0xFF5A3C00), Offset(x, size.height), Offset(x + size.height, 0f), 18f)
                    x += step
                }
                val cx = size.width / 2; val cy = size.height * 0.42f; val r = minOf(size.width, size.height) * 0.16f
                drawLine(Color(0xFF111111), Offset(cx - r, cy - r), Offset(cx + r, cy + r), 9f)
                drawLine(Color(0xFF111111), Offset(cx - r, cy + r), Offset(cx + r, cy - r), 9f)
            }
            drawLine(Color(0xAAFFFFFF), Offset(0f, 0f), Offset(0f, size.height), 3f,
                pathEffect = if (n == 1) null else PathEffect.dashPathEffect(floatArrayOf(18f, 14f)))
        }
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
                .clip(RoundedCornerShape(8.dp)).background(if (blocked) Color(0xFF111111) else Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 2.dp),
        ) { Text("$n", color = if (blocked) C.orange else C.sub, fontSize = 30.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun SpeakingCard(card: DriveViewModel.Card, speaking: Boolean) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xF20B0E12))
            .border(2.5.dp, C.cyan, RoundedCornerShape(22.dp)).padding(horizontal = 36.dp, vertical = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(card.kind.label + if (speaking) ", 지금 말하는 중" else "", color = C.cyan, fontSize = 24.sp)
            Spacer(Modifier.weight(1f))
            if (speaking) Bars()
        }
        Spacer(Modifier.height(10.dp))
        Text(card.text, color = C.text, fontSize = 50.sp, fontWeight = FontWeight.Bold, lineHeight = 66.sp)
    }
}

@Composable
private fun Bars() {
    var t by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { t++; delay(160) } }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf(0.5f, 0.9f, 0.6f, 1f, 0.7f).forEachIndexed { i, h ->
            val k = 0.45f + 0.55f * (((t + i * 2) % 5) / 4f)
            Box(Modifier.width(7.dp).height((30 * h * k).dp).clip(RoundedCornerShape(3.dp)).background(C.cyan))
        }
    }
}

private val QUICK = listOf("몇 차로 막혔어?", "앞에 왜 막혀?", "얼마나 남았어?", "언제 풀려?", "어느 차로가 빨라?")

@Composable
private fun QaPanel(s: DriveViewModel.UiState, vm: DriveViewModel) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1.62f).fillMaxHeight().padding(40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (s.qa.isEmpty()) {
                Text("무엇이든 짧게 물어보세요", color = C.sub, fontSize = 34.sp)
                Text("확인된 기록으로만 한두 문장으로 답해요", color = C.dim, fontSize = 26.sp)
            }
            s.qa.takeLast(2).forEach { qa ->
                Bubble(qa.q, mine = true)
                Bubble(qa.a, mine = false, basis = qa.basis)
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(C.line))
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(horizontal = 36.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(170.dp).clip(CircleShape).border(7.dp, if (s.listening) C.cyan else C.line, CircleShape)
                    .clickable { vm.mic() },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (s.listening) "듣는 중" else "누르고\n말하기", color = if (s.listening) C.cyan else C.text,
                    fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 40.sp)
            }
            if (s.partial.isNotBlank()) Text("“${s.partial}”", color = C.sub, fontSize = 24.sp)
            QUICK.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { q ->
                        Box(
                            Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(36.dp)).background(C.panel2)
                                .border(1.5.dp, C.line, RoundedCornerShape(36.dp)).clickable { vm.ask(q) },
                            contentAlignment = Alignment.Center,
                        ) { Text(q, color = C.text, fontSize = 26.sp, fontWeight = FontWeight.Medium) }
                    }
                    if (row.size == 1) Box(
                        Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(36.dp)).background(C.panel)
                            .clickable { vm.closeQa() },
                        contentAlignment = Alignment.Center,
                    ) { Text("닫기", color = C.sub, fontSize = 26.sp) }
                }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean, basis: String = "") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 860.dp).clip(RoundedCornerShape(22.dp)).background(if (mine) C.blue else C.panel2)
                .padding(horizontal = 30.dp, vertical = 18.dp),
        ) {
            Text(text, color = Color.White, fontSize = if (mine) 32.sp else 36.sp, lineHeight = 48.sp,
                fontWeight = if (mine) FontWeight.Medium else FontWeight.SemiBold)
            if (basis.isNotBlank()) Text(basis, color = C.sub, fontSize = 20.sp)
        }
    }
}

/** 시연 조작(정차 중 발표자용). 운전자 화면 요소가 아님 */
@Composable
private fun DemoBar(s: DriveViewModel.UiState, vm: DriveViewModel) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF05070A)).padding(horizontal = 24.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("시연", color = C.dim, fontSize = 18.sp)
            s.scenarios.forEachIndexed { i, sc ->
                Chip("${i + 1}. ${sc.title}", selected = s.mode == DriveViewModel.Mode.REPLAY && i == s.scenarioIdx) { vm.selectScenario(i) }
            }
            Chip(if (s.playing) "멈춤" else "재생", selected = s.playing) { vm.togglePlay() }
            Chip("4배속", selected = s.fast) { vm.toggleFast() }
            Chip("크루즈 켜기/끄기", selected = false) { vm.toggleReplayCruise() }
            Chip("차량 신호 사용", selected = s.useCarSignals) { vm.toggleCarSignals() }
            Chip("실시간 공공 기록", selected = s.mode == DriveViewModel.Mode.LIVE) { vm.toggleLive() }
        }
        Text(
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
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(label, color = if (selected) C.cyan else C.sub, fontSize = 18.sp) }
}
