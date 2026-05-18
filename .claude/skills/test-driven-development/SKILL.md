---
name: test-driven-development
description: Use this skill when the user wants implementation to follow a strict test-driven development cycle with failing tests first, minimal production code, and explicit refactoring checkpoints. Compatible with Codex and Claude because all instructions are plain Markdown.
---

# test-driven-development

## 호출 형식
```text
/test-driven-development {requirements}
```

## 입력 파라미터
- `requirements`: 구현하려는 기능 요구사항, 기대 동작, 예외 케이스, 제약사항

## 사용 예시
```text
/test-driven-development 사용자 회원가입 시 이메일 형식 검증과 중복 이메일 예외 처리를 포함한 서비스 구현
```

## 사용 상황
- 새 기능을 TDD 방식으로 구현해야 할 때
- 테스트 우선 개발 원칙을 강제해야 할 때
- 회귀 방지를 위해 실패 테스트부터 설계해야 할 때

## 실행 순서
1. 요구사항을 기능, 입력, 출력, 예외 케이스로 분해합니다.
2. 먼저 실패하는 테스트를 작성합니다.
3. 테스트를 통과하는 최소 구현만 추가합니다.
4. 중복 제거와 구조 개선을 수행합니다.
5. 전체 테스트를 다시 실행하고 결과를 확인합니다.

## 운영 규칙
- 구현 전에 테스트를 먼저 작성합니다.
- 테스트는 반드시 처음에 실패해야 합니다.
- Green 단계에서는 최소 구현만 허용합니다.
- Refactor 이후에는 관련 테스트를 다시 실행합니다.
- 기존 테스트를 왜곡해서 통과시키지 않습니다.

## 추가 자료
- 상세 절차: [workflow.md](workflow.md)
- 검수 기준: [checklist.md](checklist.md)
- 요청 예시: `examples/` 디렉터리
