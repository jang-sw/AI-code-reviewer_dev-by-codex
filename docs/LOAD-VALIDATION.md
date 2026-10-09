# 로컬 합성 부하 검증

2026-09-26 KST, Java25 개발 환경에서 `GitHistoryLoadSmokeTest`를 선택 실행했다. 외부 Git·AI·DB를 호출하지 않는 loopback HTTP fixture 검증이다. 운영 처리량이나 메모리 상한의 증거로 해석하지 않는다.

## 검증 범위

- GitHub와 GitLab 각각 10,000개 선형 커밋, 페이지당100개 이력과 배치당100개 선택.
- 최초100개를 순서대로 선택하고 안전한 진행 기준을 반환한다.
- 진행 기준 저장 전에 중단된 상황을 가정해, 이미 저장된100개 SHA를 전달하면 다음100개만 상세/diff 조회한다.
- 전체10,000개 SHA가 저장되어 있으면 선택0건·상세/diff 조회0건으로 마지막 진행 기준을 복구한다.
- 모든 이력 페이지의 고정 head, loopback GET, 인증 헤더 없음, 요청 수 예산을 검사한다.

## 실제 관찰

JUnit 선택 테스트1개(공급자2개 × 단계3개)가 통과했다. 전체 테스트 메서드는1.928초였다. 아래 시간은 로컬 합성 응답의 관찰값이며 성능 합격 기준이 아니다.

| 공급자 | 단계 | 선택 커밋 | HTTP 요청 | 관찰 시간 |
|---|---|---:|---:|---:|
| GitHub | 첫 배치 | 100 | 202 | 507ms |
| GitHub | 저장 SHA 재사용 | 100 | 202 | 231ms |
| GitHub | 전체 저장 후 진행 기준 복구 | 0 | 102 | 107ms |
| GitLab | 첫 배치 | 100 | 500 | 356ms |
| GitLab | 저장 SHA 재사용 | 100 | 502 | 225ms |
| GitLab | 전체 저장 후 진행 기준 복구 | 0 | 102 | 74ms |

매번 이력 조회102회가 발생했다. 이미 처리한 diff는 다시 읽지 않지만 전체 이력 대조 비용은 남는다. 실제 서비스에는 API 지연·할당량·큰 파일·큰 tree·여러 프로젝트·동시 실행 비용이 추가된다. 이 결과만으로 대형 저장소 운영 부하 검증을 완료 처리하지 않는다. 프로세스 RSS, 장시간 장애, 실제 AI 지연, rate limit과 전체10,000개 diff 처리 시간은 측정하지 않았다. 큰 merge의 분할 정확성은 별도의 일반 회귀 fixture로 검증한다.

## 재현

`source`에서 실행한다. 합성 시험 결과는 Git에서 제외된 `target/git-load-smoke-result.json`에 공급자/단계별 시간·요청 종류·검증 여부로 기록된다. 실패 시 해당 단계의 `validated`는 false다. 기본 테스트와 CI에서는 실행하지 않는다.

```powershell
$priorLoadFlag = $env:RUN_GIT_LOAD_SMOKE
try {
    $env:RUN_GIT_LOAD_SMOKE = 'true'
    .\mvnw.cmd -B -ntp '-Dtest=GitHistoryLoadSmokeTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Offline Git load validation failed' }
} finally {
    $env:RUN_GIT_LOAD_SMOKE = $priorLoadFlag
}
```

## 실제 PostgreSQL 수동 확인 이슈 1,000파일

2026-09-26 KST, `ManualReviewLoadPostgresTest`를 격리된 로컬 PostgreSQL17의 `reviewer_integration`에서 선택 실행했다. 실제 `ReviewRepository`, `ReviewCoordinator`, `IssueService`, 트랜잭션과 PostgreSQL advisory lock을 사용했다. Git 응답과 tree 증거는 합성이며 AI 호출은0회다. 이 검사는 Git 증명의 정확성이나 실제 공급자 처리량을 측정하지 않는다.

- 현재 커밋당 상한인1,000파일의 `MANUAL_ONLY` 커밋 하나를 처리하여 증거1,000행과 수동 이슈1,000행을 저장했다. 커밋 결과·진행 기준·실행 성공·배정 감사 기록도 확인했다.
- 모든 이슈가 같은 프로젝트/커밋의 증거와 연결되고, 담당자가 맞으며 심각도·행 번호는 없고 초기 상태는 `OPEN`임을 확인했다.
- 소유자 목록을25건씩40페이지 조회하여 전체1,000건의 순서·중복·누락, 마지막 페이지의 다음 페이지 없음, 범위 밖 빈 페이지를 검사했다. 목록은 요약만 반환하며 전체 확인 사유나 파일 객체 SHA를 싣지 않는다.
- 소유자와 관리자의 상세·목록 접근, 무관한 사용자의 상세404·빈 목록·리뷰 실행 거절을 검사했다.
- 같은 배치를 다시 전달해도 증거/이슈/커밋/배정 감사가 중복 생성되지 않고 두 번째 실행의 신규 처리 건수가0임을 확인했다.
- 고유 계정·프로젝트만 추가하며 기존 이슈를 삭제하거나 테이블을 초기화하지 않는다. 측정 로그에는 건수·소요시간만 출력한다.

### 실제 관찰과 한계

로컬 로그 `.local/session3-pg-third.log`에서 선택 테스트1건이 실패·오류·건너뜀 없이 통과했다. 테스트 메서드 전체는4.217초였고, 내부 측정은 다음과 같다.

| 구간 | 관찰 시간 |
|---|---:|
| 최초 리뷰 실행: 증거1,000행·이슈1,000행 및 관련 기록 저장 | 647ms |
| 40페이지·경계·접근 권한·상세 조회 검사 | 2,277ms |
| 같은 배치 재시도 및 저장 SHA 재사용 | 307ms |

이는 단일 로컬 환경에서 합성 커밋 하나를 처리한 관찰값이다. 운영 처리량 보장이나 성능 합격 기준이 아니다. 실제 Git/AI 지연, 여러 프로젝트 동시 실행, 대규모 기존 이슈 누적, 프로세스 RSS, 장시간 실행·장애 복구는 측정하지 않았다. 같은 로그의 전체 검증은721건 중716통과·선택 시험5건 건너뜀이며9개 테이블 백업/복원과 identity 시퀀스 삽입 검증도 통과했다. 이후 변경의 검증 상태는 `WORK.md`를 따른다.

### 안전한 선택 실행

Java25와 PostgreSQL17 개발 도구가 있는 Windows에서 **저장소 루트**에서 실행한다. 아래 명령은 외부 Git/AI 선택 시험을 끄고, 기존 격리 검증 스크립트가 관리하는 `.local/pg-validation` 클러스터를 사용한다. 스크립트는 loopback으로만 연결하고 전용 테스트 DB URL을 설정한 뒤 전체 검증·백업/복원을 실행하고 자신이 시작한 클러스터를 종료한다. 동시에 다른 검증을 실행하지 않는다.

```powershell
$loadSmokeFlags = @('RUN_REVIEW_LOAD_SMOKE', 'RUN_GITHUB_SMOKE', 'RUN_GITLAB_SMOKE',
    'RUN_OLLAMA_SMOKE', 'RUN_AI_EVALUATION', 'RUN_GIT_LOAD_SMOKE')
$savedLoadSmokeFlags = @{}
foreach ($name in $loadSmokeFlags) {
    $savedLoadSmokeFlags[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    foreach ($name in $loadSmokeFlags) { [Environment]::SetEnvironmentVariable($name, 'false', 'Process') }
    $env:RUN_REVIEW_LOAD_SMOKE = 'true'
    & .\scripts\test-postgres.ps1 -BackupRestore
} finally {
    foreach ($name in $loadSmokeFlags) {
        [Environment]::SetEnvironmentVariable($name, $savedLoadSmokeFlags[$name], 'Process')
    }
}
```

필요하면 스크립트의 `-PgBin`·`-Port`로 PostgreSQL 개발 도구와 비어 있는 로컬 포트를 지정한다. 이 테스트는 `RUN_REVIEW_LOAD_SMOKE=true`와 `TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:<port>/reviewer_integration` 또는 동등한 `localhost` URL을 모두 만족해야 실행된다. URL 검사만으로 DB의 소유권이 증명되지는 않으므로 운영 DB나 다른 작업의 DB를 이 이름으로 연결하지 않는다. 기본 실행·CI에서는 선택 시험을 건너뛰며, 이를 실행 통과로 해석하지 않는다.

## 실제 PostgreSQL 운영 집계 10,000프로젝트

2026-09-27 KST, Java25·PostgreSQL17 개발 환경에서 `OperationsTelemetryLoadSmokeTest`를 실행했다. 별도 UUID schema에 프로젝트10,000개, 프로젝트당 실행10개로 실행100,000건, 최근 요청10,000건을 생성하고 실제 `OperationsTelemetryService`를 호출했다. Git·AI 호출은 없다.

- 프로젝트4개 상태는 각각2,500개, 요청5개 상태는 각각2,000개로 집계됐다. 활성 요청4,000건 중120분 경계 이상인2,000건만 지연으로 집계됐다.
- 과거 실행90,000건을 실패로 두고, 가장 큰 실행ID의 시각을 과거 실행보다 앞당겼다. 전체 실패92,000건 중 **최신 실행이 실패한 프로젝트2,000개**만 집계되어, 시각 역전이나 과거 실패 누적으로 수치가 부풀지 않았다.
- 14개 고정 지표, 미래 접수 시각·정확120분·1초 안쪽 경계, 전용 schema 제거를 확인했다. 실패한 실행은 과거 성공 보고서를 그대로 남기지 않는다.

| 구간 | 로컬 관찰값 |
|---|---:|
| 합성 데이터 저장 | 2,709ms |
| 세 쿼리와 트랜잭션을 포함한 집계 호출 | 71ms |
| 최신 실패 SQL의 EXPLAIN 실행 시간 | 34.407ms |

측정 전 데이터 삽입·건수 확인·`ANALYZE`를 수행했다. 따라서 이는 캐시가 준비된 단일 관찰이며 cold-cache·운영 처리량·지연 상한을 보장하지 않는다. 실제 SQL 실행 계획에는 `project`와 실패 `review_run`의 순차 읽기, 최신ID 조회의 `review_run_project_history_idx` 사용이 함께 나타났다. 공유 블록 hit49,181/read0, 임시 블록 read/write0이었다. 결과 행 수가 작더라도 DB 내부 읽기 비용은 데이터 누적에 따라 늘어난다. 여러 서버가30초마다 수집하는 비용, 실행 기록 수백만 건, 동시 쓰기·디스크 읽기·장기 보존 정책은 추가 검증 대상이다.

결과는 Git 제외된 `source/target/operations-load-result.json`에 상태·건수·시간과 정제된 계획으로 기록한다. SQL 조건식·schema·연결 정보·비밀번호는 보고서에 넣지 않는다. JDBC query30초/socket45초 제한을 적용하며 소요시간으로 합격 여부를 결정하지 않는다.

저장소 루트의 선택 실행:

```powershell
$operationsSmokeFlags = @('RUN_OPERATIONS_LOAD_SMOKE', 'RUN_REVIEW_LOAD_SMOKE',
    'RUN_GIT_LOAD_SMOKE', 'RUN_GITHUB_SMOKE', 'RUN_GITLAB_SMOKE',
    'RUN_OLLAMA_SMOKE', 'RUN_AI_EVALUATION')
$savedOperationsFlags = @{}
foreach ($name in $operationsSmokeFlags) {
    $savedOperationsFlags[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    foreach ($name in $operationsSmokeFlags) { [Environment]::SetEnvironmentVariable($name, 'false', 'Process') }
    $env:RUN_OPERATIONS_LOAD_SMOKE = 'true'
    & .\scripts\test-postgres.ps1 -BackupRestore
} finally {
    foreach ($name in $operationsSmokeFlags) {
        [Environment]::SetEnvironmentVariable($name, $savedOperationsFlags[$name], 'Process')
    }
}
```

이중 opt-in인 `RUN_OPERATIONS_LOAD_SMOKE=true`와 위와 같은 명시적 포트의 로컬 `reviewer_integration` URL이 모두 필요하다. 기본 실행·CI에서는 건너뛴다. 전체 검증의 최신 결과는 `WORK.md`를 따른다.

## 작성자 계정 조회 비용

2026-10-07 현재PC의 WSL PostgreSQL17에서 전용 schema의 합성 계정30,000개로 작성자 조회 SQL 계획을 비교했다. 저장 시 소문자로 정규화되고 UNIQUE 제약이 있는 `git_username`을 직접 비교하도록 바꾸고, 조회 입력의 앞뒤 공백 제거·소문자 정규화와 승인/활성 조건은 유지한다.

기존 `lower(git_username) = ?`는 세 사례 모두 Seq Scan·shared hit518버퍼였으며, 변경 SQL은 고유 인덱스 Index Scan·2~3버퍼였다. 존재하는 활성 계정/비활성 계정/없는 계정의 결과는 양쪽에서 각각1/0/0행으로 같았다. 단일 실행 관찰 시간은 기존 약2.1~2.3ms, 변경 약0.028~0.031ms다. 캐시·합성 자료·로컬 단일 조회의 관찰값이며 운영 지연이나 처리량 보장은 아니다.

시험 전후 원본14개 테이블 지문을 비교하고 직접 만든 schema만 제거한 뒤 검증 PostgreSQL을 종료했다. 근거는 Git 제외 `.local/session15-assignment-plan.json`이다. 이 시험은 실제 Git 수집·AI 추론·프로젝트 동시 부하를 포함하지 않는다. 입력 정규화와 비활성/승인 대기/반려 계정의 배정 제외는 별도 애플리케이션 테스트에서 확인한다.

## 누적 이슈·감사·운영 목록의 깊은 페이지

2026-10-09 WSL PostgreSQL17에서 `PageLoadPostgresTest`를 수정 전후 각각 선택 실행했다. 새 UUID schema에 이슈100,000건·감사100,000건·프로젝트3,000개·커밋3,000개·실행27,000건·요청3,000건을 만들고 실제 `IssueService`, 메서드 보안이 적용된 `AuditController`, `OperationsService`를 호출했다. Git·AI 호출은 없다. JVM 최대 heap512MiB로 순차 실행했다.

관리자/담당자의 전체·OPEN 이슈, 감사, 운영7필터의 첫·중간·마지막·범위 밖·최대 페이지 등60개 조합에서 반환 ID·순서·중복·다음 페이지·권한·배정 근거를 검증했다. 최신 실행은 시각이 아닌ID로 선택하고, 운영 경계 시각과 실행 없는 프로젝트도 포함한다. 실제 호출의 SQL과 bind를 메모리에서 캡처해 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`으로 관찰한다. 보고서는 고정 사례명·집계·허용된 계획 속성만 저장하며 SQL·schema·계정·주소·예외 원문은 제외한다.

깊은 이슈·감사 페이지에서 OFFSET 이전 행에도 관련 정보 조인이 반복되는 것을 확인했다. 권한·상태 조건과 ID 내림차순으로 페이지 ID를 먼저 고른 뒤, 이슈는 최대26건·감사는 최대51건의 상세 정보를 결합하도록 수정했다. 단일 SQL의 조회 일관성, 기존25/50건 표시와 다음 페이지 확인용1건, ID순서·권한·필터를 유지한다. 새 인덱스나 migration은 없다.

| 관측 사례(페이지는0부터 시작) | 수정 전 SQL 시간 | 수정 후 SQL 시간 | 관련 조인 처리 행 |
|---|---:|---:|---|
| 관리자 전체 이슈 page3999 | 608.168ms | 104.492ms | 100,000 → 25 |
| 담당자 전체 이슈 page1999 | 149.951ms | 57.578ms | 페이지 ID를 먼저 선택 |
| 관리자 감사 page1999 | 69.123ms | 66.140ms | 100,000 → 50 |

두 실행 모두60개 의미 검증과 소유schema 제거·원본14테이블 보존·소유PG 정리를 통과했다. 증거는 Git 제외 `.local/session16-page-baseline.json`과 `.local/session16-page-optimized.json`이다. 수정하지 않은 운영 목록도 같은 사례에서 결과를 확인했다.

이 값은 seed·건수 확인·ANALYZE 후 순차 실행한 단회 관찰이며 캐시 상태는 통제하지 않았다. 실제 계획에는 shared read와 heap fetch가 있어 완전한 warm-cache로 볼 수 없다. 초기 두 보고서의 scope 문자열에 쓰인 warm-cache 표현은 이 한계로 정정하며, 테스트의 후속 보고서는 캐시 비통제를 명시한다. 감사의 시간 차이는 작고 첫 페이지는 추가 ID 조회 비용이 생긴다. 깊은 페이지에서 앞선 ID를 읽는 OFFSET 비용도 남는다. HTTP/JSP 렌더·동시 쓰기·장기 용량·SLA를 측정한 시험이 아니며 시간으로 합격 여부를 결정하지 않는다.

### 선택 실행

기본 전체 시험에서는 건너뛴다. `RUN_PAGE_LOAD_SMOKE=true`와 명시적 loopback `reviewer_integration` URL이 함께 있어야 실행된다. Windows의 기존 격리 PG 도구를 사용할 때 저장소 루트에서 다음과 같이 실행한다. 다른 검증과 동시에 실행하지 않는다.

```powershell
$pageSmokeFlags = @('RUN_PAGE_LOAD_SMOKE', 'RUN_OPERATIONS_LOAD_SMOKE', 'RUN_REVIEW_LOAD_SMOKE',
    'RUN_GIT_LOAD_SMOKE', 'RUN_GITHUB_SMOKE', 'RUN_GITLAB_SMOKE', 'RUN_OLLAMA_SMOKE', 'RUN_AI_EVALUATION')
$savedPageFlags = @{}
foreach ($name in $pageSmokeFlags) { $savedPageFlags[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    foreach ($name in $pageSmokeFlags) { [Environment]::SetEnvironmentVariable($name, 'false', 'Process') }
    $env:RUN_PAGE_LOAD_SMOKE = 'true'
    & .\scripts\test-postgres.ps1
    if ($LASTEXITCODE -ne 0) { throw 'Local page observation failed' }
} finally {
    foreach ($name in $pageSmokeFlags) { [Environment]::SetEnvironmentVariable($name, $savedPageFlags[$name], 'Process') }
}
```

Linux 부모 도구는 상위 환경의 선택 플래그를 전달하지 않는다. 이번 WSL 실행은 부모 `TestRun`의 기동·소유권·정리 경계 안에서 이 플래그만 명시하고 `-Dtest=PageLoadPostgresTest test`를 실행했다. URL 이름만으로 DB 소유권이 증명되지는 않으므로 임의의 기존 DB를 이 이름으로 연결하지 않는다. 결과 `source/target/page-load-result.json`은 실행 시작 시 RUNNING으로 바꾸고, 실패·정리 실패는 FAILED로 남긴다.
