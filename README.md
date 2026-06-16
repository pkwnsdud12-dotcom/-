# iPhone XS Cam (for Galaxy Note 20 Ultra)

갤럭시 노트 20 울트라에서 **iPhone XS 색감/톤을 실시간으로 모사**하는 독립 카메라 앱.
CameraX + OpenGL ES 3.0 GLSL 셰이더로 뷰파인더와 결과물 모두에 색감을 적용한다.

## 핵심 동작
1. **삼성 ISP 억제** (`CameraEngine.kt`): `NOISE_REDUCTION_MODE_OFF`, `EDGE_MODE_OFF`,
   `TONEMAP_GAMMA=1.0`(선형) 등을 `Camera2Interop` 로 주입하여 갤럭시 특유의
   과샤픈/워터컬러/콘트라스트 곡선을 최대한 제거 → 평평한(flat) 신호 확보.
2. **iPhone XS 컬러 사이언스** (`color_grade_shader.glsl`):
   - White Balance Warm Shift (+약350K)
   - Smart HDR 1세대 모사 S-Curve (하이라이트 soft roll-off + 섀도우 압착)
   - Skin Tone 보호(황/주황 HSL 영역 미세 채도 상승 + 과장 억제)
3. **3D LUT 아키텍처** (`CubeLutLoader.kt` + `sampler3D`):
   `assets/luts/*.cube` 를 로드해 렌더 파이프라인 마지막에 적용 가능(현재 identity placeholder 포함).

## 빌드
```bash
# Android Studio 로 프로젝트 열고 Run 하거나, CLI:
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```
> Gradle Wrapper JAR(`gradle/wrapper/gradle-wrapper.jar`) 이 없다면
> Android Studio 가 자동 생성하거나 `gradle wrapper --gradle-version 8.7` 로 생성하세요.

## 프로젝트 구조
```
iphonexs_cam/
├─ settings.gradle.kts
├─ build.gradle.kts
├─ gradle.properties
├─ gradle/wrapper/gradle-wrapper.properties
└─ app/
   ├─ build.gradle.kts
   ├─ proguard-rules.pro
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ assets/
      │  ├─ shaders/vertex_shader.glsl
      │  ├─ shaders/color_grade_shader.glsl   ← iPhone XS 색감 알고리즘
      │  └─ luts/iphone_xs_emulation.cube      ← 3D LUT (교체 가능)
      ├─ java/com/notexs/iphonexscam/
      │  ├─ MainActivity.kt
      │  ├─ CameraEngine.kt        ← CameraX + ISP 억제
      │  ├─ ColorGradeRenderer.kt  ← OpenGL ES 3.0 렌더링
      │  ├─ CubeLutLoader.kt       ← .cube 파서 + sampler3D 업로드
      │  ├─ GLUtil.kt
      │  └─ ImageSaver.kt          ← MediaStore 저장
      └─ res/...
```

## 한계 / 주의 (코드 주석에도 명시)
- 일부 삼성 펌웨어는 표준 Camera2 키를 무시하고 강제 ISP(장면 최적화/AI)를 적용할 수 있어
  ISP 억제 효과가 제한될 수 있음. 더 강한 통제가 필요하면 Camera2 + RAW(ImageReader) 전환 권장.
- 정확한 CCT→RGB 변환은 센서별 CCM 이 필요하므로, 본 앱의 화이트밸런스는 실용적 게인 근사치임.
- `TONEMAP_GAMMA` 미지원 기기에서는 기본 톤맵으로 폴백(try/catch 처리됨).
- 캡처는 디스플레이용 GL 프레임버퍼(뷰포트 해상도) 기준 — 풀해상도 JPEG 이 필요하면
  오프스크린 FBO 를 카메라 해상도로 렌더링하도록 확장 가능.
