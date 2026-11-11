# 막힌 차로 알림 시연 녹화 (Pleos Connect 에뮬레이터 + Virtual Console)
# 사용법: Pleos 에뮬레이터를 켜고 'adb forward tcp:8088 tcp:8088', tools/vc_heartbeat.sh 를 띄운 뒤 실행
$adb = if ($env:ADB) { $env:ADB } else { 'adb' }
$S = if ($env:SERIAL) { $env:SERIAL } else { 'emulator-5554' }
$VC = 'http://127.0.0.1:8088'
function vc($path) { curl.exe -s -m 5 -X PUT "$VC$path" | Out-Null }
$scr = Join-Path $PSScriptRoot '..\screens'
function cap($n) { cmd /c "$adb -s $S exec-out screencap -p > ""$scr\$n.png""" }
function app([string[]]$extra) { & $adb -s $S shell am start -n io.lanealert/.MainActivity @extra | Out-Null }

# 준비: 기어 D, 100km/h, 크루즈 작동(Full autonomous → Vehicle SDK ACTIVATED), 설정 100km/h
vc '/vehicle/gear?gear=Drive'
vc '/vehicle/controls?speed=100&accelerator=20&brake=0'
vc '/vehicle/driving-mode?ad_mode_state=full_autonomous&target_speed_mps=27.778'
& $adb -s $S shell am force-stop io.lanealert
& $adb -s $S shell logcat -c
# TTS 예열(첫 호출은 온디바이스 모델 적재로 느림)
app @('--ez','car','false','--ez','fast','true','--ei','scenario','4','--ez','play','true')
Start-Sleep 25
app @('--ez','car','true','--ez','fast','false','--ei','scenario','0','--ez','play','false')
Start-Sleep 4

# 녹화 시작 (최대 150초, 1920x1080)
$rec = Start-Process -FilePath $adb -ArgumentList "-s $S shell screenrecord --time-limit 150 --size 1920x1080 --bit-rate 12000000 /sdcard/lanealert_demo.mp4" -PassThru -WindowStyle Hidden
Start-Sleep 4

# 1) 크루즈 작동 중, 실제 차량 신호로 접근 → 3.3km 앞에서 말함, 이어서 정체 꼬리 안내
app @('--ez','fast','true','--ei','scenario','0','--ez','play','true')
Start-Sleep 11; cap '01_크루즈작동_실제신호_3.3km에서_안내'
Start-Sleep 15
# 2) 말로 묻기 (질문은 화면 버튼과 같은 경로로 넣음)
app @('--es','ask',"'몇 차로야?'")
Start-Sleep 8
app @('--es','ask',"'어느 차로가 빨라?'")
Start-Sleep 4; cap '03_묻고답하기_어느차로가빨라'
Start-Sleep 7
# 3) 같은 사건, 크루즈만 대기(Ready → ENABLED)로 바꿔 다시 → 1.7km 앞에서 말함
vc '/vehicle/driving-mode?ad_mode_state=ready&target_speed_mps=27.778'
Start-Sleep 1
app @('--ez','fast','true','--ei','scenario','0','--ez','play','true')
Start-Sleep 25; cap '02_크루즈대기_같은사건_1.7km에서_안내'
Start-Sleep 5
# 4) 진입로 공사 기록: 본선 통제 기록 없음
vc '/vehicle/driving-mode?ad_mode_state=full_autonomous&target_speed_mps=25'
app @('--ez','car','true','--ez','fast','true','--ei','scenario','2','--ez','play','true')
Start-Sleep 8; cap '04_진입로공사_본선통제기록없음'
Start-Sleep 6
# 5) 2개 차로 공사 (차로 칸 "5차로"와 기록 문구 "(4,5차로)"가 다른 기록)
app @('--ez','fast','true','--ei','scenario','1','--ez','play','true')
Start-Sleep 6; cap '05_2개차로공사_기록문구차로우선'
Start-Sleep 16

& $adb -s $S shell pkill -INT screenrecord
Start-Sleep 3
& $adb -s $S shell "logcat -d | grep 'received final' | sed 's/.*texts=//'"
