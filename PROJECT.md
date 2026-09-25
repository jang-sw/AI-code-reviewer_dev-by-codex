# AI Code Reviewer

## 목적과 현재 상태

승인된 GitHub·설치형 GitLab 프로젝트의 커밋을 매시간 AI로 리뷰하고 사용자에게 내부 수정 권고 이슈를 배정한다. **개발 중이며 운영 릴리스 완료 상태는 아니다.** 확정 요구사항은 `docs/REQUIREMENTS.md`, 재개 지점은 `WORK.md`를 따른다.

## 기술과 구조

- Java 25, Spring Boot 4.0.8, Maven Wrapper 3.9.11, PostgreSQL 17.
- JSP/JSTL 웹 화면. JSP Java scriptlet을 사용하지 않는다. 실행 가능한 WAR.
- Spring Security 세션/CSRF/BCrypt, Spring JDBC, Flyway SQL migration.
- Ollama, LiteLLM 또는 OpenAI Responses API 직접 연결. 로컬 기본 `gemma3:1b`; OpenAI는 전용 `OPENAI_MODEL`/`OPENAI_API_KEY` 명시 설정.
- `source/src/main/java/com/aicreviewer/` 아래 identity(계정), project(승인), git(수집), ai(모델 연결), review(예약/잠금/저장), issue(이슈함), web(감사/오류) 모듈.
- `source/src/main/resources/` 아래 application.properties, db/migration, static/css.
- `source/src/main/webapp/WEB-INF/jsp/` JSP·공통 fragment. `source/src/test/` 자동 테스트.
- `scripts/test-postgres.ps1`: Windows 격리 PostgreSQL 검증.

## 구현된 흐름

1. 초기 관리자를 명시적 설정으로 생성한다. 사용자가 ID·비밀번호·Git 사용자명으로 회원가입을 신청하면 관리자가 승인·반려한다. 승인 전 로그인은 차단한다.
2. 사용자는 저장소 HTTP(S) URL로 프로젝트를 신청한다. 프로젝트 ID는 입력하지 않는다. 관리자가 승인·반려·일시정지/재개한다.
3. 기본 매 정시 또는 수동으로 승인 프로젝트를 백그라운드 리뷰한다. 첫 실행은 **전체 이력**을 부모 커밋이 앞서는 순서의 배치로 처리한다.
4. Git API의 누락·잘림을 검사하고 AI에 전체 지원 diff를 전달한다. JSON 스키마/파일/행/크기·시간·context 예산을 검증한다.
5. 커밋별 결과/이슈를 원자적으로 저장하고 배치 전체 성공 시 진행 지점을 갱신한다. 재시도 시 저장된 SHA를 건너뛴다.
6. GitHub 계정 연결 또는 관리자가 등록한 서버 origin/작성자 이메일 매핑으로 내부 이슈를 배정한다. 매칭 실패 시 프로젝트 소유자에게 배정하고 이슈마다 근거를 남긴다. 여러 비공개 서버의 토큰은 origin별로 분리한다.
7. 프로젝트·사용자·리뷰 기록은 페이지당50건, 이슈는25건을 조회한다. 이슈 목록은 짧은 요약만 보여주고 상세 화면에서 전체 권고를 읽는다. 필터·권한은 각 조회와 상태 변경에 적용한다.
8. 본문 없는 경로·권한 변경과 정규 빈 파일 생성·삭제도 신규 처리 시 고정 tree를 재확인해 수동 이슈로 배정한다. 이력이 재작성되면 관리자가 프로젝트를 일시 중지하고 기존 리뷰·이슈를 보존하며 진행 기준만 복구할 수 있다.
9. 입력 한도나 미제공 diff가 있으면 전체 변경 경로를 고정 tree로 증명한 뒤 커밋 전체를 수동 확인 이슈로 배정하고 다음 커밋을 진행한다. AI 입력은 가능한 경우 파일 경계로 분할한다. API/응답 오류·증명 실패는 계속 중단한다.
10. 관리자는 운영 상태에서 최신 실패·미실행·오래된 실행 시작을50건씩 확인한다. 수동 업무는 확인 사유와 함께 처리하며 AI 권고와 구분한다.

## 실행·검증

Java 25를 설치하고 `source`에서 `./mvnw.cmd verify`로 검증·패키징한다. 전용 PostgreSQL DB 및 초기 관리자 환경변수를 설정한 뒤 `java -jar target/ai-code-reviewer.war`로 실행한다.

Git 기본 호스트는 `github.com`이다. 설치형 GitLab은 관리자가 허용 호스트를 설정한다. 운영 DB·비밀번호·AI 설정은 [운영 가이드](docs/OPERATIONS.md)를 따른다. 로컬 설정 파일과 자격증명은 커밋하지 않는다.

운영 대상은 **Linux 서버, Docker 미사용**이다. [Linux 배포 자료](docs/LINUX-DEPLOYMENT.md)와 `deploy/linux/`의 systemd·TLS·환경파일 템플릿을 제공한다. 실제 서버 설치·보안·복구 검증은 아직 남아 있다.

저장소 루트에서 `./scripts/test-postgres.ps1`을 실행하면 `.local/pg-validation`에 독립 PostgreSQL 클러스터를 만들어 실제 DB/HTTP/JSP/동시성 테스트와 WAR 패키징 후 종료한다. 기본 `mvnw verify`에서는 DB·실서비스 선택 테스트를 건너뛰므로 전체 검증과 구분한다.

기본 자동 테스트는 외부 API/유료 모델을 호출하지 않는다. 실제 GitHub와 Ollama smoke 실행은 운영 가이드의 opt-in 명령을 사용한다. 마지막 실제 실행 결과는 WORK.md에 기록한다.

## 남은 릴리스 과제

- 실제 대형 이력/변경 부하와 공급자별 수동 전환 호환성. 파일 내부 분할·원본 diff 복원·부분 AI/수동 혼합은 별도 확장이다. 증명할 수 없는 변경은 실패한다.
- 실제 GitLab·LiteLLM 환경, 운영 모델 품질, 장시간·장애·부하 검증.
- 운영 백업/복원·모니터링·보존·업그레이드/롤백·의존성 취약점 검증.
- 상세 기준은 `docs/RELEASE-CHECKLIST.md`와 `docs/AI-EVALUATION.md`를 따른다.
