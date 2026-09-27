# 공개 Git 공급자 검증

이 검증은 공식 공개 저장소를 실제 GitHub/GitLab REST API로 읽어 수집 계약을 확인한다. 기본 자동 테스트와 CI에서는 실행하지 않는다. 외부 서비스의 가용성·요청 제한에 영향을 받으며, 실패를 skip이나 성공으로 바꾸지 않는다.

## 범위와 고정 자료

`PublicGitHubSmokeTest`와 `PublicGitLabSmokeTest`는 Spring 설정을 로드하지 않고 Git 클라이언트를 직접 생성한다. 토큰은 빈 문자열이고 추가 자격증명 목록도 비어 있다. 목적지는 공식 HTTPS API로 고정한다. 환경의 Git/AI 토큰을 읽지 않으며 AI·DB·Git clone·원본 blob 다운로드·원격 변경을 수행하지 않는다. 공개 커밋 메타데이터에는 작성자 정보가 포함될 수 있지만 테스트가 이를 별도 출력하거나 보고서에 기록하지 않는다.

| 사례 | 고정 자료와 단언 | 예상 GET 수 |
|---|---|---:|
| 기존 GitHub 전체 이력 | `octocat/Hello-World`의 기본 브랜치 이력과 완료·재개. 이 기존 사례의 HEAD는 고정하지 않았다. | 저장소 이력에 따라 변동 |
| 기존 GitLab 루트 | `gitlab-org/gitlab-test`의 `ddd0f15ae83993f5cb66a927a28673882e99100b` 이력에서 루트 `1a0b36b3cdad1d2ee32457c102a8c0b7056fa863` 본문만 선택하고 완료·재개 | 9 |
| 새 GitHub 바이너리 루트·진행 | `github/media`의 `af5fe61b38fb7c2343360d074a714f226d4cc029` → `86a78d8d370a490aa8b5b87d31c626884114103b`: 각각 `octocat.png`, `octocat_gems.png` 생성. `MANUAL_ONLY`, 정확한 blob/mode/사유, 명시 checkpoint, 빈 재개 | 14 |
| 새 GitLab 바이너리·본문 진행 | 부모 cursor `33f3729a45c02fc67d00adb1b8bca394b0e761d9` 다음 `2f63565e7aac07bcdadb654e253078b727143ec4` 이미지 수정의 수동 증거 → `874797c3a73b60d2187ed6e2fcabd289ff75171e`의 Ruby 두 파일 `FULL`, checkpoint, 빈 재개 | 18 |
| 새 GitLab 빈 파일 메타데이터 | 부모 cursor `c7fbe50c7c7419d9701eebe64b1fdacc3df5b9dd` 다음 `9a944d90955aaf45f6d0c88f30e27f8d2c41cec0`의 `files/empty`: canonical 빈 blob/100644 생성, `METADATA_ONLY` 후 재증명하여 `METADATA_CHANGE` 수동 증거 | 11 |

새 사례3개는 현재 고정 자료의 단일 페이지 응답 기준 약43 GET이다. 코드 경로에서 계산한 예상치이며 실제 요청 계측값이나 서비스 SLA가 아니다. 새 사례는 요청당20초, 각 Git 작업90초, 이력·GitLab tree/diff 최대2페이지, 응답2MiB와 diff256KiB로 제한하며 각 테스트도180초 timeout을 둔다. GitHub recursive tree의 `truncated`는 계속 실패 처리한다. 기존 두 smoke의 예산과 검증은 유지한다.

`github/media`는 GitHub 소유의 보관 저장소이며 처음 두 커밋만 고정하여 사용한다. GitLab 사례는 GitLab 자체 테스트 저장소의 작은 불변 구간이다. 이미지 원본을 내려받아 binary 형식이나 해시를 직접 계산하는 검증이 아니라, API의 변경 목록·고정 tree·blob 식별자·모드와 본문 제공 여부가 현재 수집 계약에 맞는지 확인한다. GitHub는 해당 파일의 patch를 제공하지 않고 GitLab은 binary marker를 제공한다. GitLab 미제공 본문의 행수는 검증하지 못한다는 안내도 검사한다.

자료: [GitHub 공식 보관 저장소](https://github.com/github/media), [GitHub 루트 커밋](https://github.com/github/media/commit/af5fe61b38fb7c2343360d074a714f226d4cc029), [GitLab 이미지 수정](https://gitlab.com/gitlab-org/gitlab-test/-/commit/2f63565e7aac07bcdadb654e253078b727143ec4), [GitLab 빈 파일 생성](https://gitlab.com/gitlab-org/gitlab-test/-/commit/9a944d90955aaf45f6d0c88f30e27f8d2c41cec0).

## 실행

Java25와 Maven Wrapper가 준비된 상태에서 저장소 루트의 새 터미널로 실행한다. 두 클래스만 선택하므로 모델 평가나 DB 테스트를 함께 실행하지 않는다. 공개 API 요청 제한에 걸리면 해당 실행은 실패하며, 토큰을 추가하거나 검증을 약화하지 말고 응답 제한이 해제된 뒤 명시적으로 재실행한다.

PowerShell:

```powershell
Push-Location source
$env:RUN_GITHUB_SMOKE = 'true'
$env:RUN_GITLAB_SMOKE = 'true'
try {
    .\mvnw.cmd '-Dtest=PublicGitHubSmokeTest,PublicGitLabSmokeTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Public Git smoke failed; inspect the test report.' }
} finally {
    Remove-Item Env:RUN_GITHUB_SMOKE -ErrorAction SilentlyContinue
    Remove-Item Env:RUN_GITLAB_SMOKE -ErrorAction SilentlyContinue
    Pop-Location
}
```

Bash:

```bash
cd source
RUN_GITHUB_SMOKE=true RUN_GITLAB_SMOKE=true \
  ./mvnw '-Dtest=PublicGitHubSmokeTest,PublicGitLabSmokeTest' test
```

2026-09-27 Windows/Java25에서 실제 GitHub2건(4.067초)+GitLab3건(13.00초)이 모두 통과했다. 새3사례를 포함한 총5건이며 AI·DB·토큰은 사용하지 않았다. 두 opt-in 변수가 없으면 이5건은 skip된다. 실제 결과는 `source/target/surefire-reports/TEST-com.aicreviewer.PublicGitHubSmokeTest.xml`과 `TEST-com.aicreviewer.PublicGitLabSmokeTest.xml`, 해당 회차 로그 `.local/session6-public-git.log`에 기록했다. 실행 시간은 당시 관찰값이며 서비스 SLA가 아니다.

## 해석의 한계

- 입력한 부모 cursor는 이 작은 구간 검증의 시작 조건이다. 테스트가 그 이전의 모든 커밋을 리뷰·저장했다고 주장하지 않는다.
- 반환된 SHA를 다음 `batch`의 reviewed 집합에 전달해 선택·checkpoint 진행을 검사한다. DB 저장 원자성, 실제 수동 이슈 생성·배정·중복 방지와 작업자 재시작은 별도 PostgreSQL/프로세스 검증 범위다.
- `FULL`은 해당 두 파일의 지원 본문 diff가 반환됐다는 뜻이다. 이 테스트는 AI를 호출하지 않으므로 모델 검토 품질이나 실제 결함 탐지를 증명하지 않는다.
- 신규 사례는 GitHub 메타데이터-only, 모든 binary/rename/mode/submodule 조합, 큰 저장소·대형 diff, 비공개 토큰, 설치형 GitLab 버전별 호환성을 모두 검증하지 않는다. 해당 오프라인 회귀와 남은 릴리스 검증을 대체하지 않는다.
