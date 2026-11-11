# 막힌 차로 알림 (LaneAlert)

빨간 줄 대신, 앞 사건으로 몇 차로가 통제됐는지를 짧은 음성으로 알려 주고, 크루즈를 켠 채 다가가면 더 일찍 알려 주는 Pleos Connect 차량 앱입니다.

- APK: `apk/LaneAlert_막힌차로알림_release.apk` (release 빌드)
- 시연 영상: [`docs/demo.mp4`](docs/demo.mp4) (Pleos Connect 에뮬레이터 화면 녹화, 1920x1080, 약 2분. 화면 녹화라 소리는 없고, 말한 문장은 화면 카드에 같이 나옵니다)
- 화면: `screens/` (에뮬레이터 실제 캡처 5장)

## 지금 되는 것 (v0.2.0)
- **공공 돌발 기록으로 통제 차로 안내**: ITS 국가교통정보센터 돌발상황정보(eventInfo) 기록에서 통제 차로를 읽어 "3.3km 앞 고장차. 4차로 통제."처럼 한두 문장으로 말합니다(Pleos TextToSpeech ON_DEVICE).
  - 기록의 `lanesBlocked` 칸에는 차로 번호가 하나만 들어 있는 경우가 있어(2026-11-12 00:42에 받은 고속도로 기록 195건 중 두 칸 모두 차로가 있는 62건 가운데 25건이 "가장 큰 차로 번호 하나"만 담김) 메시지 괄호의 차로 목록 "(4,5차로)"를 먼저 쓰고, 칸이 다르면 화면에 밝힙니다.
  - "(진입로 2차로)", "(갓길)"처럼 본선이 아닌 기록은 "본선 차로 통제 기록은 없어요"라고 말합니다. "해제"가 섞인 기록은 통제 차로를 단정하지 않습니다.
- **크루즈 상태에 따라 알림 시점 조정**: Pleos Vehicle SDK의 크루즈 상태가 `ACTIVATED`이면 사건 지점 도착 120초 전, 아니면 60초 전에 말합니다(100km/h에서 각각 약 3.3km, 1.7km 앞. 문턱값은 가정값). 크루즈가 작동 중인데 정체 꼬리에 다가가며 속도가 줄지 않으면 "1.2km 앞부터 정체가 시작돼요. 크루즈가 켜져 있어요."라고 말합니다. 화면의 "말하는 지점" 막대에 두 지점과 내 차 위치가 나옵니다.
- **차량 신호**: "차량 신호 사용"을 켜면 크루즈 상태·설정 속도(Vehicle SDK `DrivingMode`)와 차속(AAOS `PERF_VEHICLE_SPEED`)으로 알림을 계산합니다. 신호 오류가 나면 이전 값을 지웁니다.
- **묻고 답하기**: "말로 묻기"를 누르면 바로 듣기 시작합니다(Pleos SpeechToText ON_DEVICE, 버튼 누를 때만 마이크). 화면 버튼으로도 묻습니다. "몇 차로야? / 왜 막혀? / 얼마나 남았어? / 언제 풀려? / 어느 차로가 빨라?"에 기록 문구로만 1-2문장 답합니다. 어느 차로가 빠른지는 답하지 않습니다. 오래되거나 끝난 기록은 통제 차로를 지금 사실처럼 말하지 않습니다. 답한 뒤 9초 뒤 주행 화면으로 돌아갑니다.
- **실시간 공공 기록**: ITS 오픈API 키가 있으면 전국 고속도로 돌발 기록을 받습니다. NaviHelper가 준 경로 위 사건과 300m 안에서, 종류가 맞고 모호하지 않은 공공 기록만 "앞 사건"으로 말합니다. 못 맞추면 "주변 기록 · 내 경로 앞인지 확인 전"으로만 보여 주고 말하지 않습니다.
- **반복·금지 문구**: 같은 사건은 내용이 바뀌지 않으면 다시 말하지 않고, TTS가 말을 끝낸 뒤에만 "말함"으로 기록합니다. 차로를 바꾸라는 말은 하지 않고, 차량 기능의 작동 여부는 안내하지 않습니다(단위 시험으로 막음).
- **시연용 재생**: 실제 공공 기록 5건(2026-11-12 00:42 받음, `app/app/src/main/assets/its/`)에 가상 주행(거리·속도·크루즈)을 붙여 재생합니다. 화면 위에 "공공 기록 재생 · 주행 조건 가상"이 항상 나옵니다.

## 쓰는 Pleos SDK
| SDK | 버전 | 쓰는 곳 |
|---|---|---|
| Vehicle | 2.3.2 | `DrivingMode` 크루즈 상태 6가지·설정 속도 구독 (`pleos.car.permission.CAR_DRIVE`) |
| NaviHelper | 3.0.5 | 경로 상태의 정체·사건 목록, 도로 제한속도, 현재 위치 (`pleos.car.permission.NAVI_ROUTE`) |
| TextToSpeech | 3.3.1 | ON_DEVICE 음성 안내 (앱 등록 불필요) |
| SpeechToText | 3.3.1 | ON_DEVICE 질문 듣기 |
| AAOS Car API | - | 차속 `PERF_VEHICLE_SPEED` |

## 테스트 환경
- **Pleos Connect 에뮬레이터 v2.1.0** (시스템 이미지 `system-images;android-34;pleos_car_emulator;x86_64`, API 34, SDK Manager 주소 `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`)
- **화면 규격: 2560 x 1440, 160dpi(mdpi), 가로**. 앱은 Pleos 홈의 앱 영역(오른쪽)에 뜨며, 1600dp 폭 기준으로 그리고 실제 폭에 맞춰 키웁니다.
- 빌드: JDK 17, Gradle 8.9, Android Gradle Plugin 8.5.2, Kotlin 2.0.21, compileSdk 34, minSdk 28
- 에뮬레이터에서 확인한 것: Vehicle SDK 연결·크루즈 이벤트 수신, PERF_VEHICLE_SPEED 수신, NaviHelper 연결, Pleos TTS ON_DEVICE 발화(첫 호출은 모델 적재로 약 8-12초, 이후 약 2초), Pleos STT ON_DEVICE 초기화. 마이크로 실제 말한 질문을 인식하는 것은 이 환경에서 시험하지 못했습니다(질문 버튼·명령으로 같은 경로 시험).

## 실행 방법
1. APK 설치: `adb install -r -g apk/LaneAlert_막힌차로알림_release.apk`
2. 앱 목록에서 "막힌 차로 알림" 실행. 아래 "시연 조작 ▸"을 열어 시나리오를 고르면 재생됩니다.
3. 명령으로: `adb shell am start -n io.lanealert/.MainActivity --ei scenario 0 --ez play true [--ez fast true] [--ez car true]`
4. 질문: "말로 묻기" 또는 질문 버튼. 명령: `adb shell am start -n io.lanealert/.MainActivity --es ask "'몇 차로야?'"`
5. 실제 차량 신호로 시연: `adb forward tcp:8088 tcp:8088` 뒤 Pleos Virtual Console(http://127.0.0.1:8088/)에서 기어 D, 속도, ADAS Cruise Control State를 바꿉니다. 이 에뮬레이터에서 확인한 대응: Full autonomous → `ACTIVATED`, Ready·Lateral → `ENABLED`, Not ready → 값 없음. 앱은 `--ez car true`(또는 "차량 신호 사용")로 켭니다.
6. 녹화 그대로 다시 하기: `tools/vc_heartbeat.sh`를 띄운 뒤 `tools/demo.ps1` (PowerShell, `ADB`·`SERIAL` 환경변수로 adb 경로와 기기 지정)

## 소스에서 빌드
```
cd app
# local.properties (저장소에 없음)
#   sdk.dir=<Android SDK 경로>
#   its.apiKey=<ITS 오픈API 키, 없으면 실시간 기능만 꺼짐>
./gradlew :app:testDebugUnitTest      # 규칙 엔진 단위 시험 38개
./gradlew :app:assembleRelease
```
release 서명 키(`app/release.keystore`)와 API 키는 저장소에 넣지 않았습니다. Windows에서 경로에 한글이 있으면 단위 시험 클래스를 못 찾는 문제가 있어 `subst`로 영문 드라이브를 잡아 실행했습니다.

## 구조
| 경로 | 무엇 |
|---|---|
| `app/app/src/main/java/io/lanealert/core/` | 규칙 엔진(안드로이드 없이 시험 가능): 공공 기록 해석 `ItsParser`, 안내 문장·반복 방지 `Announcer`(파이썬 참조 구현에서 옮김), 알림 시점 `AlertTiming`, 묻고 답하기 `QaEngine`, 재생 `ReplaySim`, 내비 사건 맞추기 `NaviMatch`·`IncidentMatcher` |
| `app/app/src/main/java/io/lanealert/pleos/` | Pleos SDK 연결: `PleosVehicleSource`, `PleosNaviSource`, `Speaker`(TTS), `Hearer`(STT) |
| `app/app/src/main/java/io/lanealert/ui/` | 차량 가로 화면(Jetpack Compose) |
| `app/app/src/test/` | 단위 시험 38개 |
| `tools/` | 시연 녹화 스크립트 |

## 아직 안 되는 것
- 고속도로 CCTV 영상으로 처리 진행(통제 시작, 처리 중, 일부 개방, 해제)을 읽는 기능은 별도 연구이며 앱에 없습니다. 공모전 Pleos SDK에 카메라·영상 입력이 없어 향후 계획입니다.
- 끝나는 시각·남은 시간 예측은 말하지 않습니다(공공 기록에 종료 시각이 대부분 비어 있음).
- 실시간 모드의 사건까지 거리는 직선거리입니다. 경로를 따라 잰 거리가 아닙니다. 공공 기록과 내비 사건은 좌표·종류로만 맞추며, 도로명·방향 대조는 아직 없습니다.
- 알림 시점 문턱값(60초/120초)은 가정값입니다. 에뮬레이터 내비가 실제 경로·사건을 주는 장면은 아직 확인하지 못해 시연은 재생으로 합니다.

## 데이터 출처
- 국가교통정보센터(ITS) 돌발상황정보 오픈API. 재생 기록은 2026-11-12 00:42에 받은 원문을 그대로 넣었습니다.
