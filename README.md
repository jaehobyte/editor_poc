# newCam

Reference 이미지로부터 추론된 보정값을 Input 이미지에 애니메이션으로 적용하는 안드로이드 포토 에디터.

## Stack
- Kotlin + Jetpack Compose
- 최소 SDK 31 (Android 12), 컴파일 SDK 35
- TensorFlow Lite (CPU 기본, GPU delegate는 추후)
- OpenGL ES 3.0 (다음 단계에서 도입)

## 자산
- `app/src/main/assets/recipe_generator_v260422.tflite` — 보정 파라미터 추론 모델 (300MB, git 제외)
- `assets/galaxy_editor_python.py` — Galaxy 필터 알고리즘 레퍼런스 (Python)
- `FYI.kt` — 동일 알고리즘의 Kotlin CPU 포팅 (셰이더 포팅 참조용)

## 빌드 / 실행

### 사전 요구
- Android Studio Iguana 이상 (Compose Compiler 2.0+ 지원)
- Android SDK 35
- JDK 17

### 첫 실행
```
1. Android Studio에서 이 디렉터리를 Open
2. Gradle sync 자동 실행 — wrapper 자동 생성됨
3. Run ▶ MainActivity
```

### 현재 PoC 동작
1. "Reference 선택" → 갤러리에서 사진 선택
2. "Input 선택" → 갤러리에서 사진 선택
3. "추론 실행" → 29개 파라미터 출력

## 다음 단계
- [ ] OpenGL ES 셰이더 파이프라인 (9개 효과 포팅)
- [ ] 애니메이션 (톤 → 컬러 튜닝, 3초)
- [ ] 저장 → 갤러리

## 문서
- `AGENTS.md` — 코딩 에이전트(Codex 등) 가이드
- `DESIGN.md` — UI 디자인 시스템 (Runway 기반)
- `.claude/CLAUDE.md` — Karpathy 코딩 원칙
