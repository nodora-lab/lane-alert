# 막힌 차로 알림 (LaneAlert)

빨간 줄 대신, 앞 사건으로 몇 차로가 통제됐는지를 짧은 음성으로 알려 주고, 크루즈를 켠 채 다가가면 더 일찍 알려 주는 Pleos Connect 차량 앱입니다.

## 지금 되는 것 (v0.2.0)
- **공공 돌발 기록으로 통제 차로 안내**: ITS 국가교통정보센터 돌발상황정보(eventInfo) 기록에서 통제 차로를 읽어 "3km 앞 고장차. 4차로 통제."처럼 한두 문장으로 말합니다.
  - 기록의 `lanesBlocked` 칸에는 차로 번호가 하나만 들어 있는 경우가 있어(2026-11-12 00:42 받은 고속도로 기록 195건 중, 두 칸 모두 차로가 있는 62건의 25건) 메시지 괄호의 차로 목록 "(4,5차로)"를 먼저 씁니다.
  - "(진입로 2차로)", "(갓길)"처럼 본선이 아닌 기록은 "본선 차로 통제 기록은 없어요"라고 말합니다.
- **크루즈 상태에 따라 알림 시점 조정**: Pleos Vehicle SDK의 크루즈 상태가 작동 중(ACTIVATED)이면 사건 지점 도착 120초 전, 아니면 60초 전에 말합니다(문턱값은 가정, 시험으로 조정 예정). 크루즈가 작동 중인데 정체 꼬리에 다가가며 속도가 줄지 않으면 "1km 앞부터 정체가 시작돼요. 크루즈가 켜져 있어요."라고 말합니다.
- **묻고 답하기**: "몇 차로 막혔어? / 앞에 왜 막혀? / 얼마나 남았어? / 언제 풀려? / 어느 차로가 빨라?"에 기록에 있는 사실로만 1-2문장으로 답합니다. 어느 차로가 빠른지는 답하지 않습니다("차로별 속도는 확인할 수 없어요"). 마이크 버튼(Pleos SpeechToText ON_DEVICE) 또는 화면 버튼으로 묻습니다.
- **시연용 재생**: 실제 공공 기록 5건(2026-11-12 00:42 받음, `app/app/src/main/assets/its/`)에 가상 주행(거리·속도·크루즈)을 붙여 재생합니다. 화면 위에 "재생 · 실제 기록" 표시가 항상 나옵니다.
- **실시간 공공 기록**: ITS 오픈API 키가 있으면 전국 고속도로 돌발 기록을 받아 옵니다.
- 같은 사건은 내용이 바뀌지 않으면 다시 말하지 않고, 실제로 말을 끝낸 뒤에만 "말함"으로 기록합니다. 차로를 바꾸라는 말, 크루즈를 믿어도 된다는 말은 하지 않습니다(단위 시험으로 막음).

## 쓰는 Pleos SDK
| SDK | 버전 | 쓰는 곳 |
|---|---|---|
| Vehicle | 2.3.2 | `DrivingMode` 크루즈 상태(6가지)·설정 속도 구독 (`pleos.car.permission.CAR_DRIVE`) |
| NaviHelper | 3.0.5 | 경로 상태의 정체·사건 목록, 도로 제한속도, 현재 위치 (`pleos.car.permission.NAVI_ROUTE`) |
| TextToSpeech | 3.3.1 | ON_DEVICE 모드 음성 안내 (앱 등록 불필요) |
| SpeechToText | 3.3.1 | ON_DEVICE 모드 질문 듣기 (버튼 누를 때만 마이크) |
| AAOS Car API | - | 차속 `PERF_VEHICLE_SPEED` |

## 테스트 환경
- **Pleos Connect 에뮬레이터 v2.1.0** (시스템 이미지 `system-images;android-34;pleos_car_emulator;x86_64`, API 34)
- **화면 규격: 2560 x 1440, 160dpi(mdpi), 가로**. 앱 화면은 1600dp 폭 기준으로 그리고 실제 폭에 맞춰 키웁니다.
- 빌드: JDK 17, Gradle 8.9, Android Gradle Plugin 8.5.2, Kotlin 2.0.21, compileSdk 34, minSdk 28

## 실행 방법
1. APK 설치: `adb install -r -g apk/<APK 파일>`
2. 앱 목록에서 "막힌 차로 알림" 실행. 아래 "시연" 줄에서 시나리오를 고르면 재생이 시작됩니다.
3. 명령으로 시작: `adb shell am start -n io.lanealert/.MainActivity --ei scenario 0 --ez play true`
4. 질문: 오른쪽 "질문하기" → 마이크 또는 질문 버튼. 명령: `adb shell am start -n io.lanealert/.MainActivity --es ask "몇 차로 막혔어?"`
5. 크루즈 신호를 차량에서 받으려면 "차량 신호 사용"을 켜고 Pleos Virtual Console(`adb forward tcp:8088 tcp:8088` 뒤 http://127.0.0.1:8088/)에서 크루즈 상태를 바꿉니다.

## 소스에서 빌드
```
cd app
# local.properties (저장소에 없음)
#   sdk.dir=<Android SDK 경로>
#   its.apiKey=<ITS 오픈API 키, 없으면 실시간 기능만 꺼짐>
./gradlew :app:testDebugUnitTest      # 규칙 엔진 단위 시험
./gradlew :app:assembleRelease
```
release 서명 키(`app/release.keystore`)와 API 키는 저장소에 넣지 않았습니다.

## 구조
| 경로 | 무엇 |
|---|---|
| `app/app/src/main/java/io/lanealert/core/` | 규칙 엔진(안드로이드 없이 시험 가능): 공공 기록 해석 `ItsParser`, 안내 문장 `Announcer`, 알림 시점 `AlertTiming`, 묻고 답하기 `QaEngine`, 재생 `ReplaySim`, 사건 맞추기 `IncidentMatcher` |
| `app/app/src/main/java/io/lanealert/pleos/` | Pleos SDK 연결: `PleosVehicleSource`, `PleosNaviSource`, `Speaker`(TTS), `Hearer`(STT) |
| `app/app/src/main/java/io/lanealert/ui/` | 차량 가로 화면(Jetpack Compose) |
| `app/app/src/test/` | 단위 시험 |
| `apk/` | release APK |

## 아직 안 되는 것
- 고속도로 CCTV 영상으로 처리 진행(통제 시작, 처리 중, 일부 개방, 해제)을 읽는 기능은 별도 연구이며 앱에 들어 있지 않습니다. 공모전 기간 Pleos SDK에 카메라·영상 입력이 없어 향후 계획입니다.
- 끝나는 시각·남은 시간 예측은 말하지 않습니다(공공 기록에 종료 시각이 대부분 비어 있음).
- 알림 시점 문턱값(60초/120초)은 가정값입니다.

## 데이터 출처
- 국가교통정보센터(ITS) 돌발상황정보 오픈API. 재생 기록은 2026-11-12 00:42에 받은 원문을 그대로 넣었습니다.
