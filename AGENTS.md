# AGENTS.md

**newCam** — Reference 이미지로부터 추론된 보정값을 Input 이미지에 애니메이션으로 적용하는 안드로이드 포토 에디터.

이 파일은 Claude Code 외의 코딩 에이전트(주로 **Codex**, `codex-plugin-cc` 경유)가 읽는 가이드입니다.
Claude Code는 `.claude/CLAUDE.md`도 함께 사용합니다.

---

## 프로젝트 개요

### 사용자 흐름
```
1. 갤러리에서 Reference 이미지 선택
2. 갤러리에서 Input 이미지 선택
3. TFLite 모델이 (reference, input) → 29개 보정 파라미터 추론
4. Input 이미지에 OpenGL ES 셰이더로 실시간 애니메이션 보정 적용
   - 1단계: 톤 보정 8개 (Temperature → Shadows)
   - 2단계: 컬러 튜닝 21개 (7 색상 × HSL)
5. 저장 → 갤러리
```

### 핵심 자산

| 파일 | 역할 | 주의사항 |
|------|------|---------|
| `assets/recipe_generator_v260422.tflite` | TFLite 추론 모델 (300MB) | APK 압축 해제 필수, GPU delegate 권장 |
| `assets/galaxy_editor_python.py` | Galaxy 필터 파이프라인 레퍼런스 (Python) | **알고리즘 변경 금지** — 보정 상수는 실제 Galaxy 단말에서 캘리브레이션된 값 |
| `FYI.kt` | 동일 파이프라인의 Kotlin CPU 포팅 | OpenGL 셰이더 포팅의 참조 구현 |

---

## 기술 스택

| 영역 | 선택 |
|------|------|
| 언어 | Kotlin |
| UI | Jetpack Compose |
| 최소 SDK | **API 31** (Android 12) |
| 컴파일 SDK | API 35+ |
| AI 추론 | `org.tensorflow:tensorflow-lite` + `tensorflow-lite-gpu` |
| 이미지 처리 | **OpenGL ES 3.0** (multi-pass FBO 셰이더) |
| OpenGL 통합 | `GLSurfaceView` + Compose `AndroidView` |
| 사진 선택 | PhotoPicker (`ActivityResultContracts.PickVisualMedia`) |
| 비동기 | Coroutines + Flow |
| 빌드 | Gradle Kotlin DSL |

---

## 모델 명세

```
Input  args_0: [1, 3, 224, 224] float32  ← Reference 이미지 (CHW, RGB)
Input  args_1: [1, 3, 224, 224] float32  ← Input 이미지     (CHW, RGB)
Output       : [1, 29]          float32  ← 보정 파라미터 [-1, 1]
```

### 출력 29개의 매핑

| 인덱스 | 파라미터 | UI 스케일 |
|--------|----------|-----------|
| 0 | Temperature | × 100 → [-100, 100] |
| 1 | Contrast | ×100 |
| 2 | Tint | ×100 |
| 3 | Saturation | ×100 |
| 4 | Brightness | ×100 |
| 5 | Exposure | ×100 |
| 6 | Highlights | ×100 |
| 7 | Shadows | ×100 |
| 8-28 | 7색상 × (Hue, Sat, Lum) — Red, Orange, Yellow, Green, Blue, Navy, Purple 순서 | 색상별 별도 게인 적용 (`galaxy_editor_python.py` 참조) |

### 주의
- **NCHW 포맷** (일반 TFLite의 NHWC 아님). 비트맵 → 텐서 변환 시 transpose 필요.
- 입력 정규화는 `galaxy_editor_python.py`의 학습 파이프라인과 맞춰야 함 (`[0,1]` 추정, 확인 필요).
- 추론은 **1회만** 수행. 이후 애니메이션은 셰이더에서 파라미터 보간만 함.

---

## 애니메이션 정책 (B: 순차)

```
t = 0          → 원본
t = 0 ~ 1.5s   → 톤 8개 보간 (0 → target)
t = 1.5 ~ 3s   → 컬러 튜닝 21개 보간 (0 → target)
t = 3s+        → 최종 결과
```

- 톤 단계가 끝난 시점의 값은 컬러 튜닝 동안 유지
- 사용자가 슬라이더로 진행 위치 스크럽 가능 (선택 기능)
- 부드러운 이징: `FastOutSlowInEasing` 권장

---

## 역할 분담

| 에이전트 | 담당 | 읽는 파일 |
|----------|------|-----------|
| **Claude Code** | 모델 로딩, 추론, 셰이더 파이프라인, 상태 관리 | `.claude/CLAUDE.md` + 본 파일 |
| **Codex** (codex-plugin-cc) | UI / 디자인 구현 | 본 파일 + `DESIGN.md` |

---

## 필독 파일

1. **`DESIGN.md`** — Runway 기반 디자인 시스템 (UI 작업 시 필수)
2. **`.claude/CLAUDE.md`** — Karpathy 코딩 행동 원칙 (모든 에이전트 공통)
3. **`assets/galaxy_editor_python.py`** — 필터 알고리즘 원본 (셰이더 포팅 시 참조)
4. **`FYI.kt`** — 동일 알고리즘의 Kotlin 구현 (자료형/순서 참고)

---

## 행동 원칙

`CLAUDE.md`의 Karpathy 4원칙 동일 적용:

1. **Think Before Coding** — 가정을 명시, 모호하면 질문
2. **Simplicity First** — 최소 코드, 추측 금지
3. **Surgical Changes** — 요청된 것만 변경
4. **Goal-Driven Execution** — 검증 가능한 성공 기준

---

## 절대 규칙

### 알고리즘 무결성
- `galaxy_editor_python.py`의 **모든 상수는 보존**. 변경하면 Galaxy 단말 보정 결과와 어긋남.
- 효과 적용 순서 변경 금지: Temperature → Contrast → Tint → Saturation → (Luma 효과) → Color Tuning
- "표준 공식"으로 대체 금지 (BT.601 행렬, 캘리브레이션 LUT 등은 의도된 값)

### UI 작업 (Codex)
- 색상/폰트/간격은 `DESIGN.md` 토큰만 사용
- 캔버스 영역 배경: 중간 회색 (이미지 색상 인식 방해 방지)
- 이미지 위 그라데이션/오버레이 금지
- 호버 효과는 안드로이드 ripple로 대체

### TFLite
- 모델 입력은 항상 **(reference=args_0, input=args_1)** 순서
- 추론은 GPU delegate 우선, 실패 시 CPU 폴백
- 모델 파일은 압축 해제 상태로 APK에 포함 (build.gradle에서 `noCompress 'tflite'`)

---

## 작업 흐름

1. 요청 → DESIGN.md / FYI.kt / galaxy_editor_python.py 중 어디를 참조할지 진술
2. 모호하면 질문
3. 구현
4. 검증: 셰이더 결과를 `FYI.kt` CPU 구현 결과와 픽셀 비교
