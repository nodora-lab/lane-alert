"""demo_nosound.mp4 에 한국어 설명 음성(edge-tts) + 넷플릭스형 자막을 입힌다.
사용: python -I -X utf8 build_vo.py <src.mp4> <out.mp4> <font_dir>
SEGS: (원하는 시작 초, [(자막 표시, 낭독 문장), ...]). 자막 '/' = 줄바꿈. 낭독은 숫자를 한글로 써서 발음 고정.
"""
import asyncio, json, os, subprocess, sys
import edge_tts
from PIL import ImageFont

SRC, OUT, FDIR = sys.argv[1], sys.argv[2], sys.argv[3]
WORK = os.path.dirname(os.path.abspath(__file__))
VOICE, RATE = "ko-KR-SunHiNeural", "+8%"

SEGS = [
    (0.4, [("Pleos Connect/에뮬레이터 시연입니다", "플레오스 커넥트 에뮬레이터 시연입니다."),
           ("실제 ITS 돌발 기록을 재생하고/거리와 정체 위치만 가상이에요", "실제 아이티에스 돌발 기록을 재생하고, 거리와 정체 위치만 가상이에요.")]),
    (8.1, [("크루즈 상태와 속도는/Pleos 차량 SDK로 받아요", "크루즈 상태와 속도는 플레오스 차량 에스디케이로 받아요.")]),
    (12.2, [("앱이 Pleos TTS로 말한/안내 문장을 다시 읽어요", "앱이 플레오스 티티에스로 말한 안내 문장을 다시 읽어요."),
            ("앱 안내:/3.3km 앞 고장차. 4차로 통제", "삼 점 삼 킬로미터 앞 고장차. 사 차로 통제.")]),
    (20.4, [("앱 안내:/1.2km 앞부터 정체가 시작돼요", "앱 안내입니다. 일 점 이 킬로미터 앞부터 정체가 시작돼요."),
            ("앱 안내: 크루즈가 켜져 있어요", "크루즈가 켜져 있어요.")]),    (32.0, [("통제 차로는 ITS 기록을 따라요", "통제 차로는 아이티에스 기록을 따라요."),
            ("앱 안내:/1.2km 앞 고장차, 4차로 통제예요", "앱 안내입니다. 일 점 이 킬로미터 앞 고장차, 사 차로 통제예요.")]),
    (40.3, [("어느 차로가/빠른지는 답하지 않아요", "어느 차로가 빠른지는 답하지 않아요."),
            ("앱 안내:/차로별 속도는 확인할 수 없어요", "앱 안내입니다. 차로별 속도는 확인할 수 없어요.")]),
    (49.0, [("이번엔 같은 사건을/크루즈만 대기로 바꿔 다시 달려요", "이번엔 같은 사건을, 크루즈만 대기로 바꿔 다시 달려요."),
            ("속도는 똑같이 시속 100km예요", "속도는 똑같이 시속 백 킬로미터예요.")]),
    (60.0, [("크루즈가 대기라서/도착 60초 전에 말해요", "크루즈가 대기라서, 도착 육십 초 전에 말해요."),
            ("이 속도면 1.7km 앞이에요", "이 속도면 일 점 칠 킬로미터 앞이에요.")]),
    (67.5, [("말하는 지점 막대도/1.7km 쪽으로 옮겨 갔어요", "말하는 지점 막대도, 일 점 칠 킬로미터 쪽으로 옮겨 갔어요.")]),
    (77.3, [("앱 안내:/1.7km 앞 고장차. 4차로 통제", "앱 안내입니다. 일 점 칠 킬로미터 앞 고장차. 사 차로 통제.")]),
    (81.6, [("크루즈 작동 중엔 3.3km", "크루즈 작동 중엔 삼 점 삼 킬로미터,"),
            ("대기일 땐 1.7km에서 시작했어요", "대기일 땐 일 점 칠 킬로미터에서 시작했어요.")]),
    (88.6, [("진입로 공사 기록이에요", "진입로 공사 기록이에요."),
            ("앱 안내:/3.3km 앞 진입로 공사", "앱 안내입니다. 삼 점 삼 킬로미터 앞 진입로 공사."),
            ("본선 차로 통제 기록은 없어요", "본선 차로 통제 기록은 없어요."),
            ("진입로나 갓길 기록은/본선이 막혔다고 하지 않아요", "진입로나 갓길 기록은, 본선이 막혔다고 하지 않아요.")]),
    (104.2, [("앱 안내:/3km 앞 공사. 4, 5차로 통제", "앱 안내입니다. 삼 킬로미터 앞 공사. 사 차로, 오 차로 통제."),
             ("ITS 차로 칸엔 5차로만 있지만/기록 문구의 4, 5차로를 따라요", "아이티에스 차로 칸엔 오 차로만 있지만, 기록 문구의 사 차로, 오 차로를 따라요.")]),
    (114.8, [("앱 안내:/1.2km 앞부터 정체가 시작돼요", "앱 안내입니다. 일 점 이 킬로미터 앞부터 정체가 시작돼요."),
             ("앱 안내: 크루즈가 켜져 있어요", "크루즈가 켜져 있어요.")]),
]


def nf_len(s):
    return sum(1 if ("가" <= ch <= "힣" or ord(ch) > 0x3000) else 0.5 for ch in s)


async def _tts(text, mp3):
    words = []
    comm = edge_tts.Communicate(text, VOICE, rate=RATE, boundary="WordBoundary")
    with open(mp3, "wb") as h:
        async for ch in comm.stream():
            if ch["type"] == "audio":
                h.write(ch["data"])
            elif ch["type"] == "WordBoundary":
                s = ch["offset"] / 1e7
                words.append([s, s + ch["duration"] / 1e7, ch["text"]])
    return words


def dur(p):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "json", p], capture_output=True, text=True)
    return float(json.loads(r.stdout)["format"]["duration"])


def ass_time(t):
    t = max(t, 0)
    return f"{int(t // 3600)}:{int(t % 3600 // 60):02d}:{t % 60:05.2f}"


def main():
    font = ImageFont.truetype(os.path.join(FDIR, "NotoSansKR-Medium.ttf"), 70)
    W, H = 1920, 1080
    vid = dur(SRC)
    t_prev, events, audio, log = 0.0, [], [], []
    for si, (want, chunks) in enumerate(SEGS):
        for disp, _ in chunks:  # 자막 규칙 검사
            lines = disp.split("/")
            assert len(lines) <= 2, disp
            for ln in lines:
                assert nf_len(ln) <= 16 and font.getlength(ln) <= W * 0.8, (ln, nf_len(ln))
            if len(lines) == 2:
                assert nf_len(lines[1]) >= nf_len(lines[0]), ("아래 줄이 짧음", disp)
        text = " ".join(t for _, t in chunks)
        mp3 = os.path.join(WORK, f"seg{si:02d}.mp3")
        words = asyncio.run(_tts(text, mp3))
        start = max(want, t_prev + 0.25)
        d = words[-1][1] if words else dur(mp3)
        audio.append((mp3, start))
        log.append(f"{si:02d} 원함 {want:6.1f} 실제 {start:6.1f}-{start + d:6.1f} ({d:.1f}초) {text}")
        # 자막 조각 시각: 낭독 단어를 글자 수 누적으로 조각에 배정
        key = lambda s: "".join(ch for ch in s if ch.isalnum())
        cum, n = [], 0
        for s, e, w in words:
            n += len(key(w)); cum.append((n, s, e))
        pos = 0
        for disp, tt in chunks:
            k = len(key(tt)); a, b = pos + 1, pos + k; pos = b
            st = next(s for c, s, e in cum if c >= a)
            en = next((e for c, s, e in cum if c >= b), cum[-1][2])
            events.append([start + st, start + en, disp.replace("/", "\\N")])
        t_prev = start + d
    total = max(vid, t_prev + 1.0)
    F2 = 2 / 30
    for i, ev in enumerate(events):  # 넷플릭스 시간 규칙: 말 끝 +0.4초, 0.83-7초, 다음 자막과 2프레임
        nxt = events[i + 1][0] if i + 1 < len(events) else total
        end = max(ev[1] + 0.4, ev[0] + 5 / 6)
        ev[1] = min(end, nxt - F2, ev[0] + 7)
        assert ev[1] - ev[0] >= 0.83 - 1e-6 or nxt - F2 < ev[0] + 0.83, ev
    head = f"""[Script Info]
ScriptType: v4.00+
PlayResX: {W}
PlayResY: {H}
WrapStyle: 2
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Cap,Noto Sans KR Medium,70,&H00FFFFFF,&H00FFFFFF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,2.6,2,2,50,50,54,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
    ass = os.path.join(WORK, "caps.ass")
    with open(ass, "w", encoding="utf-8-sig") as h:
        h.write(head + "\n".join(f"Dialogue: 0,{ass_time(s)},{ass_time(e)},Cap,,0,0,0,,{{\\blur0.8}}{t}" for s, e, t in events) + "\n")
    ins, fl = [], []
    for i, (mp3, a0) in enumerate(audio):
        ins += ["-i", mp3]
        ms = int(a0 * 1000)
        fl.append(f"[{i + 1}:a]aresample=48000,adelay={ms}|{ms}[a{i}]")
    fc = ";".join(fl) + ";" + "".join(f"[a{i}]" for i in range(len(audio))) + \
        f"amix=inputs={len(audio)}:normalize=0,loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000,apad[a]"
    pad = max(0.0, total - vid)
    vf = (f"[0:v]tpad=stop_mode=clone:stop_duration={pad:.2f}," if pad > 0 else "[0:v]") + "subtitles=caps.ass:fontsdir=fonts[v]"
    os.makedirs(os.path.join(WORK, "fonts"), exist_ok=True)
    import shutil
    shutil.copy(os.path.join(FDIR, "NotoSansKR-Medium.ttf"), os.path.join(WORK, "fonts", "NotoSansKR-Medium.ttf"))
    r = subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", SRC] + ins + ["-filter_complex", fc + ";" + vf,
                       "-map", "[v]", "-map", "[a]", "-c:v", "libx264", "-preset", "medium", "-crf", "21",
                       "-c:a", "aac", "-b:a", "160k", "-t", f"{total:.2f}", "-movflags", "+faststart", OUT], cwd=WORK)
    assert r.returncode == 0
    # 받아쓰기 검사용 낭독만 합친 소리
    fl0 = [x.replace(f"[{i + 1}:a]", f"[{i}:a]", 1) for i, x in enumerate(fl)]
    subprocess.run(["ffmpeg", "-v", "error", "-y"] + ins + ["-filter_complex", ";".join(fl0) + ";" + "".join(f"[a{i}]" for i in range(len(audio))) +
                    f"amix=inputs={len(audio)}:normalize=0[a]", "-map", "[a]", "-ac", "1", "-ar", "16000", os.path.join(WORK, "vo_only.wav")], cwd=WORK)
    print("\n".join(log))
    print(f"영상 {vid:.1f}초 → 결과 {total:.1f}초, 자막 {len(events)}개")
    json.dump({"segments": [(a0, t) for (mp3, a0), (_, ch) in zip(audio, SEGS) for t in [" ".join(x for _, x in ch)]],
               "events": events}, open(os.path.join(WORK, "timeline.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)


main()
