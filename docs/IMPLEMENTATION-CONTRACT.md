# 구현 계약 (1차 개발)

- Java 25, Spring Boot 4.0.8, Maven, PostgreSQL 17, Spring JDBC, Flyway, Spring Security, JSP/JSTL, executable WAR.
- 기본 패키지 `com.aicreviewer`. 서버 시각은 UTC `Instant`, DB `timestamptz`.
- 인증은 세션 + CSRF, 역할 ADMIN/USER. JSP 출력은 c:out, JSP Java scriptlet 금지.
- 기본 리뷰는 내부 이슈이며 외부 Git 이슈 작성은 아직 하지 않는다. 사용자 확정: 첫 승인 후 전체 커밋 이력을 오래된 순서로 배치 리뷰한다.
- 구성: `app.ai.provider=ollama|litellm`, `app.ai.base-url`, `app.ai.model`, `app.ai.api-key`, `app.ai.timeout-seconds`; `app.git.allowed-hosts` (쉼표 구분), `app.git.github-api-url`, `app.git.token`, `app.git.timeout-seconds`; `app.review.enabled`, `app.review.cron=0 0 * * * *`, `app.review.max-commits=100`.
- root 담당: 빌드, 공통 SQL, 설정, 통합, 운영 문서. agents는 본인 모듈과 테스트만 수정. commit은 root가 수행.

## 데이터 계약

`app_user`: id bigint identity PK, username varchar(80) unique, password_hash varchar(255), git_username varchar(100), role varchar(10) ADMIN/USER, enabled boolean, created_at timestamptz, security_version bigint default 0 (V2). username와 git_username은 소문자 정규화, git_username unique.

`project`: id bigint identity PK, name varchar(120), repository_url varchar(2048) unique, provider varchar(10) GITHUB/GITLAB, repository_host varchar(255), repository_path varchar(1024), owner_id bigint FK app_user, status varchar(20) PENDING/APPROVED/REJECTED/PAUSED, review_branch varchar(255) nullable (null이면 기본 브랜치), last_reviewed_sha varchar(64) nullable, approved_at timestamptz nullable, created_at timestamptz, updated_at timestamptz.

`review_run`: id bigint identity PK, project_id bigint FK, status varchar(20) RUNNING/SUCCEEDED/FAILED, started_at timestamptz, finished_at timestamptz nullable, reviewed_commits integer default 0, error_message varchar(1000) nullable.

`reviewed_commit`: id bigint identity PK, project_id bigint FK, commit_sha varchar(64), author_login varchar(100) nullable, summary text, reviewed_at timestamptz, unique(project_id,commit_sha).

`review_issue`: id bigint identity PK, project_id bigint FK, reviewed_commit_id bigint FK, assignee_id bigint FK app_user, severity varchar(10) LOW/MEDIUM/HIGH/CRITICAL, title varchar(240), file_path varchar(1024), line_number integer nullable, description text, suggestion text, status varchar(20) OPEN/RESOLVED/DISMISSED default OPEN, created_at timestamptz, updated_at timestamptz.

`audit_event`: id bigint identity PK, actor_id bigint nullable FK app_user, action varchar(80), target_type varchar(40), target_id bigint nullable, detail varchar(1000), created_at timestamptz.

## 모듈 경계

### identity/project (agent)
- `identity` package: Spring Security UserDetails lookup, security configuration, admin bootstrap env properties `app.bootstrap.username`, `app.bootstrap.password`, `app.bootstrap.git-username`; no default password. User admin UI and password change.
- `project` package: URL registration, owner/admin visibility, admin approve/reject/pause; calls `git.RepositoryUrl.parse(String, Set<String>)` to normalize metadata. Root creates DB migration, agent SQL must match it.
- Routes `/login`, `/admin/users`, `/account/password`, `/projects`, `/projects/{id}`, `/admin/projects/{id}/approve|reject|pause` POST. Views owned by agent identity/project. Shared JSP includes root `/WEB-INF/jsp/fragments/header.jspf`, footer.jspf. View prefix `/WEB-INF/jsp/`.
- principal name is username; access current user with query app_user, no shared user class required.

### integrations (agent)
- package `git`: `RepositoryUrl` record `(String normalizedUrl, String provider, String host, String path)`, static parse(String, Set<String>) allowing HTTPS/explicit configured HTTP self-hosted hosts, no credentials/query/fragment; hosts exact allow-list. GitHub host github.com default; other configured hosts GitLab.
- `GitRepositoryClient` Spring bean method `List<GitCommit> commits(RepositoryUrl repository, String branch, String lastReviewedSha, int limit)` returns oldest-first new commits, initial null cursor enumerates full history and returns the oldest batch up to limit. Existing cursor returns the oldest next batch up to limit. Pin history to immutable head; paginated full history must not silently skip commits. Fail closed if cursor missing/history rewritten/safety page budget exceeded. `GitCommit` record `(String sha, String authorLogin, String message, String diff)`.
- package `ai`: `AiReviewClient` Spring bean method `ReviewResult review(GitCommit commit)`; records `ReviewResult(String summary, List<ReviewFinding> findings)`, `ReviewFinding(String severity, String title, String filePath, Integer lineNumber, String description, String suggestion)`. Validate bounds, output schema, failures; treat diff as untrusted. No silent success if response invalid or diff too large.
- Use Java HTTP client (no redirects), Jackson 3 (`tools.jackson.databind`) from Boot; injected configuration via @Value or private @ConfigurationProperties.
- meaningful HTTP fixtures tests, no live external calls.

### review/issues (agent)
- package `review`: scheduled execution default every hour conditional app.review.enabled, manual POST `/projects/{id}/review` restricted owner/admin approved only. PostgreSQL advisory lock on dedicated connection covers full run across instances, unlock in finally. JDBC transactions persist commit+issues atomically; only checkpoint cursor when whole batch succeeds. On failure retain previous safe batch cursor and deduplicate saved commits on retry. Use GitRepositoryClient and AiReviewClient contracts.
- unique reviewed_commit prevents duplicates. Assign to enabled user with lower(git_username)=lower(authorLogin); else project owner (explicit fallback in UI/docs).
- package `issue`: GET `/issues` visibility assignee or admin, POST `/issues/{id}/status` bound status/ownership. `/` dashboard and project detail review data can be separate `/reviews?projectId=...` route. JSP owned by this agent for dashboard/issues/reviews.
- All record fields for JSP need JavaBean getters or map view models; ensure escaping and CSRF inputs.

## 루트 검증 계획

Maven unit/integration tests with H2 PostgreSQL mode only for fast behavior tests; real isolated PostgreSQL database migrations and end-to-end tests are required separately. Mock AI and Git endpoints, never invoke paid APIs in tests. Browser rendering check for packaged JSP when runnable. Deployment/external issue creation/main push are not authorized by this build request.
