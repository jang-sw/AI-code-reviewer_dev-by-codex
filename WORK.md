# 현재 작업 상태

- 작업: 실사용 AI 소스코드 리뷰 시스템 신규 개발. **개발 중, 릴리스 완료 아님.**
- 이번 회차: 2026-09-25 22:32 KST 시작, 상한 약 2시간 (09-26 00:32 KST).
- 기준 브랜치/커밋: `main` / `44bfa91` (검증된 1차 흐름), 후속 작성자 매핑·복수 자격증명·운영 검증 진행 중.
- 확정 요구사항: `docs/REQUIREMENTS.md`. 내부 이슈함, 최초 전체 이력 리뷰, 기존 Java25/Spring/Maven/JSP/PostgreSQL 유지.

## 구현·검증된 1차 흐름

- Maven Wrapper + WAR, PostgreSQL Flyway V1/V2, JSP/JSTL 반응형 UI.
- ADMIN 계정 생성·관리, USER 로그인/비밀번호, BCrypt12·CSRF·CSP·로그인 제한·영구 세션 폐기·동시 마지막 관리자 보호.
- URL 프로젝트 신청·승인/반려/중지/재개, 소유권 검사.
- GitHub/GitLab 전체 이력/페이지/부모 그래프 및 diff 완전성 검사, Ollama/LiteLLM 구조화 출력 검증.
- 시간별/수동 백그라운드 리뷰, PG advisory lock, 커밋별 저장과 배치 성공 checkpoint, 재시도 중복 방지.
- 내부 이슈함/상태/배정 사유, 감사 기록, 일반화된 오류 화면.
- 실제 패키지에서 발견한 JSP forward/CSP/record property/UTF8 문제 수정.
- 교차 리뷰로 발견한 encoded 로그인 경로 제한 우회·아이디 정규화 우회·Git token scheme/port 격리 수정.

## 마지막 실제 검증

- `scripts/test-postgres.ps1`: PostgreSQL17.9 격리 클러스터 생성/시작/종료 및 `mvnw verify` 성공. 총140건 중138통과, 외부 서비스 선택검증2건 skip.
- 실제 PostgreSQL7개 HTTP/JSP/권한/배치/SQL오류 rollback 검사와 관리자 동시성1개 포함. 소유자 수동 POST→큐→리뷰→DB 저장 확인.
- 공개 GitHub octocat/Hello-World 전체 이력·head 재개 smoke: 별도 실행 통과(1.86초).
- 설치된 로컬 Ollama gemma3:1b smoke: JSON 프로토콜 통과(72.75초). 안전한 변경에 오탐·영어 응답 확인, 품질 합격 아님 (`docs/AI-EVALUATION.md`).
- 실행 WAR의 브라우저 로그인·빈 대시보드·프로젝트 URL 등록/승인 확인. 390/1280 viewport에서 page 가로 넘침 없음. 모든 화면/키보드 전체 검증은 남음.
- 실패했던 테스트/실행 문제는 수정 후 위 검증으로 재확인. 외부 GitLab/LiteLLM·운영 배포·부하·복원·취약점 검사는 미실행.
- 커밋 전 교차 검토의 이슈 전체 필터·diff 문자열 오인 문제를 수정하고 HTTP 및 adapter 회귀 검증 통과.
- 후속 `scripts/test-postgres.ps1 -BackupRestore`: 총208건 중205통과, 선택검증3건 skip. 실제 PostgreSQL 작성자 매핑 HTTP/배정·권한 검사 및 V2→V4 업그레이드 통과. 환경변수 테스트 fixture를 실제 Spring 환경 소스 이름으로 수정 후 전체 재검증함.
- pg_dump→새 격리 DB pg_restore 후8개 테이블 행 내용·건수 fingerprint와 identity 시퀀스 삽입 일치. 검증 DB만 제거, 원본 보존. `.local/backups/restore-report.json`에 증거 기록.
- 합성 AI 품질 평가6사례와 보고서 생성/선택 gate 준비, 실제 모델 평가 실행은 다음 단계.

## 다음 작업

1. 관리자 작성자 매핑(V3), 배정 근거(V4), 복수 Git origin 자격증명 구현 후 통합 검증.
2. 고정 합성 AI 평가 집합과 격리 DB 백업/복원 검증을 실행하고 결과 기록.
3. 큰/binary/rename 변경 처리와 운영 검증은 릴리스 체크리스트 기준으로 이어서 진행.

## 재개·운영 메모

- 원격 origin: jang-sw/code-reviewer_by-codex. main 직접 푸시 별도 승인 없음 → 로컬 커밋만, 푸시/배포 없음.
- `.local/pg-test` 개발검증 PG는127.0.0.1:55432, 운영 설치 DB와 분리. `.local/pg-validation` 스크립트 PG는55439, 스크립트가 종료함.
- 격리 UI 테스트 WAR는127.0.0.1:18080, 리뷰 스케줄 비활성. 종료 전 상태를 정리한다. `.local`은 Git 제외.
- GitLab 작성자 origin/email 매핑과 복수 토큰 지원. Binary/일부rename/mode-only, oversized diff·merge는 안전하게 실패함.
- `docs/RELEASE-CHECKLIST.md` 미완료 항목이 남으면 완료라고 보고하지 않는다. 사용자가 `이어서 진행`하면 실제 Git/코드/문서를 대조하고 계속한다.
