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
5. 사용자는 공개 `/signup` 화면에서 ID·비밀번호·Git 계정으로 가입을 신청한다. 관리자는 ‘가입 승인’에서 요청을 검토·승인한다. 승인된 사용자가 저장소 URL을 등록하면 프로젝트도 승인 대기가 된다. 관리자가 프로젝트 상세에서 승인하며 기본 브랜치 또는 입력한 한 브랜치를 리뷰한다.
6. 승인 후 시간표가 실행되거나 `지금 리뷰 실행`을 누르면 전체 이력을 오래된 순서로 처리한다. 가입 승인 시 Git 계정의 실제 소유 관계는 관리자가 확인한다. 이슈는 AI의 권고이며 코드 수정 전에 사람이 검토한다.
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
| `AI_PROVIDER` | `ollama`, `litellm`, `openai` |
| `AI_BASE_URL` | `http://127.0.0.1:11434` |
| `AI_MODEL` | `gemma3:1b` |
| `AI_API_KEY` | 없음; LiteLLM에서 필요시 설정 |
| `OPENAI_MODEL` | 없음; OpenAI 사용 시 Responses/Structured Outputs 지원 모델을 명시 |
| `OPENAI_API_KEY` | 없음; OpenAI 전용 서버 secret, 다른 공급자로 전송하지 않음 |
| `AI_TIMEOUT_SECONDS` | 120 |
| `AI_CONTEXT_TOKENS` | 32768 |
| `AI_MAX_OUTPUT_TOKENS` | 4096 |

Ollama는 `<base-url>/api/chat`, LiteLLM은 `<base-url>/chat/completions`를 사용한다. 프록시가 `/v1/chat/completions`를 제공하면 base URL을 `http://host:4000/v1`로 설정한다. 운영 LiteLLM에는 HTTPS와 접근 인증을 설정하고 해당 모델이 JSON schema structured output을 지원하는지 확인한다. 공급자를 LiteLLM으로 설정하면 코드 diff가 해당 프록시/모델로 전송되므로 조직이 승인한 주소와 모델만 설정한다.

모델 context와 출력 예산을 실제 모델 용량에 맞춘다. 입력은 byte 기반 보수적 예산 검사로 silent context truncation을 방지한다. 큰 diff를 임의로 잘라 성공 처리하지 않는다. 구조가 잘못된 JSON, 잘못된 파일/행, 생성 중단, 거부, 과대 응답은 실패로 기록한다.

OpenAI는 고정된 공식 HTTPS Responses endpoint를 사용하며 `AI_BASE_URL`/`AI_API_KEY`를 재사용하지 않는다. `store:false`를 요청하지만 공급자의 로그 보존까지 없애는 설정은 아니다. 코드가 외부로 전송되므로 조직이 승인한 모델과 정책으로 구성한다. 상세 설정·검증 범위는 [OpenAI 연결](OPENAI-INTEGRATION.md), 커밋 검사 방법은 [비밀정보 보호](SECRET-HYGIENE.md)를 따른다. 실제 API 키를 커밋·브라우저 입력·테스트 fixture에 넣지 않는다.

실제 `gemma3:1b`는 오탐·설명 품질 문제가 있었고, 설치된 `llama3.1:8b` 비교에서도6사례 중4개 시간 초과와 근거 문제가 있었다. [AI 검증 기록](AI-EVALUATION.md)을 확인한다. 모델·context 조정 후 품질 재평가 및 실제 LiteLLM 환경 검증이 남아 있다.

## 시간표와 복구

- `REVIEW_ENABLED=true`가 기본값이며 `REVIEW_CRON=0 0 * * * *`는 UTC 기준 매 정시다. 스케줄러를 끄더라도 권한 있는 수동 실행은 가능하다.
- `REVIEW_MAX_COMMITS=100`, `REVIEW_CONCURRENCY=2`가 기본이다. 작업 큐는 인스턴스당1000개로 제한된다. DB pool은 `2 * concurrency + 2` 이상이어야 하며 부족하면 시작에 실패한다.
- 예약 후보는 미실행·최근 시도가 오래된 순서로 큐 전체 용량(1000+동시 실행 수, 최대1016)까지만 SQL 조회한다. 이미 대기 중인 프로젝트는 건너뛰고 빈 슬롯을 채운다. 큐 포화·종료 상태에서는 후보 조회를 생략한다. DB 정렬 비용·다중 인스턴스의 전체 공정성은 별도 부하 검증 대상이다.
- 커밋/이슈/실행 성공 건수는 커밋 단위로 원자적으로 저장한다. 배치 전체 성공 뒤 first-parent 기준 안전한 경계로 진행 지점을 갱신한다.
- 실패하면 이전 배치 경계를 유지하고 재실행 시 이미 저장된 SHA는 건너뛴다. AI 호출 실패를 빈 결과 성공으로 기록하지 않는다.
- 리뷰 중 프로젝트가 일시정지되면 이후 DB 저장 단계에서 다시 상태를 검사한다. 이미 실행 중인 HTTP 호출은 시간 제한까지 걸릴 수 있다.
- 프로세스 중단 뒤 다음 실행은 해당 프로젝트 잠금을 확보한 경우에만 이전 RUNNING을 실패로 정리한다. 메모리 큐는 재시작 시 사라지며 승인 프로젝트는 다음 정시에 다시 대상이 된다.
- force-push로 커서가 없어지거나 first-parent 순서가 바뀌면 자동으로 이력을 건너뛰지 않고 실패한다. 관리자가 이력을 대조하는 복구 절차/UI는 릴리스 전 보완 대상이다.
- 큰 merge 묶음은 여러 배치로 나누어 처리한다. 배치당 새로 처리할 커밋만 최대1000개이며 이미 저장한 커밋의 diff/AI는 재호출하지 않는다. 부분 merge 성공 중에는 기준 커밋이 그대로여도 저장 건수가 증가할 수 있다. 모든 선행 변경 검토가 끝난 안전한 경계에 도달하면 기준을 갱신한다.
- 매 실행은 불변 head 전체 이력을 다시 검증하므로 저장소 크기에 따른 API 비용이 있다. 전체 metadata32MiB, 기본1000페이지, 저장된 SHA131072개 제한을 넘으면 실패한다. 제한을 무시하거나 이력을 자동 절삭하지 않는다. 대형 저장소 부하·재작성 후 관리자 복구는 후속 검증 대상이다.
- 리뷰 기록의 실행/커밋 목록은 최신순50건씩 각각 이전/다음 페이지로 조회한다. 이슈와 커밋 카드에서 원본 커밋을 새 창으로 열 수 있으며 Git 서버 자체 권한은 별도로 적용된다.
- 파일 목록·통계(그리고 GitLab 트리)로 확인된 빈 커밋은 `EMPTY`로 기록하며 AI를 호출하지 않는다. GitLab의 경로/모드 변경은 불변 blob ID로 본문이 같음을 증명한 경우에만 `METADATA_ONLY`로 구분한다. 본문 diff가 섞인 커밋은 메타데이터 변경을 포함해 AI로 전달한다.
- `METADATA_ONLY`는 본문 AI 검토가 없으며 경로·실행권한·파일 유형 변경의 영향은 수동 확인 대상이다. 리뷰 기록에 이전/새 경로와 숫자 모드 및 안내를 표시한다. 자동 권고 이슈는 만들지 않는다. 처리 완료 커밋 수는 이런 커밋을 포함하므로 AI가 검토한 커밋 수와 다르다.
- binary, 제공되지 않는 patch, GitHub의 일부 rename/mode-only, 새 빈 파일, API 잘림은 명시적으로 실패한다. 임의 제외 후 진행하지 않는다. 원본 기반 diff 대안과 수동 검토 이슈 흐름은 후속 개발 대상이다.

## 계정·보안

세션 인증, CSRF, BCrypt12, CSP, 프레임 차단, 출력 escaping을 사용한다. 비밀번호 초기화/변경과 계정 활성 상태 변경 시 security version으로 기존 세션을 폐기한다. 마지막 활성 관리자는 비활성화할 수 없다.

회원가입은 항상 일반 사용자·승인 대기·비활성 상태로 생성한다. 요청의 role/enabled 값으로 승인이나 관리자 권한을 선택할 수 없다. 중복 ID/Git 계정도 동일한 접수 안내를 반환한다. 승인·반려·반려 후 재검토는 관리자만 할 수 있으며 계정 활성화/비밀번호 초기화로 승인 상태를 우회할 수 없다. V7 적용 시 기존 계정은 승인 완료 상태를 유지한다.

회원가입 요청은 로그인 제한과 별도인 IP 기준 기본15분10회, 최대10000개 버킷으로 제한한다. 성공한 요청도 할당량을 되돌리지 않는다. 인코딩된 URL도 동일한 제한과 CSRF 검사를 적용한다. 다중 인스턴스와 프록시 경계의 추가 제한은 로그인과 같은 운영 설정이 필요하다.

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

# source: 공식 공개 GitLab fixture의 pinned 이력/루트 diff/재개, 토큰/AI 없음
$env:RUN_GITLAB_SMOKE='true'
.\mvnw.cmd '-Dtest=PublicGitLabSmokeTest' test
Remove-Item Env:RUN_GITLAB_SMOKE
```

`scripts/test-postgres.ps1`은 `.local/pg-validation`과 별도 포트를 사용한다. 이 스크립트가 만든 marker가 없는 DB 디렉터리를 재사용하지 않는다. 운영 DB를 삭제하지 않는다. `reviewer_integration`, `identity_security`는 테스트 전용이다. 이 환경에서만 허용하는 trust 인증을 운영 설정으로 복사하지 않는다. 테스트 결과 XML은 `source/target/surefire-reports`, 패키지는 `source/target/ai-code-reviewer.war`에 생성된다.

`-BackupRestore`를 추가하면 같은 테스트 클러스터를 확인한 뒤 pg_dump를 만들고 새 임시 DB에 복원한다. 모든 업무 테이블·Flyway 기록의 내용과 identity 시퀀스 삽입을 검증하고 생성한 복원 DB만 제거한다. 결과와 합성 테스트 백업은 `.local/backups`에 남는다. 운영 복원·암호화·보존 정책 검증과 구분한다.

공개 GitLab smoke는 [GitLab 자체 테스트 저장소](https://gitlab.com/gitlab-org/gitlab-test)의 고정 head 이력47개 중 루트 커밋1개의 diff와 해당 루트 기준 재개만 읽는다. 2026-09-26 실제3.794초 통과했다. 전체47개 변경의 리뷰 지원이나 사용자 설치형 GitLab·비공개 인증 검증을 대신하지 않는다.

## CI와 의존성 검사

`.github/workflows/verify.yml`은 push/PR/수동 실행에서 Java25와 임시 PostgreSQL17로 `scripts/verify-ci.sh`를 실행한다. 두 실제 PostgreSQL 테스트가 누락되거나 skip이면 실패한다. 외부 GitHub/GitLab/Ollama/모델 평가 호출은 비활성화한다. 코드 검증만 수행하며 배포하지 않는다. 로컬 문법·gate 검증은 수행했으나 실제 GitHub Actions 실행은 원격 반영 전 미실행이다.

Tomcat은 공급자 보안 수정을 위해 `pom.xml`에서11.0.26으로 고정했다. Maven 운영 의존성 검사와 재현 명령은 [의존성 점검 기록](DEPENDENCY-AUDIT.md)을 따른다. OSV 결과와 공급자 공지를 함께 확인하며, 검사 시점·범위 밖의 안전성을 주장하지 않는다.

## 공식 참조

- [Spring Boot 4.0 servlet/JSP 제한](https://docs.spring.io/spring-boot/4.0/reference/web/servlet.html)
- [GitHub commits API](https://docs.github.com/en/rest/commits/commits)
- [GitLab commits API](https://docs.gitlab.com/api/commits/)
- [Ollama chat API](https://docs.ollama.com/api/chat)
- [LiteLLM structured outputs](https://docs.litellm.ai/docs/completion/json_mode)
