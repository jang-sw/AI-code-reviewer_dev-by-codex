# 구현 계약

- Java 25, Spring Boot 4.0.8, Maven, PostgreSQL 17, Spring JDBC, Flyway, Spring Security, JSP/JSTL, executable WAR.
- 기본 패키지 `com.aicreviewer`. 서버 시각은 UTC `Instant`, DB `timestamptz`.
- 인증은 세션 + CSRF, 역할 ADMIN/USER. JSP 출력은 c:out, JSP Java scriptlet 금지.
- 기본 리뷰는 내부 이슈이며 외부 Git 이슈 작성은 아직 하지 않는다. 사용자 확정: 첫 승인 후 전체 커밋 이력을 오래된 순서로 배치 리뷰한다.
- 구성: `app.ai.provider=ollama|litellm|openai`, `app.ai.base-url`, `app.ai.model`, `app.ai.api-key`, `app.ai.timeout-seconds`; OpenAI 전용 `app.ai.openai-model`/환경변수 `OPENAI_API_KEY`; `app.git.allowed-hosts` (쉼표 구분), `app.git.github-api-url`, `app.git.token`, `app.git.timeout-seconds`; `app.review.enabled`, `app.review.cron=0 0 * * * *`, `app.review.max-commits=100`.
- root 담당: 빌드, 공통 SQL, 설정, 통합, 운영 문서. agents는 본인 모듈과 테스트만 수정. commit은 root가 수행.

## 데이터 계약

`app_user`: id bigint identity PK, username varchar(80) unique, password_hash varchar(255), git_username varchar(100), role varchar(10) ADMIN/USER, enabled boolean, created_at timestamptz, security_version bigint default 0 (V2). username와 git_username은 소문자 정규화, git_username unique.

`project`: id bigint identity PK, name varchar(120), repository_url varchar(2048) unique, provider varchar(10) GITHUB/GITLAB, repository_host varchar(255), repository_path varchar(1024), owner_id bigint FK app_user, status varchar(20) PENDING/APPROVED/REJECTED/PAUSED, review_branch varchar(255) nullable (null이면 기본 브랜치), last_reviewed_sha varchar(64) nullable, approved_at timestamptz nullable, created_at timestamptz, updated_at timestamptz.

`review_run`: id bigint identity PK, project_id bigint FK, status varchar(20) RUNNING/SUCCEEDED/FAILED, started_at timestamptz, finished_at timestamptz nullable, reviewed_commits integer default 0, error_message varchar(1000) nullable.

`reviewed_commit`: id bigint identity PK, project_id bigint FK, commit_sha varchar(64), author_login varchar(100) nullable, summary text, reviewed_at timestamptz, unique(project_id,commit_sha).

`review_issue`: id bigint identity PK, project_id bigint FK, reviewed_commit_id bigint FK, assignee_id bigint FK app_user, severity varchar(10) LOW/MEDIUM/HIGH/CRITICAL, title varchar(240), file_path varchar(1024), line_number integer nullable, description text, suggestion text, status varchar(20) OPEN/RESOLVED/DISMISSED default OPEN, created_at timestamptz, updated_at timestamptz.

`audit_event`: id bigint identity PK, actor_id bigint nullable FK app_user, action varchar(80), target_type varchar(40), target_id bigint nullable, detail varchar(1000), created_at timestamptz.

V3 `git_author_mapping`: id bigint identity PK, user_id FK app_user, repository_origin varchar(512), author_email varchar(320), created_at timestamptz, unique(repository_origin,author_email). 관리자만 생성/삭제하며 정확한 origin과 정규화된 전체 이메일을 사용한다.

V4 `reviewed_commit.author_email` varchar(320) nullable, `review_issue.assignment_reason` varchar(32) default LEGACY: GITHUB_ACCOUNT/GIT_EMAIL_MAPPING/PROJECT_OWNER_FALLBACK/LEGACY. 배정 시점의 근거를 보존하며 일반 화면에 이메일 원문을 노출하지 않는다.

V5 `reviewed_commit.coverage_type` varchar(20) default FULL, `coverage_details` text default '' (최대16000자). 범위는 FULL/EMPTY/METADATA_ONLY. 검증된 EMPTY와 METADATA_ONLY만 AI를 생략하며 해당 사실과 수동 확인 범위를 화면에 명시한다. 일반 누락/잘림 파일을 제외 성공으로 처리하지 않는다.

V6 `reviewed_commit(project_id,id DESC)`, `review_run(project_id,id DESC)` 페이지 조회 인덱스. `/reviews`의 `commitPage`와 `runPage`는 각각0부터10000까지, 페이지당50건이다. 프로젝트 권한 검사 후 두 기록을 독립 조회한다.

## 모듈 경계

V7 `app_user.approval_status` PENDING/APPROVED/REJECTED(default APPROVED), approval_decided_at/approval_reason. 비승인 계정은 enabled=false CHECK. 공개 `/signup`는 USER/PENDING만 생성하고 관리자 승인 후 로그인 가능하다. V8 프로젝트 소유자/상태별 id 페이지 인덱스, V9 이슈 담당자/상태별 id 페이지 인덱스.

V10은 기존 coverage 값을 유지하며 `MANUAL_ONLY`를 추가한다. `manual_review_file`은 프로젝트·커밋·변경 경로·old/new 객체 SHA/모드·reason_code(SOURCE_DIFF_UNAVAILABLE/GIT_DIFF_BUDGET/AI_INPUT_LIMIT)·evidence_kind(PINNED_TREES)를 저장한다. 커밋당 최대1000개, 경로 UNIQUE. `review_issue.issue_kind`는 기존/기본AI_FINDING 또는MANUAL_REVIEW, 후자는 severity/line_number NULL 및 manual_file_id 필수다. 프로젝트/커밋 포함 복합FK와 manual_file_id UNIQUE로 교차 연결/중복을 방지한다. `resolution_note` 최대1000자, 수동 상태 변경의5..1000자 사유와 감사 저장은 원자적이다. `audit_event.detail`은1200자로 확장한다.

`/projects`는 q/status/page, `/admin/users`는 search/status/page, `/issues`는 status/page로 제한된 목록을 조회한다. page는0..10000이며 lookahead 한 건으로 다음 페이지 여부를 판단한다. 이슈 상세 `/issues/{id}`도 담당자/관리자만 조회하며 관련 없는 사용자는 존재하지 않는 이슈와 같은404를 받는다.

### identity/project (agent)
- `identity` package: Spring Security UserDetails lookup, security configuration, admin bootstrap env properties `app.bootstrap.username`, `app.bootstrap.password`, `app.bootstrap.git-username`; no default password. User admin UI and password change.
- `project` package: URL registration, owner/admin visibility, admin approve/reject/pause; calls `git.RepositoryUrl.parse(String, Set<String>)` to normalize metadata. Root creates DB migration, agent SQL must match it.
- Routes `/login`, `/admin/users`, `/account/password`, `/projects`, `/projects/{id}`, `/admin/projects/{id}/approve|reject|pause` POST. Views owned by agent identity/project. Shared JSP includes root `/WEB-INF/jsp/fragments/header.jspf`, footer.jspf. View prefix `/WEB-INF/jsp/`.
- principal name is username; access current user with query app_user, no shared user class required.

### integrations (agent)
- package `git`: `RepositoryUrl` record `(String normalizedUrl, String provider, String host, String path)`, static parse(String, Set<String>) allowing HTTPS/explicit configured HTTP self-hosted hosts, no credentials/query/fragment; hosts exact allow-list. GitHub host github.com default; other configured hosts GitLab.
- `GitRepositoryClient.batch(RepositoryUrl,String branch,String lastReviewedSha,Set<String> reviewedShas,int limit)` returns `GitReviewBatch(List<GitCommit> commits,String checkpointSha)`. Immutable head의 전체 부모 그래프를 검증하고 첫 부모 우선 DFS 순서에서 아직 저장되지 않은 커밋만 최대limit개 선택한다. checkpoint는 선택된 커밋 전체 성공을 전제로 연속 검토 범위의 가장 뒤 first-parent SHA이며 부분 merge이면 null일 수 있다. 이미 저장된 뒤쪽 SHA일 수 있고 선택0건에도 갱신될 수 있다. 기존4인자 `commits(...)`는 마지막 반환 SHA를 checkpoint로 해석하던 호환 계약을 유지하므로 닫힌 merge 경계가 한도 안에 없으면 실패한다. 누락/재작성된 cursor·페이지 예산 초과를 성공 처리하지 않는다.
- `reviewedShas`는 프로젝트 잠금 아래 조회하며 SQL LIMIT131073으로 DB 응답부터 제한,131072건 초과이면 실패한다. Git adapter도 같은 입력 한도를 검사한다. orphan SHA는 현재 그래프의 진행에 관여하지 않는다. 이는 보수적 논리적 메모리 한도이며 프로세스 RSS 보장은 아니다.
- `GitCommit` record는 기존7개 값 뒤에 `List<ManualReviewFile> manualFiles`를 추가한다. 기존4/5/7인자 생성자는 빈 manualFiles로 호환한다. MANUAL_ONLY만1..1000개 고유 경로 증거를 갖고 diff는 비어 있다. `RepositoryOrigin.normalize(origin, allowedHosts)`와 `fromRepositoryUrl(url)`로 scheme/host/port를 정규화한다. `GitCommitLink.from(url,sha)`는 검증된 HTTP(S) URL과40/64자리 SHA만 외부 커밋 링크로 표시한다.
- Git 수동 fallback은 전체 변경 목록과 고정된 현재/첫 부모 tree를 다시 대조하며 커밋 전체 경로를 수동화한다. `manualFallback(repository,original)`은 AI 입력 한도용으로 전체 증명을 다시 수행한다. API 오류/잘린 목록/통계 모순/본문 오류는 일반 실패다. GitLab 미제공 patch의 실제 행수는 검증 불가 사실을 명시한다.
- package `ai`: `AiReviewClient` Spring bean method `ReviewResult review(GitCommit commit)`; records `ReviewResult(String summary, List<ReviewFinding> findings)`, `ReviewFinding(String severity, String title, String filePath, Integer lineNumber, String description, String suggestion)`. Validate bounds, output schema, failures; treat diff as untrusted. No silent success if response invalid or diff too large.
- Use Java HTTP client (no redirects), Jackson 3 (`tools.jackson.databind`) from Boot; injected configuration via @Value or private @ConfigurationProperties.
- meaningful HTTP fixtures tests, no live external calls.
- AI는 단일 입력이 안 맞을 때 완전한 파일 경계로 사전 분할하며 모든 호출에 하나의 총 시간 제한을 적용한다. 1..32개(기본8) 호출, 합계100개 finding/32000자 summary 한도. `AiInputLimitException`의4가지 사전 입력 한도만 수동 전환 대상이며 HTTP/응답/거절/시간/합산 한도 실패는 포함하지 않는다.

### review/issues (agent)
- package `review`: scheduled execution default every hour conditional app.review.enabled, manual POST `/projects/{id}/review` restricted owner/admin approved only. PostgreSQL advisory lock on dedicated connection covers full run across instances, unlock in finally. JDBC transactions persist commit+issues atomically; only checkpoint cursor when whole batch succeeds. On failure retain previous safe batch cursor and deduplicate saved commits on retry. Use GitRepositoryClient and AiReviewClient contracts.
- 관리자 복구 POST `/admin/projects/{id}/review-progress/reset`: PAUSED 프로젝트의 정확한 repositoryUrl/expectedCursor와 reason5..500자를 확인한다. 서비스는 외부 트랜잭션 없이 advisory lease를 획득한 뒤 새 트랜잭션에서 권한/row lock/상태를 재검사한다. last_reviewed_sha=NULL + 감사 기록 commit/rollback 후 lease를 닫는다. 기존 runs/commits/issues와 PAUSED는 보존한다.
- `METADATA_ONLY`에는 같은 blob의 경로·모드 변경과, 고정 tree 및 canonical Git 빈 blob으로 증명한 정규 빈 파일 생성·삭제가 포함된다. AI 본문 검토 및 자동 이슈 생성은 없으며 수동 확인 범위를 표시한다. 본문 diff와 섞이면 FULL로 전달한다.
- `MANUAL_ONLY`는 AI 본문 검토 없이 전체 파일별 수동 이슈·근거·배정 감사·실행 건수를 커밋과 함께 저장한다. 다음 커밋 진행과 사람의 확인 완료는 별개다. 수동 이슈 닫기는 reviewed_commit 범위나 cursor를 바꾸지 않는다.
- unique reviewed_commit prevents duplicates. github.com의 authorLogin과 활성 사용자 Git 계정이 맞으면 우선 배정한다. 그 외에는 정확한 origin/email 관리자 매핑의 활성 사용자, 없으면 프로젝트 소유자 순서다. GitLab 사용자명으로 GitHub 계정 namespace를 매칭하지 않는다.
- package `issue`: GET `/issues` visibility assignee or admin, POST `/issues/{id}/status` bound status/ownership. `/` dashboard and project detail review data can be separate `/reviews?projectId=...` route. JSP owned by this agent for dashboard/issues/reviews.
- JSP 값은 map 또는 현재 Tomcat EL의 record resolver로 조회하며 실제 HTTP 렌더링으로 확인한다. 출력 escaping과 CSRF 입력은 필수다.

## 루트 검증 계획

Maven unit/integration tests with H2 PostgreSQL mode only for fast behavior tests; real isolated PostgreSQL database migrations and end-to-end tests are required separately. Mock AI and Git endpoints, never invoke paid APIs in tests. Browser rendering check for packaged JSP when runnable. Deployment/external issue creation/main push are not authorized by this build request.
