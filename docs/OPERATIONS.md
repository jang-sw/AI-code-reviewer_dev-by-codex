# 설정과 운영 가이드

현재 개발 검증용 안내다. 운영 릴리스 승인은 WORK.md와 릴리스 점검 결과를 확인한다.

확정한 운영 대상은 **Linux 서버, Docker 미사용**이다. 전용 계정·systemd·TLS 프록시·WAR 검증과 백업/새 DB 복원·업데이트/롤백 절차는 [Linux 배포 가이드](LINUX-DEPLOYMENT.md)를 따른다. 실제 서버 배포 검증은 별도로 남아 있다.

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
| `AI_MAX_REVIEW_CALLS` | 8; 커밋당 최대 파일 묶음 호출 수, 허용1..32 |

Ollama는 `<base-url>/api/chat`, LiteLLM은 `<base-url>/chat/completions`를 사용한다. 프록시가 `/v1/chat/completions`를 제공하면 base URL을 `http://host:4000/v1`로 설정한다. 운영 LiteLLM에는 HTTPS와 접근 인증을 설정하고 해당 모델이 JSON schema structured output을 지원하는지 확인한다. 공급자를 LiteLLM으로 설정하면 코드 diff가 해당 프록시/모델로 전송되므로 조직이 승인한 주소와 모델만 설정한다.

모델 context와 출력 예산을 실제 모델 용량에 맞춘다. 입력은 byte 기반 보수적 예산 검사로 silent context truncation을 방지한다. 큰 diff를 임의로 잘라 성공 처리하지 않는다. 구조가 잘못된 JSON, 잘못된 파일/행, 생성 중단, 거부, 과대 응답은 실패로 기록한다.

전체 입력이 한도 안이면 한 번 호출한다. 넘으면 완전한 파일 경계로 분할하며 모든 파일·hunk 경계를 첫 요청 전에 검사한다. 한 파일 자체가 너무 크거나 총 호출 수 한도를 넘으면 전체 변경 경로를 Git에서 다시 증명한 뒤 커밋 전체를 수동 이슈로 넘긴다. 파일 내부를 잘라 보내지는 않는다. 여러 요청도 `AI_TIMEOUT_SECONDS` 하나의 총 시간 제한을 공유한다. 각 응답은 해당 묶음의 파일/행만 가리켜야 하고 합계100개 권고/32000자 요약을 넘거나 마지막 호출이 실패하면 커밋을 저장하지 않는다. 분할 검토 요약에는 파일 간 맥락 검토의 제한을 표시한다.

OpenAI는 고정된 공식 HTTPS Responses endpoint를 사용하며 `AI_BASE_URL`/`AI_API_KEY`를 재사용하지 않는다. `store:false`를 요청하지만 공급자의 로그 보존까지 없애는 설정은 아니다. 코드가 외부로 전송되므로 조직이 승인한 모델과 정책으로 구성한다. 상세 설정·검증 범위는 [OpenAI 연결](OPENAI-INTEGRATION.md), 커밋 검사 방법은 [비밀정보 보호](SECRET-HYGIENE.md)를 따른다. 실제 API 키를 커밋·브라우저 입력·테스트 fixture에 넣지 않는다.

실제 `gemma3:1b`는 오탐·설명 품질 문제가 있었고, 설치된 `llama3.1:8b` 비교에서도6사례 중4개 시간 초과와 근거 문제가 있었다. [AI 검증 기록](AI-EVALUATION.md)을 확인한다. 모델·context 조정 후 품질 재평가 및 실제 LiteLLM 환경 검증이 남아 있다.

## 시간표와 복구

- `REVIEW_ENABLED=true`가 기본값이며 `REVIEW_CRON=0 0 * * * *`는 UTC 기준 매 정시다. 예약을 끄면 **새 예약 생성만** 멈춘다. 이미 접수한 수동·예약 요청은 계속 처리한다.
- `REVIEW_WORKER_ENABLED=true`가 기본이다. 점검/복원 검증 중에는 `false`로 재시작해 접수 요청을 DB에 보존하면서 Git·AI 호출을 멈춘다. 모든 인스턴스에 적용해야 전체 처리가 멈춘다. 새 예약 생성도 중지하려면 `REVIEW_ENABLED=false`를 함께 설정한다. 수동 접수는 저장되고 화면에는 처리 중지 안내가 나온다.
- `REVIEW_MAX_COMMITS=100`, `REVIEW_CONCURRENCY=2`가 기본이다. 실행 동시성은 인스턴스당1..16이며 메모리 작업 대기열을 사용하지 않는다. DB pool은 `2 * concurrency + 2` 이상이어야 하며 부족하면 시작에 실패한다. 여러 인스턴스의 총 동시성은 각 설정의 합이므로 Git·AI 할당량에 맞춰 정한다.
- V12의 `review_request`는 프로젝트마다 마지막 요청 한 건을 보존한다. 접수는 DB commit 후에만 성공 응답한다. 처리 중 추가 요청은 기존 요청으로 합치고 요청자·접수 시각을 바꾸지 않는다. DB 요청 수는 등록 프로젝트 수로 제한되며 전체를 JVM 메모리에 적재하지 않는다. 완료 요청의 상세 실행 이력은 기존 `review_run`에 남는다.
- 예약 시각은 `project.next_review_at`에 보존한다. 시작 후와 이후30초 간격으로 기한이 지난 승인 프로젝트를 최대1000개씩 조회하고 다음 UTC cron 시각으로 원자 갱신한다. 최초 승인/V12 업그레이드 후 예약 시각이 없는 프로젝트도 대상이다. 중단 중 놓친 여러 시간은 한 요청으로 합치며, 이미 대기/실행 중인 요청은 해당 예약도 함께 처리한 것으로 간주한다. 따라서 빠른 cron도30초보다 촘촘한 실행은 보장하지 않는다. cron 변경 후 이미 저장된 다음 시각은 유지되고, 그 시각 처리부터 새 cron을 적용한다.
- 작업자는5초 간격으로 실행 가능한 요청을 SQL 최대64개씩 읽고 빈 실행 슬롯만 채운다. DB 쿼리는 QUEUED/RUNNING별로 각각 최대64행을 선택한 뒤 전체 순서로 합쳐64개만 반환한다. 상태별 정렬 인덱스를 사용할 수 있게 구성했으며 실제 실행 계획과 DB 비용은 데이터 분포에 따라 달라진다. 조회 순서는 다음 확인 가능 시각·접수 시각·프로젝트 번호다. 다른 작업자가 잠근 프로젝트는30초 뒤 다시 확인하도록 미뤄 다음 프로젝트가 진행할 수 있게 한다. 조회/생성/실행 시간과 부하에 따른 지연은 발생할 수 있으며 정시 시작 SLA가 아니다.
- 공유 예약 풀은 기본3스레드로 대기 요청 확인·예약 접수·운영 집계가 각각 진행할 자리를 둔다. 실제 리뷰 실행 동시성인 `REVIEW_CONCURRENCY`와는 별개다. 앞의 설정을 낮추거나 DB 연결이 고갈되면 지연될 수 있다.
- 커밋/이슈/실행 성공 건수는 커밋 단위로 원자적으로 저장한다. 배치 전체 성공 뒤 first-parent 기준 안전한 경계로 진행 지점을 갱신한다.
- 실패하면 이전 배치 경계를 유지하고 재실행 시 이미 저장된 SHA는 건너뛴다. AI 호출 실패를 빈 결과 성공으로 기록하지 않는다.
- 요청 실행 전과 각 커밋 저장 시 프로젝트 승인과 수동 요청자의 현재 승인·활성·소유자/관리자 권한을 검사한다. 권한이 없으면 취소하고 새 예약 권한으로 바꿔 실행하지 않는다. 이미 진행 중인 HTTP 호출은 시간 제한까지 걸릴 수 있다. 일시정지/계정 변경 후 취소 표시는 다음 작업자 확인 때 반영되며 완료된 리뷰/이슈는 보존한다.
- 중단된 RUNNING 요청은 재시작 후 프로젝트 PG advisory lock을 얻은 작업자만 재개한다. 경과 시간만으로 살아 있는 작업자를 탈취하지 않는다. 새 실행권 토큰은 커밋 저장·진행 기준 갱신·최종 성공/실패 트랜잭션에서도 검사한다. 이전 작업자는 새 작업자의 결과를 덮어쓸 수 없다. 배치 성공은 요청·실행 기록·진행 기준이 한 트랜잭션으로 확정된다.
- 종료 인터럽트·DB/트랜잭션 오류는 접수 요청을 없애지 않는다. 복구 실행의 재확인 대기는30초부터 시도 횟수에 따라 두 배씩 늘어나 최대1시간이다. Git/AI의429는 같은 요청으로 대기하며 최대5회/24시간 상한을 적용한다. 한도 초과 후 정시 예약은 자동 재접수하지 않는다. [공유 대기·직접 재접수·업데이트 정책](EXTERNAL-RATE-LIMITS.md)을 따른다. 그 밖의 Git/AI 처리 실패는 실패로 종료하고 사용자가 다시 요청하거나 다음 예약에서 재시도한다. DB 오류와 커밋 저장 전 강제 종료에서는 AI 호출이 다시 일어날 수 있으므로 외부 호출의 정확히 한 번 실행/과금은 보장하지 않는다. 저장 완료된 커밋은 재사용한다.
- force-push로 기준 커밋이 없어지거나 first-parent 순서가 바뀌면 자동으로 이력을 건너뛰지 않고 실패한다. 관리자는 아래 복구 절차로 기존 기록을 보존하며 현재 브랜치의 전체 이력을 다시 대조할 수 있다.
- 큰 merge 묶음은 여러 배치로 나누어 처리한다. 배치당 새로 처리할 커밋만 최대1000개이며 이미 저장한 커밋의 diff/AI는 재호출하지 않는다. 부분 merge 성공 중에는 기준 커밋이 그대로여도 저장 건수가 증가할 수 있다. 모든 선행 변경 검토가 끝난 안전한 경계에 도달하면 기준을 갱신한다.
- 매 실행은 불변 head 전체 이력을 다시 검증하므로 저장소 크기에 따른 API 비용이 있다. 전체 metadata32MiB, 기본1000페이지, 저장된 SHA131072개 제한을 넘으면 실패한다. 제한을 무시하거나 이력을 자동 절삭하지 않는다. 대형 저장소의 부하는 후속 검증 대상이다.
- 리뷰 기록의 실행/커밋 목록은 최신순50건씩 각각 이전/다음 페이지로 조회한다. 이슈와 커밋 카드에서 원본 커밋을 새 창으로 열 수 있으며 Git 서버 자체 권한은 별도로 적용된다.
- 프로젝트 상세·리뷰 기록의 진행 카드는 현재 요청의 실행에 연결된다. 저장된 리뷰 기록 확인 → Git 이력·변경 확인 → 커밋 리뷰·수동 확인 준비 → 이번 실행 결과 정리를 마지막 기록된 단계로 표시한다. 화면을 새로고침해 새 기록을 확인한다. 새 저장 건수는 이번 처리 시도에서 원자 저장한 커밋 수이며 빈 변경·수동 확인 배정도 포함한다. 재시도에서 재사용한 결과는 다시 세지 않는다. 단계/시각 기록이 없는 과거 실행은 그 사실을 표시한다. 전체 분모·완료 예정 시간·실제 프로세스 생존 지표는 아니다.
- 파일 목록·통계(그리고 GitLab 트리)로 확인된 빈 커밋은 `EMPTY`로 기록하며 AI를 호출하지 않는다. GitHub/GitLab의 경로·모드 변경은 불변 blob ID로 본문이 같음을 증명한 경우에만 `METADATA_ONLY`로 구분한다. 본문 diff가 섞인 커밋은 메타데이터 변경을 포함해 AI로 전달한다.
- GitHub의 rename 또는0행 변경 후보가 있는 커밋은 현재와 첫 부모의 SHA에 연결된 전체 tree를 교차 검증한다. 모든 변경 경로·파일 상태·blob·모드가 일치해야 한다. tree 잘림·누락·copy·본문 모순은 실패한다. submodule은 AI 본문 검토가 불가능하므로 전체 경로 증명 후 수동 전환 조건을 따른다. 후보 커밋은 보통 추가3회 GET이 필요하며 기존 요청/배치 시간·응답 크기 예산을 공유한다. 일반 본문 변경만 있는 기존 경로까지 전체 tree 검증을 확대한 것은 아니다.
- GitHub/GitLab의 정규 빈 파일 생성·삭제는 고정 tree, 생성·삭제 상태, canonical Git 빈 blob SHA-1/SHA-256이 모두 맞아야 `METADATA_ONLY`로 처리한다. 실행 파일도 포함하지만 빈 symlink/submodule 생성·삭제는 지원하지 않는다. 내용이 없더라도 파일 존재 자체가 동작에 영향을 줄 수 있으므로 경로·권한의 수동 확인 안내를 남긴다.
- 새 `METADATA_ONLY`는 본문 AI 검토 없이 경로·실행권한·파일 존재의 영향을 수동 확인해야 하므로, 변경 목록과 고정 tree를 다시 증명해 `METADATA_CHANGE` 사유의 수동 이슈로 저장한다. 파일 변경이 없는 `EMPTY`는 이슈가 없다. 본문과 섞인 `FULL`은 메타데이터 header까지 AI에 전달한다. 과거 저장된 `METADATA_ONLY`와 기존 이슈는 보존하며 소급 생성하지 않는다. 처리 완료 커밋 수와 진행 기준은 수동 배정도 포함하며 담당자의 확인 완료와 다르다.
- binary 표시/제공되지 않는 patch/수집 크기 한도/AI 사전 입력 한도는 독립적인 고정 현재·첫 부모 tree와 전체 변경 목록을 대조할 수 있을 때만 `MANUAL_ONLY`로 저장한다. 이번 버전은 해당 커밋의 **모든 변경 경로**를 수동 이슈로 배정하고 다음 커밋으로 진행한다. 커밋당 최대1000개 경로이며 초과 시 실패한다. rename은 이전/새 경로가 각각 업무가 될 수 있다. 전체 원본 바이트를 읽거나 binary 형식을 증명한 것으로 표시하지 않는다.
- 수동 이슈는 `MANUAL_REVIEW`이며 심각도/행 번호가 없고 파일별 고정 객체 SHA·모드·미검토 사유를 보존한다. AI 권고 `AI_FINDING`과 구분한다. 상태 저장 시 확인 결과/사유5..1000자가 필요하며 담당자/관리자만 처리할 수 있다. 이슈를 닫아도 당시 AI 미검토 범위는 유지한다. V10은 기존 이슈/범위를 보존하며 과거 커밋에 업무를 소급 생성하지 않는다.
- GitHub는 파일별 추가/삭제 합계와 전체 통계를 대조하고, GitLab의 미제공 patch는 본문 행수를 확인할 수 없음을 범위 설명에 남긴다. 제공된 본문의 통계 모순이나 목록/트리 잘림·누락·부모 불일치·인증/네트워크/시간 초과·잘못된 응답은 계속 실패한다. AI 응답 오류/거절도 수동 성공으로 바꾸지 않는다. 원본 blob에서 diff 재구성 및 한 커밋 안의 부분 AI/부분 수동 혼합 처리는 후속 범위다.

### 운영 상태와 DB 시간 제한

관리자 메뉴의 **운영 상태**(`/admin/operations`)는 DB 요청의 대기 상태와 최근 실행 기록을50건씩 조회한다. 접수 후 경과와 실행 시작 후 경과를 구분하며, ‘오래된 미완료 요청’은 대기/처리 중 요청 모두 최초 접수 시각으로 찾는다. 반복 복구로 새 실행 시각이 생겨도 오래된 요청이 누락되지 않는다. 최신 실행이 성공하면 이전 실패는 실패 목록에서 제외한다. `OPERATIONS_STALE_AFTER_MINUTES=120`(1..10080)은 관찰용 기준이며 실행 완료 보장 시간이 아니다. RUNNING 기록만으로 다른 프로세스가 현재 살아 있는지는 판단할 수 없다. DB 조회 장애를 빈 정상 목록으로 숨기지 않는다.

JDBC 조회는 `JDBC_QUERY_TIMEOUT_SECONDS=30`, PostgreSQL 소켓 읽기는 `JDBC_SOCKET_TIMEOUT_SECONDS=45`, 연결은 `JDBC_CONNECT_TIMEOUT_SECONDS=10`초로 제한한다. 각1..3600이고 소켓 한도는 쿼리 한도보다 커야 한다. advisory lock 획득/해제에도 쿼리 제한을 적용한다. URL에 별도 `socketTimeout`/`connectTimeout`을 넣으면 이 값과 정확히 일치해야 하며 무제한/충돌/중복을 시작 시 거부한다. Hikari의 pool 대기10초와 실제 SQL/네트워크 시간 제한은 별개다. 제한 초과 실행의 저장 완료 여부를 확인하고 재시도한다.

MVC에서 처리되지 않은 예외는 고정500 화면으로 반환하고 기본 로그에는 예외 종류와 생성한 참조번호만 남긴다. SQL·예외 원문·요청값은 이 처리기에서 기록하지 않는다. 기존 보안/입력 검증 상태는 유지하며 필터·JSP 렌더 단계 예외나 별도로 활성화한 debug 로그까지 정화하는 기능은 아니다. 운영 로그 접근과 보존 정책은 별도로 적용한다.

### 강제 푸시 후 관리자 복구

1. 리뷰 기록의 실패 원인과 Git 서버의 현재 브랜치 이력을 대조한다. URL·브랜치·접근 권한을 확인하고 프로젝트 상세에서 **리뷰 일시 중지**를 누른다.
2. 실행 중인 리뷰가 종료된 후 상세 화면을 새로고침한다. **Git 이력 변경 후 리뷰 진행 기준 복구**를 펼쳐 화면의 저장소 주소를 그대로 입력하고 5~500자의 복구 사유를 적는다. 사유에 비밀번호·토큰·소스코드를 넣지 않는다.
3. **기존 기록을 보존하고 진행 기준 초기화**를 누른다. 관리 권한·일시 중지 상태·현재 기준 SHA·URL을 다시 확인하며 실행 중이면409로 거부한다. 기준 초기화와 이전 SHA/사유 감사 기록은 하나의 트랜잭션으로 처리한다.
4. 프로젝트는 일시 중지 상태를 유지한다. 확인 후 **리뷰 재개**를 누른다. 자동 리뷰가 꺼져 있거나 바로 실행하려면 **지금 리뷰하기**를 누른다. 현재 브랜치 전체 이력 중 저장된 SHA는 재사용하고 새 SHA만 리뷰한다.

기존 리뷰·실행 기록·이슈의 상태와 담당자는 삭제하거나 초기화하지 않는다. 현재 브랜치에서 사라진 커밋의 이슈도 남는다. 복구 후 이미 해결한 이슈를 다시 열지 않는다. 이 기능은 부족한 API 권한·모델 품질·큰 diff 한도를 해결하지 않으며 저장된 SHA 수 제한도 유지한다. 기준 SHA가 없는 최초 진행 중 실패는 초기화 대상이 아니므로 실패 원인을 해결한 뒤 재시도한다.

## 계정·보안

세션 인증, CSRF, BCrypt12, CSP, 프레임 차단, 출력 escaping을 사용한다. 비밀번호 초기화/변경과 계정 활성 상태 변경 시 security version으로 기존 세션을 폐기한다. 마지막 활성 관리자는 비활성화할 수 없다.

회원가입은 항상 일반 사용자·승인 대기·비활성 상태로 생성한다. 요청의 role/enabled 값으로 승인이나 관리자 권한을 선택할 수 없다. 중복 ID/Git 계정도 동일한 접수 안내를 반환한다. 승인·반려·반려 후 재검토는 관리자만 할 수 있으며 계정 활성화/비밀번호 초기화로 승인 상태를 우회할 수 없다. V7 적용 시 기존 계정은 승인 완료 상태를 유지한다.

회원가입 요청은 로그인 제한과 별도인 IP 기준 기본15분10회, 최대10000개 버킷으로 제한한다. 기존 용량 의미를 유지하여 주소당 내부 버킷2개를 쓴다. 성공한 요청도 할당량을 되돌리지 않는다. 인코딩된 URL도 동일한 제한과 CSRF 검사를 적용한다. V14부터 같은 DB를 사용하는 서버들이 횟수와 만료 시각을 공유한다.

로그인은 같은 DB 전체에서 기본15분 동안 계정10회/IP100회로 제한한다. 성공하면 계정 횟수만 지우고 IP 횟수는 유지한다. 버킷10000개 제한이며 가득 차면 새 키를 거부하고 기존 차단 계정을 축출하지 않는다. 서버 재시작으로 초기화되지 않는다. `getRemoteAddr()`를 사용하며 임의의 전달 헤더를 신뢰하지 않는다. 프록시 구성에 따라 모든 요청이 프록시 IP로 제한될 수 있어 신뢰 가능한 프록시 경계 설정이 필요하다. 프록시의 연결·요청량 제한은 DB 및 비밀번호 검증 부하를 줄이는 별도 계층이다.

DB 확인 실패·잠금 시간 초과·서버 간 제한 설정 불일치 시 로그인/가입을 진행하지 않고503과 `Retry-After: 30`을 반환한다. 정상적인 횟수/용량 초과는429와 남은 창의 대기 시간을 반환한다. 인증 성공 뒤 계정 초기화만 실패하면 이미 쓴 횟수를 보존한 채 성공을 마무리한다. [공유 제한의 설정·전환·복원·보존 정책](AUTH-LIMITING.md)을 따른다.

운영 TLS 종료·secure cookie·신뢰 프록시·DB 최소권한·secret 회전·백업 보관은 운영자가 설정한다. 관리자 메뉴의 **서버 상태**는 주기적으로 수집한 실패·지연·요청 수치와 현재 서버 설정을 보여준다. 미수집·실패·오래된 관측은 정상0건으로 표시하지 않는다. 알림은 화면에만 제공하며 이메일·푸시는 발송하지 않는다. 익명 GET/HEAD는 상태만 반환하는 `/actuator/health/liveness`와 DB 점검을 포함한 `/actuator/health/readiness`에 한정하고, health 루트·metrics·서버 상태 화면/JSON은 현재 승인·활성 ADMIN만 조회한다. 공개 probe의 상태가 외부 Git·AI 연결이나 리뷰 진행을 보증하지는 않는다. 관측 주기·캐시·접근 정책·지표와 다중 서버 해석은 [모니터링 가이드](MONITORING.md)를 따른다. 실서버 설치·장기 관측·외부 알림과 보존 정책 검증은 별도로 남아 있다.

## 검증 명령

```powershell
# 빠른 테스트 + 실행 WAR
cd source
.\mvnw.cmd verify

# 저장소 루트: 독립 로컬 PostgreSQL 클러스터 생성/시작, 전체 검증, 종료
.\scripts\test-postgres.ps1

# 같은 격리 테스트 후 pg_dump/pg_restore와 테이블 내용/identity 시퀀스 검증
.\scripts\test-postgres.ps1 -BackupRestore

# 실제 WAR를 종료·재시작하여 접수 보존과 중단 커밋 재개까지 검증
.\scripts\test-postgres.ps1 -BackupRestore -ReviewRestart

# 실제 WAR 두 개에서 프로젝트 중복 실행 방지와 느린 프로젝트 뒤의 진행 검증
.\scripts\test-postgres.ps1 -ReviewConcurrency

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

`-ReviewRestart`는 빌드한 WAR를 세 번 시작해 처리 중 강제 종료와 DB 요청 재개를 검사한다. GitLab/Ollama 응답은 로컬 합성이며 외부 저장소·실제 모델·유료 API를 호출하지 않는다. [요청 복구 검증 기록](QUEUE-VALIDATION.md)에 재현 절차와 한계를 적었다.

`-ReviewConcurrency`는 같은 전용 schema를 사용하는 WAR 두 개와 합성 프로젝트 세 개를 실행한다. 느린 AI 응답을 보류한 동안 다른 서버의 잠금 경쟁과 후속 프로젝트 완료를 검사한다. [두 서버 검증](WORKER-CONCURRENCY-VALIDATION.md)의 재현·제한을 따른다. 각 도구는 자신이 만든 프로세스·schema·작업 디렉터리만 정리한다.

공개 GitLab smoke는 [GitLab 자체 테스트 저장소](https://gitlab.com/gitlab-org/gitlab-test)의 고정 head 이력47개 중 루트 커밋1개의 diff와 해당 루트 기준 재개만 읽는다. 2026-09-26 실제3.794초 통과했다. 전체47개 변경의 리뷰 지원이나 사용자 설치형 GitLab·비공개 인증 검증을 대신하지 않는다.

외부 호출 없이10,000커밋 이력의 배치·재개·요청 수, 실제 로컬 PostgreSQL의 수동1,000파일·10,000프로젝트 운영 집계를 검증하는 선택 테스트는 [부하 검증](LOAD-VALIDATION.md)을 따른다. 실제 API/AI와 운영 서버를 포함한 처리량 보장과 구분한다.

## CI와 의존성 검사

`.github/workflows/verify.yml`은 push/PR/수동 실행에서 Java25와 임시 PostgreSQL17로 `scripts/verify-ci.sh`를 실행한다. 필수 PostgreSQL 네 suite(`ApplicationPostgresTest`, `IdentityPostgresTest`, `ReviewRequestPostgresTest`, `OperationsTelemetryPostgresTest`)가 누락되거나 skip이면 실패한다. 빌드한 WAR의 합성 프로세스 재시작·두 서버 경쟁 검사도 실행하도록 구성했다. 외부 GitHub/GitLab/Ollama/모델 평가와 대규모 선택 부하는 비활성화한다. 코드 검증만 수행하며 배포하지 않는다. 로컬 문법·gate 검증은 수행했으나 실제 GitHub Actions 실행은 원격 반영 전 미실행이다.

Tomcat은 공급자 보안 수정을 위해 `pom.xml`에서11.0.26으로 고정했다. Maven 운영 의존성 검사와 재현 명령은 [의존성 점검 기록](DEPENDENCY-AUDIT.md)을 따른다. OSV 결과와 공급자 공지를 함께 확인하며, 검사 시점·범위 밖의 안전성을 주장하지 않는다.

## 공식 참조

- [Spring Boot 4.0 servlet/JSP 제한](https://docs.spring.io/spring-boot/4.0/reference/web/servlet.html)
- [GitHub commits API](https://docs.github.com/en/rest/commits/commits)
- [GitLab commits API](https://docs.gitlab.com/api/commits/)
- [Ollama chat API](https://docs.ollama.com/api/chat)
- [LiteLLM structured outputs](https://docs.litellm.ai/docs/completion/json_mode)
