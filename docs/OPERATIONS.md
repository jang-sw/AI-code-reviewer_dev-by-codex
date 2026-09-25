# 설정과 운영 가이드

현재 개발 검증용 안내다. 운영 릴리스 승인은 WORK.md와 릴리스 점검 결과를 확인한다.

## 구성

Java 25 / Spring Boot 4.0.8 / Maven Wrapper 3.9.11 / PostgreSQL 17 / JSP·JSTL을 사용한다. 서버는 WAR 하나로 웹 화면, DB 접근, Git/AI 어댑터, 예약 실행을 제공한다. 다중 인스턴스 간 같은 프로젝트의 동시 리뷰는 PostgreSQL 세션 advisory lock으로 차단한다.

애플리케이션 기본 바인딩은 `127.0.0.1:8080`이다. 데이터는 PostgreSQL에 저장한다. Git/AI 응답과 소스는 서버 파일이나 일반 로그에 저장하지 않으며 리뷰 요약·권고만 DB에 남긴다. 로컬 합성 smoke 테스트는 예외적으로 `target/`에 결과를 기록한다.

## 최초 실행

1. 앱 전용 PostgreSQL 데이터베이스와 소유 계정을 생성한다. 다른 서비스의 DB를 재사용하지 않는다.
2. 다음 환경변수를 설정한다. 실제 비밀번호는 셸 기록에 직접 쓰지 말고 배포 환경의 secret 또는 `Read-Host -MaskInput` 같은 안전한 입력을 이용한다. 예제 문자열은 실제 자격증명이 아니다.

| 환경변수 | 기본값 / 의미 |
|---|---|
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5432/ai_reviewer` |
| `DB_USERNAME` | `ai_reviewer` |
| `DB_PASSWORD` | 필수 운영 비밀번호, 기본값 없음 |
| `BOOTSTRAP_ADMIN_USERNAME` | 최초 관리자 ID |
| `BOOTSTRAP_ADMIN_PASSWORD` | 최초 관리자 비밀번호: 최소 12자, UTF-8 72바이트 이하 |
| `BOOTSTRAP_ADMIN_GIT_USERNAME` | 관리자 Git 사용자명 |
| `SERVER_ADDRESS`, `SERVER_PORT` | `127.0.0.1`, `8080` |
| `SESSION_COOKIE_SECURE` | 로컬 HTTP는 `false`, HTTPS 운영은 `true` |

3. `source`에서 `.\mvnw.cmd verify`로 패키징한다.
4. `java -jar target/ai-code-reviewer.war`로 실행한다. 시작 시 Flyway 마이그레이션이 자동 적용된다. 새 DB에서 초기 관리자 설정이 없으면 시작에 실패한다.
5. 로그인 후 사용자 관리에서 계정을 생성한다. 일반 사용자가 저장소 URL을 등록하면 PENDING 상태가 되며, 관리자가 프로젝트 상세 화면에서 승인한다. 기본 브랜치 또는 입력한 한 브랜치를 리뷰한다.
6. 승인 후 시간표가 실행되거나 `리뷰 실행`을 누르면 전체 이력을 오래된 순서로 처리한다. 사용자 생성 시 Git 계정은 관리자가 확인한다. 이슈는 AI의 권고이며 코드 수정 전에 사람이 검토한다.
7. 최초 관리자 생성 뒤 bootstrap 환경변수는 제거한다. 기존 DB에서는 이 값으로 관리자 비밀번호를 덮어쓰지 않는다.

설정 파일은 `source/src/main/resources/application.properties`이다. 추가 로컬 설정은 Git에서 제외된 `source/application-local.properties`를 만들고 `--spring.profiles.active=local`로 읽을 수 있다. `.env` 파일은 자동 로딩하지 않는다. secret 파일의 OS 접근 권한은 운영자가 제한한다.

## Git 연결

| 설정 / 환경변수 | 의미 |
|---|---|
| `app.git.allowed-hosts` / `GIT_ALLOWED_HOSTS` | 기본 `github.com`, 허용할 GitLab 호스트를 쉼표로 추가 |
| `app.git.github-api-url` / `GITHUB_API_URL` | 기본 `https://api.github.com` |
| `app.git.token` / `GIT_TOKEN` | 읽기 전용 저장소 접근 토큰 |
| `app.git.token-host` / `GIT_TOKEN_HOST` | 토큰을 전송할 정확한 저장소 호스트, 기본 `github.com` |
| `app.git.token-origin` / `GIT_TOKEN_ORIGIN` | 기본 `https://<token-host>:443`; HTTP/다른 포트는 신뢰할 전체 origin을 명시 |
| `app.git.timeout-seconds` / `GIT_TIMEOUT_SECONDS` | HTTP 요청 전체 시간 제한, 기본 30초 |
| `app.git.operation-timeout-seconds` / `GIT_OPERATION_TIMEOUT_SECONDS` | 한 배치의 Git 수집 전체 시간 제한, 기본 300초 |
| `app.git.max-history-pages` | 기본 1000, 페이지당 최대100커밋 |
| `app.git.max-diff-bytes` | 커밋 diff 최대262144바이트 |
| `app.git.max-response-bytes` | HTTP 응답 최대2097152바이트 |

저장소 ID는 입력하지 않는다. GitHub는 `/owner/repository`, GitLab은 `/group/subgroup/repository`를 자동 해석하고 GitLab API의 project path를 인코딩한다. `.git`과 마지막 `/`를 정규화한다. URL 안의 계정·토큰, query, fragment를 거부한다. HTTP GitLab은 명시적으로 허용된 호스트에서만 가능하며 운영에서는 HTTPS를 권장한다. HTTP 리다이렉트는 따라가지 않는다.

여러 비공개 Git 서버는 `app.git.credentials` 목록으로 구성한다. 예를 들어 Git에서 제외한 `application-local.properties`에 아래처럼 환경변수 참조를 적는다. Spring의 환경변수 바인딩으로 `APP_GIT_CREDENTIALS_0_ORIGIN`, `APP_GIT_CREDENTIALS_0_TOKEN`도 사용할 수 있다.

```properties
app.git.credentials[0].origin=https://github.com
app.git.credentials[0].token=${GITHUB_READ_TOKEN}
app.git.credentials[1].origin=https://gitlab.example.com:8443
app.git.credentials[1].token=${GITLAB_READ_TOKEN}
```

각 호스트는 `GIT_ALLOWED_HOSTS`에 있어야 한다. 목록은 최대100개이며 같은 origin의 중복 설정은 시작 시 거부한다. 기존 단일 `GIT_TOKEN` 설정도 지원하지만 같은 origin을 목록과 동시에 설정하지 않는다. 토큰은 scheme·host·port가 일치할 때만 전달하고, 토큰이 설정된 호스트의 다른 origin이면 요청 전에 실패한다. 다른 호스트의 공개 저장소에는 토큰을 보내지 않는다. 회전 시 secret을 교체하고 프로세스를 재시작한 후 권한 있는 사용자로 리뷰를 재실행한다. 런타임 자동 reload는 지원하지 않는다. GitHub Enterprise와 URL 하위 경로에 설치한 GitLab은 아직 지원 검증 대상이다.

GitHub 작성자 계정을 활성 사용자의 Git 계정과 먼저 매칭한다. GitLab 또는 GitHub 계정 매칭이 없는 커밋은 관리자의 **Git 작성자 매핑**에서 설정한 정확한 서버 origin + 전체 작성자 이메일로 배정한다. 이메일은 trim/소문자 정규화하며 이름이나 `@` 앞부분으로 추정하지 않는다. 매핑이 없거나 대상 계정이 비활성화되면 프로젝트 소유자에게 배정하고 이슈에 근거를 표시한다.

커밋 이메일은 작성자가 넣은 메타데이터이며 인증된 신원을 뜻하지 않는다. 관리자는 저장소 팀 구성과 대조해 매핑을 등록해야 한다. 매핑은 이슈 배정에만 사용하며 프로젝트 열람이나 계정 권한을 부여하지 않는다. 변경하려면 삭제 후 다시 등록하고, 이미 생성한 이슈의 담당자/배정 근거는 유지한다. 이메일 원문은 관리자 매핑 화면과 DB에만 저장·표시하며 일반 이슈 화면·감사 메시지에는 넣지 않는다.

## AI 연결

| 환경변수 | 기본값 |
|---|---|
| `AI_PROVIDER` | `ollama`, 다른 값은 `litellm` |
| `AI_BASE_URL` | `http://127.0.0.1:11434` |
| `AI_MODEL` | `gemma3:1b` |
| `AI_API_KEY` | 없음; LiteLLM에서 필요시 설정 |
| `AI_TIMEOUT_SECONDS` | 120 |
| `AI_CONTEXT_TOKENS` | 32768 |
| `AI_MAX_OUTPUT_TOKENS` | 4096 |

Ollama는 `<base-url>/api/chat`, LiteLLM은 `<base-url>/chat/completions`를 사용한다. 프록시가 `/v1/chat/completions`를 제공하면 base URL을 `http://host:4000/v1`로 설정한다. 운영 LiteLLM에는 HTTPS와 접근 인증을 설정하고 해당 모델이 JSON schema structured output을 지원하는지 확인한다. 공급자를 LiteLLM으로 설정하면 코드 diff가 해당 프록시/모델로 전송되므로 조직이 승인한 주소와 모델만 설정한다.

모델 context와 출력 예산을 실제 모델 용량에 맞춘다. 입력은 byte 기반 보수적 예산 검사로 silent context truncation을 방지한다. 큰 diff를 임의로 잘라 성공 처리하지 않는다. 구조가 잘못된 JSON, 잘못된 파일/행, 생성 중단, 거부, 과대 응답은 실패로 기록한다.

실제 `gemma3:1b` 연결은 확인했지만 오탐을 관찰했다. [AI 검증 기록](AI-EVALUATION.md)을 확인한다. 더 큰 모델의 품질 평가 및 실제 LiteLLM 환경 검증이 남아 있다.

## 시간표와 복구

- `REVIEW_ENABLED=true`가 기본값이며 `REVIEW_CRON=0 0 * * * *`는 UTC 기준 매 정시다. 스케줄러를 끄더라도 권한 있는 수동 실행은 가능하다.
- `REVIEW_MAX_COMMITS=100`, `REVIEW_CONCURRENCY=2`가 기본이다. 작업 큐는 인스턴스당1000개로 제한된다. DB pool은 `2 * concurrency + 2` 이상이어야 하며 부족하면 시작에 실패한다.
- 커밋/이슈/실행 성공 건수는 커밋 단위로 원자적으로 저장한다. 배치 전체 성공 뒤 first-parent 기준 안전한 경계로 진행 지점을 갱신한다.
- 실패하면 이전 배치 경계를 유지하고 재실행 시 이미 저장된 SHA는 건너뛴다. AI 호출 실패를 빈 결과 성공으로 기록하지 않는다.
- 리뷰 중 프로젝트가 일시정지되면 이후 DB 저장 단계에서 다시 상태를 검사한다. 이미 실행 중인 HTTP 호출은 시간 제한까지 걸릴 수 있다.
- 프로세스 중단 뒤 다음 실행은 해당 프로젝트 잠금을 확보한 경우에만 이전 RUNNING을 실패로 정리한다. 메모리 큐는 재시작 시 사라지며 승인 프로젝트는 다음 정시에 다시 대상이 된다.
- force-push로 커서가 없어지거나 first-parent 순서가 바뀌면 자동으로 이력을 건너뛰지 않고 실패한다. 관리자가 이력을 대조하는 복구 절차/UI는 릴리스 전 보완 대상이다.
- 큰 merge 묶음이 배치 한도를 넘으면 한도를 올리거나 후속 개선이 필요하다. 현재 최대1000커밋이다.
- binary, 제공되지 않는 patch, 일부 rename/mode-only, API 잘림은 명시적으로 실패한다. 원본 기반 diff 대안과 검토 제외 정책은 후속 개발 대상이다.

## 계정·보안

세션 인증, CSRF, BCrypt12, CSP, 프레임 차단, 출력 escaping을 사용한다. 비밀번호 초기화/변경과 계정 활성 상태 변경 시 security version으로 기존 세션을 폐기한다. 마지막 활성 관리자는 비활성화할 수 없다.

로그인은 인스턴스별로 기본15분 동안 계정10회/IP100회로 제한한다. 버킷10000개 제한이며 가득 차면 새 키를 거부한다. 여러 인스턴스 운영에서는 프록시 계층의 공유 rate limit이 추가로 필요하다. `getRemoteAddr()`를 사용하며 임의의 전달 헤더를 신뢰하지 않는다. 프록시 구성에 따라 모든 요청이 프록시 IP로 제한될 수 있어 신뢰 가능한 프록시 경계 설정이 필요하다.

운영 TLS 종료·secure cookie·신뢰 프록시·DB 최소권한·secret 회전·백업 보관은 운영자가 설정한다. 기본 health endpoint 세부 정보는 노출하지 않으며 현재 인증이 필요하다. 대시보드에서 실패 상태, 관리자 감사 기록에서 승인/계정/리뷰/이슈 변경을 확인한다. 모니터링 알림·지표·보존 정책은 후속 개발 대상이다.

## 검증 명령

```powershell
# 빠른 테스트 + 실행 WAR
cd source
.\mvnw.cmd verify

# 저장소 루트: 독립 로컬 PostgreSQL 클러스터 생성/시작, 전체 검증, 종료
.\scripts\test-postgres.ps1

# 같은 격리 테스트 후 pg_dump/pg_restore와 테이블 내용/identity 시퀀스 검증
.\scripts\test-postgres.ps1 -BackupRestore

# source: 로컬 Ollama에 합성 코드만 보내는 선택 검증
$env:RUN_OLLAMA_SMOKE='true'
.\mvnw.cmd '-Dtest=LocalOllamaSmokeTest' test
Remove-Item Env:RUN_OLLAMA_SMOKE

# source: 공개 GitHub 예제 읽기, 토큰/AI 호출 없음
$env:RUN_GITHUB_SMOKE='true'
.\mvnw.cmd '-Dtest=PublicGitHubSmokeTest' test
Remove-Item Env:RUN_GITHUB_SMOKE
```

`scripts/test-postgres.ps1`은 `.local/pg-validation`과 별도 포트를 사용한다. 이 스크립트가 만든 marker가 없는 DB 디렉터리를 재사용하지 않는다. 운영 DB를 삭제하지 않는다. `reviewer_integration`, `identity_security`는 테스트 전용이다. 이 환경에서만 허용하는 trust 인증을 운영 설정으로 복사하지 않는다. 테스트 결과 XML은 `source/target/surefire-reports`, 패키지는 `source/target/ai-code-reviewer.war`에 생성된다.

## 공식 참조

- [Spring Boot 4.0 servlet/JSP 제한](https://docs.spring.io/spring-boot/4.0/reference/web/servlet.html)
- [GitHub commits API](https://docs.github.com/en/rest/commits/commits)
- [GitLab commits API](https://docs.gitlab.com/api/commits/)
- [Ollama chat API](https://docs.ollama.com/api/chat)
- [LiteLLM structured outputs](https://docs.litellm.ai/docs/completion/json_mode)
