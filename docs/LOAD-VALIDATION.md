# 오프라인 이력 부하 검증

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
