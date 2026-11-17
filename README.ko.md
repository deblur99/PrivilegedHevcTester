# PrivilegedHevcTester

[English](README.md)

온디바이스 앱과 Mac `adb` host-bridge로 **Android System WebView 91 vs 134**에서 HEVC / H.264 `<video>` 재생을 스모크 테스트합니다.

## 개요

- 앱에서 **HEVC** 또는 **H.264**를 고른 뒤 **Test WebView 91** / **Test WebView 134**를 누릅니다.
- 순서: 환경 세팅(호스트가 해당 WebView APK 설치) → 시스템 WebView로 샘플 HTML 로드.
- 이미 같은 major 버전이면 host 설치를 건너뛰고 HTML만 로드합니다.
- 버전 전환 시 Mac `host-bridge.py`가 `adb install`을 수행하고, 앱이 **스스로 재시작**해 새 WebView 프로바이더를 로드합니다(수동 강제 종료 불필요).

## 요구 사항

- macOS, `adb`, Python 3
- 에뮬레이터: API **29**, **arm64**, **userdebug** (예: `emulator-5554`)
- WebView APK (git에 없음 → `apks/`에 직접 배치):
  - `webview-91.0.4472.120.apk`
  - `webview-134.0.6998.40.apk`
  - `trichrome-134.0.6998.40.apk` (134와 함께 필요)

[APKMirror — Android System WebView](https://www.apkmirror.com/apk/google-inc/android-system-webview/)에서 arm64 / Android 10+ variant를 받으세요.

## 디렉터리

| 경로 | 역할 |
|------|------|
| `PrivilegedHevcTester/` | Android Gradle 앱 |
| `host-bridge.py` | 앱 `files/cmd` 폴링 후 `adb install` |
| `start-host-bridge.sh` | host-bridge 상시 실행 |
| `run-test.sh` | 호스트에서 설치 후 `skipSetup` 실행 |
| `install-tester.sh` | 테스터 APK 빌드·설치 |
| `assets/` | 공유 HTML·샘플 mp4 |
| `apks/` | 로컬 WebView APK (gitignore) |

## 설정

```bash
git clone https://github.com/deblur99/PrivilegedHevcTester.git
cd PrivilegedHevcTester

# WebView APK를 apks/에 둠 (위 파일명)

./start-host-bridge.sh

cd PrivilegedHevcTester
./gradlew :app:assembleDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n kr.co.milkt.privilegedhevctester/.MainActivity
```

또는 저장소 루트에서 `./install-tester.sh`.

## 사용법

1. 버전을 바꿀 때는 Mac에서 `./start-host-bridge.sh`를 켜 둡니다.
2. 앱에서 **HEVC** 또는 **H.264**를 선택합니다.
3. **Test WebView 91** 또는 **Test WebView 134**를 누릅니다.
4. 프로그레스·로그를 확인하고, 버전 전환 후에는 앱이 재시작된 뒤 스모크 로드가 이어집니다.

버튼 없이 호스트만 쓸 때:

```bash
./run-test.sh 91
./run-test.sh 134
```

## 참고

- **host-bridge는 WebView 패키지를 바꿀 때만** 필요합니다. 같은 버전 스모크에는 필요 없습니다.
- 샘플 미디어는 `/sdcard`의 `660` 권한 문제를 피하려고 앱 private `files/`에 스테이징합니다.
- WebView **91**에서 HEVC는 재생 이벤트가 나와도 **영상 크기 0×0**(화면 없음)일 수 있고, **H.264**는 대체로 정상입니다. **134**에서는 개발에 쓴 에뮬에서 HEVC 프레임이 보일 수 있습니다.
- HTML을 Chrome으로 열지 마세요. Chrome은 `com.google.android.webview`가 아니라 자체 엔진을 씁니다.

## 면책

**테스트용 도구**입니다. WebView / Trichrome APK는 Google 서명 패키지로, 직접 다운로드하고 APKMirror·Google 이용 약관을 지키세요. Google과 무관합니다.
