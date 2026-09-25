# AI Code Reviewer

## 목적과 현재 상태

승인된 GitHub·설치형 GitLab 프로젝트의 커밋을 매시간 AI로 리뷰하고 사용자에게 내부 수정 권고 이슈를 배정한다. **개발 중이며 운영 릴리스 완료 상태는 아니다.** 확정 요구사항은 `docs/REQUIREMENTS.md`, 재개 지점은 `WORK.md`를 따른다.

## 기술과 구조

- Java 25, Spring Boot 4.0.8, Maven Wrapper 3.9.11, PostgreSQL 17.
- JSP/JSTL 웹 화면. JSP Java scriptlet을 사용하지 않는다. 실행 가능한 WAR.
- Spring Security 세션/CSRF/BCrypt, Spring JDBC, Flyway SQL migration.
- Ollama 직접 연결 또는 LiteLLM. 기본 `gemma3:1b` (기존 문서의 Gemma3.1b), properties/환경변수로 변경 가능.
- `source/src/main/java/com/aicreviewer/` 아래 identity(계정), project(승인), git(수집), ai(모델 연결), review(예약/잠금/저장), issue(이슈함), web(감사/오류) 모듈.
- `source/src/main/resources/` 아래 application.properties, db/migration, static/css.
- `source/src/main/webapp/WEB-INF/jsp/` JSP·공통 fragment. `source/src/test/` 자동 테스트.
- `scripts/test-postgres.ps1`: Windows 격리 PostgreSQL 검증.

## 구현된 흐름

1. 초기 관리자를 명시적 설정으로 생성한다. 관리자가 사용자 ID·비밀번호·Git 사용자명을 등록한다.
2. 사용자는 저장소 HTTP(S) URL로 프로젝트를 신청한다. 프로젝트 ID는 입력하지 않는다. 관리자가 승인·반려·일시정지/재개한다.
3. 기본 매 정시 또는 수동으로 승인 프로젝트를 백그라운드 리뷰한다. 첫 실행은 **전체 이력**을 부모 커밋이 앞서는 순서의 배치로 처리한다.
4. Git API의 누락·잘림을 검사하고 AI에 전체 지원 diff를 전달한다. JSON 스키마/파일/행/크기·시간·context 예산을 검증한다.
5. 커밋별 결과/이슈를 원자적으로 저장하고 배치 전체 성공 시 진행 지점을 갱신한다. 재시도 시 저장된 SHA를 건너뛴다.
6. GitHub 작성자 계정과 사용자 Git 계정을 매칭해 내부 이슈를 배정한다. 매칭 실패 또는 현재 GitLab 작성자는 프로젝트 소유자에게 배정하고 사유를 남긴다.

## 실행·검증

Java 25를 설치하고 `source`에서 `./mvnw.cmd verify`로 검증·패키징한다. 전용 PostgreSQL DB 및 초기 관리자 환경변수를 설정한 뒤 `java -jar target/ai-code-reviewer.war`로 실행한다.

Git 기본 호스트는 `github.com`이다. 설치형 GitLab은 관리자가 허용 호스트를 설정한다. 운영 DB·비밀번호·AI 설정은 [운영 가이드](docs/OPERATIONS.md)를 따른다. 로컬 설정 파일과 자격증명은 커밋하지 않는다.

저장소 루트에서 `./scripts/test-postgres.ps1`을 실행하면 `.local/pg-validation`에 독립 PostgreSQL 클러스터를 만들어 실제 DB/HTTP/JSP/동시성 테스트와 WAR 패키징 후 종료한다. 기본 `mvnw verify`에서는 DB·실서비스 선택 테스트를 건너뛰므로 전체 검증과 구분한다.

기본 자동 테스트는 외부 API/유료 모델을 호출하지 않는다. 실제 GitHub와 Ollama smoke 실행은 운영 가이드의 opt-in 명령을 사용한다. 마지막 실제 실행 결과는 WORK.md에 기록한다.

## 남은 릴리스 과제

- GitLab 작성자별 사용자 매핑, 복수 비공개 호스트 자격증명.
- binary/큰 변경/일부 rename·mode-only 처리와 대형 이력·merge 그룹 확장. 현재 누락 우려 시 실패하며 성공으로 숨기지 않는다.
- 실제 GitLab·LiteLLM 환경, 운영 모델 품질, 장시간·장애·부하 검증.
- 운영 백업/복원·모니터링·보존·업그레이드/롤백·의존성 취약점 검증.
- 상세 기준은 `docs/RELEASE-CHECKLIST.md`와 `docs/AI-EVALUATION.md`를 따른다.
