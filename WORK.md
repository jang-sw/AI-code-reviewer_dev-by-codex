# 현재 작업 상태

- 작업: 실사용 AI 소스코드 리뷰 시스템 신규 개발. **개발 중, 릴리스 완료 아님.**
- 이번 회차: 2026-09-25 22:32 KST 시작, 상한 약 2시간 (09-26 00:32 KST).
- 기준 브랜치/커밋: `main` / `4b00b94` (작성자 매핑·복수 자격증명), 검토 범위/운영 검증 후속 작업 중.
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
- 합성 AI 품질 평가6사례 실제 gemma3:1b 실행: JSON 모두 통과, 품질 기준0/6. 안전한 변경 오탐·실제 결함 미설명·영어 응답. docs/AI-EVALUATION.md에 사례별 대조 기록. 보고서 생성 테스트 성공을 품질 합격으로 해석하지 않는다.
- V5 EMPTY/METADATA_ONLY 검토 범위 기능까지 전체241건 중238통과, 선택3skip. 빈/메타데이터 커밋은 본문 AI 검토 없이 분류하고 수동 확인 안내; 혼합 본문은 AI 호출. 실제PG 저장/화면출력·마이그레이션·백업복원 재검증.
- WAR의 관리자 매핑 등록/감사 화면, 모바일390px 가로 넘침 없음 및 Tab→본문 건너뛰기→검색 필드 이동 확인. 화면 조각이 중복된 fullPage 캡처는 DOM 중복 아님(필드/푸터 각1개 확인).
- 본문 없는 header-only diff가 FULL로 통과할 수 있던 경계를 차단하고 symlink 모드 안내를 보완. Tomcat11.0.26 보안 수정 후 전체245건 중242통과, 선택3skip 및 백업복원 통과.
- 운영 Maven 의존성87개 OSV 검사: 기존 Tomcat3공지 발견, 공급자 최신 공지와 대조해11.0.26 갱신 후 일치0개. 실제 WAR5개Tomcat 모듈 버전 확인. 환경 전체의 보안 합격을 의미하지 않음.
- CI workflow 추가: Java25/PG17 실DB 검증, 외부모델 비활성, 필수DB 테스트 skip 실패 gate. actionlint/셸 문법/Python gate6개·의존성 scanner12개 통과. 원격 CI 실행은 푸시하지 않아 미실행.

## 다음 작업

1. 검토 범위·의존성 보안·CI 변경 커밋.
2. 전체 이력의 화면 페이지 처리/원본 커밋 링크, 설치된 Llama8b 비교 결과 정리.
3. 큰/binary/GitHub rename 변경 처리·이력 확장·운영 모델 교체/평가와 운영 검증은 릴리스 체크리스트 기준으로 이어서 진행.

## 재개·운영 메모

- 원격 origin: jang-sw/code-reviewer_by-codex. main 직접 푸시 별도 승인 없음 → 로컬 커밋만, 푸시/배포 없음.
- `.local/pg-test` 개발검증 PG는127.0.0.1:55432, 운영 설치 DB와 분리. `.local/pg-validation` 스크립트 PG는55439, 스크립트가 종료함.
- 격리 UI 테스트 WAR는127.0.0.1:18080, 리뷰 스케줄 비활성. 종료 전 상태를 정리한다. `.local`은 Git 제외.
- GitLab 작성자 origin/email 매핑과 복수 토큰 지원. Binary/일부rename/mode-only, oversized diff·merge는 안전하게 실패함.
- `docs/RELEASE-CHECKLIST.md` 미완료 항목이 남으면 완료라고 보고하지 않는다. 사용자가 `이어서 진행`하면 실제 Git/코드/문서를 대조하고 계속한다.
