# 저장된 리뷰가 있는 V12→V13 업데이트·백업 복귀 검증

`scripts/verify-review-upgrade.py`는 실제 실행 WAR와 격리 PostgreSQL에서 저장된 리뷰·이슈·수동 확인 근거·처리 사유와 중단된 요청을 포함한 업데이트를 검증한다. V12 백업을 **다른 새 DB**에 복원하여 구 WAR가 조회하고 이어서 처리할 수 있는지도 확인한다. 2026-10-03 전용 WSL에서 아래 범위의 실제 검증을 통과했다.

이 검증은 [WSL 검증 환경](WSL-VALIDATION.md)의 Linux 부모 도구를 사용한다. [WAR 재시작](QUEUE-VALIDATION.md), [DB 중단 후 복구](DB-RECOVERY-VALIDATION.md), [운영 배포·복구](LINUX-DEPLOYMENT.md)와 함께 보되 각 시험의 범위를 구분한다. 외부 Git, 실제 AI 모델, API 키, 운영 데이터와 Docker를 사용하지 않는다.

## 실행 조건

- 일반 Linux 사용자, Java25, PostgreSQL17 도구, Python3.11 이상과 Linux 파일시스템의 별도 checkout을 사용한다. Python은 SHA-256 계산에 `hashlib.file_digest`를 사용한다.
- 부모 `scripts/test-postgres-linux.py`가 checkout의 `.local/pg-validation`을 시작하고 실제 Maven 검증을 마친 뒤 하위 도구를 호출한다. 운영 DB나 별도 서비스 DB를 대상으로 지정하지 않는다.
- 구 WAR의 신뢰할 수 있는 해시와 절대경로를 명시한다. 현재 checkout의 `source/target`은 clean 빌드로 지워지므로 구 WAR는 그 밖에 보관한다. 하위 도구는 구 WAR의 V1~V12, 현재 WAR의 V1~V13 migration 파일 목록과 양쪽 WAR의 기대 해시를 검사한다. 임의 버전 조합에 대한 범용 업그레이드 도구가 아니다.
- 부모가 postmaster PID·시작 시각·실행 토큰을 전달한다. 하위 도구는 경로·Linux 전용 마커·연결 서버의 신원을 대조한다. 이 값들을 추측하거나 소유권 확인을 생략해 직접 실행하지 않는다.
- PostgreSQL과 WAR의 loopback 포트는 다른 프로세스가 사용하지 않아야 한다. 기존 클러스터·프로세스를 중지하거나 PID 파일을 삭제해서 빈 포트를 만들지 않는다.

이번 구버전 기준은 `ec05f9b1c43479555377a97bc7217c159e5fddaa`다. 별도 Linux checkout에서 `-DskipTests package`로 만든 **리허설용 WAR**이며, 이 빌드를 구버전 전체 테스트 통과로 세지 않는다.

| 구버전 자료 | 값 |
|---|---|
| Linux WAR | `/home/reviewer/work/ai-reviewer-v12/source/target/ai-code-reviewer.war` |
| SHA-256 | `783971202a6f1ccc9d45580f1308f2a2ad8ac040dd8ddea4bb0d70f8949da57c` |
| migration 경계 | V1~V12 |

경로와 해시는 해당 WSL 실행 자료다. 다른 환경에서 파일을 재생성했다면 별도로 확인한 빌드 기록을 사용한다. WAR의 소스 출처를 해시 형식 검사만으로 증명할 수는 없다.

## 부모 도구로 실행

Linux checkout 루트에서 실행한다. Java 경로는 설치된 Java25의 실제 경로로 바꾼다.

```bash
python3 scripts/test-postgres-linux.py \
  --pg-bin /usr/lib/postgresql/17/bin \
  --java /usr/lib/jvm/temurin-25-jdk-amd64/bin/java \
  --port 55439 \
  --review-upgrade \
  --previous-war /home/reviewer/work/ai-reviewer-v12/source/target/ai-code-reviewer.war \
  --expected-previous-war-sha256 783971202a6f1ccc9d45580f1308f2a2ad8ac040dd8ddea4bb0d70f8949da57c
```

부모는 이번 실행에서 만든 현재 WAR의 해시와 구 WAR의 명시한 해시를 하위 도구에 전달한다. 하위 도구의 직접 CLI에는 `--war`, `--previous-war`, `--expected-war-sha256`, `--expected-previous-war-sha256`, `--java`, `--pg-bin`, `--port`, `--report`, 부모 소유권 정보가 모두 필요하다. `--psql`을 생략하면 `--pg-bin`의 `psql`을 사용한다. 환경파일을 셸에 로드하거나 운영 자격증명을 전달하지 않는다.

하위 시나리오의 기본 시간 예산은300초, 허용 범위는120~900초다. WAR 시작과 SQL·외부 프로세스는 각각 제한 시간을 사용하며, 실패·취소 후 소유권을 확인하는 정리 시간은 추가될 수 있다. 사용자 인터페이스의 완료 예상 시간이나 운영 복구 시간 보장은 아니다.

## 데이터와 실제 처리의 구분

업그레이드 시험의 업무 데이터 쓰기는 이번 호출이 생성한 두 UUID DB에만 허용한다. 기존 `reviewer_integration`의10개 테이블은 읽기 전용으로 지문을 구하고, 시험 종료와 정리 뒤에도 같음을 확인한다. 두 DB 안에서는 동일한 고유 `restart_test_<UUID>` schema 이름을 사용하므로 백업을 다른 schema 이름으로 바꾸지 않는다.

| 데이터 | 생성 방법과 검증 목적 |
|---|---|
| 프로젝트·수동 요청 | 구 WAR의 실제 로그인·CSRF·프로젝트 등록/승인·리뷰 요청 HTTP 흐름 |
| A 커밋과 AI 이슈 | loopback GitLab/Ollama fixture를 구 WAR가 호출하고 실제 리뷰 처리로 저장 |
| 처리 중 B 커밋 | 응답 대기 지점에서 구 WAR를 강제 종료하여 저장되지 않은 변경과 `RUNNING` 요청을 유지 |
| 수동 확인 증거·해결 사유·과거 완료 실행 | 정합성을 갖춘 SQL 합성 자료로 삽입; 수동 이슈 생성 경로의 실제 Git 처리 검증으로 세지 않음 |
| 과거 해결된 AI 이슈·감사 기록 | 별도 SQL 합성 자료로 삽입하여 기존 행 보존과 화면 조회 확인 |
| A 이슈의 검토 제외 상태 | 실제 생성된 A 이슈의 상태를 SQL로 설정; 사용자 변경 HTTP 실행으로 세지 않음 |

수동 확인 사유에는 HTML 특수문자를 넣는다. 이후 WAR의 이슈 상세 화면에서 이 값이 escaped 형태로 표시되고 원래 문구·근거가 보존되는지 확인한다. 보고서에는 계정 비밀번호, HTTP 본문, DB 원본 행과 claim 토큰을 출력하지 않는다.

## 검증 순서

1. **V12에서 실제 리뷰를 중단한다.** worker를 끈 구 WAR에서 프로젝트와 요청을 만들고 `QUEUED`를 확인한다. worker를 켜서 A의 원자적 저장과 B의 응답 대기를 확인한 뒤 해당 WAR 프로세스만 강제 종료한다. 요청은 동일 ID·행위자·첫 처리 시도와 `RUNNING` 상태를 가진다.
2. **저장 자료가 있는 V12를 백업한다.** SQL 합성 자료를 추가한 뒤 저장 행과 migration 기록을 읽는다. WAR가 멈춘 상태에서 전용 schema의 custom-format dump를 만들고0600으로 보관한다. 백업 전후 원본 시험 행이 변하지 않았는지 확인한다.
3. **V13 migration만 먼저 확인한다.** 같은 새 시험 DB에 현재 WAR를 worker OFF로 시작한다. 기존9개 업무 테이블의 행과 V1~V12 checksum이 같아야 한다. `review_run`은 기존7개 열(`id`, `project_id`, `status`, `started_at`, `finished_at`, `reviewed_commits`, `error_message`)을 명시해 비교한다. 새 진행 정보3개 열은 과거 실행에서 모두 NULL이어야 한다. 이 단계에서 중단된 요청을 처리한 것으로 표시하면 실패다.
4. **V13에서 요청을 이어서 처리한다.** worker ON으로 재시작하여 같은 요청 ID·행위자를 유지하면서 claim과 실행 ID가 교체되고 시도 횟수가1 증가하는지 확인한다. 새 실행은 새 저장0건에서 시작하며 A의 diff 재조회·AI 호출을 반복하지 않고 B만 재시도한다. 고정 이력·tree 확인은 계속 수행한다. 정확한 이슈 수·배정·체크포인트와 과거 수동 확인 사유·증거·감사 기록 보존을 대조한다.
5. **백업을 또 다른 새 DB에 복원한다.** 업그레이드된 DB에 구 WAR를 연결하지 않는다. V12 dump를 새 UUID DB에 복원하고10개 테이블의 기존 행과 정확한 V1~V12 migration 기록을 비교한다. 구 WAR를 worker OFF로 시작해 기존 이슈 화면과 저장된 중단 요청을 확인한다.
6. **구 WAR에서도 백업 지점부터 복구한다.** 복원 DB의 worker를 켜서 동일 요청의 복구, A 저장 결과 재사용과 B 처리, 과거 결정 보존을 확인한다. 이 과정에서 앞서 V13으로 올린 시험 DB가 바뀌지 않아야 한다.
7. **소유 자원만 정리한다.** WAR와 fixture를 종료한 뒤 클러스터 및 DB의 OID·소유자·실행 표식을 재검사한다. 연결이 남으면 강제로 끊지 않고 삭제를 보류한다. 두 시험 DB 제거와 기존 테스트 DB 보존이 확인되어야 PASS가 된다.

세 처리 경로를 모두 실행했을 때 fixture의 A AI 호출은 합계1회, B는 구버전에서 중단한 호출·V13 복구·구버전 복원 복구를 합쳐3회다. 이 합성 요청 횟수 검사는 외부 AI 과금이나 분산 시스템의 exactly-once 보장이 아니다.

## 실패·증거·한계

상위 보고서는 부모 실행 토큰, 최종 결과, 양쪽 WAR 해시와 `ownedDatabasesRemoved`를 포함한다. 부모가 요구하는 보고서가 없거나 일치하지 않으면 통과로 처리하지 않는다. 세부 보고서는 검사 단계와 안전한 고정 진단을 기록한다. 원래 실패 뒤 정리도 실패하면 원래 실패를 보존하고 정리 미확인 상태를 함께 남긴다. SIGINT/SIGTERM도 소유 자원 정리 경로로 들어가지만 강제 종료·전원 종료 등 모든 중단 시점을 자동 복구한다는 뜻은 아니다.

dump와 WAR 로그는 Linux checkout의 `.local/review-upgrade-<UUID>` 아래에 남는다. 현재 dump는 합성 자료지만 비밀번호 해시와 처리 사유를 포함하므로 운영 백업과 같은 접근 제한이 필요하다. 정리에 실패한 소유 DB·작업 디렉터리는 신원을 확인하기 전에 삭제하지 않는다. 기존 보고서 경로도 덮어쓰지 않는다.

이 도구는 직접 시작한 WAR와 loopback HTTP로 migration·행 보존·요청 재개를 검증한다. systemd/Nginx/TLS·최소권한 배포 검증은 [별도 WSL 결과](WSL-VALIDATION.md)를 따른다. 새 테스트 DB의 계정은 격리 클러스터용 계정이며 운영 DB 최소권한 검증을 대신하지 않는다.

백업 복귀는 **업데이트 전 상태를 새 DB에 복원하는 절차**다. V13에서 새로 발생한 업무 데이터를 V12로 변환하거나 병합하지 않는다. 운영 규모·모든 과거 버전·다중 인스턴스 장시간 부하, 실제 GitLab/LiteLLM 인증·모델 품질, 백업 암호화·보존·RPO/RTO는 [릴리스 기준](RELEASE-CHECKLIST.md)에 남아 있다.

## 실행 기록

2026-10-03 KST, 전용 Ubuntu24.04.5·Temurin25.0.4.1·PG17.11·Python3.12.3에서 부모 `--backup-restore --review-upgrade`를 실행했다. Java 소스 기준은 `d67ea98`이며 부모·하위 검증 도구는 이번 회차 작업본이었다. Java907건 중897통과/선택10skip(외부7·부하3), 필수PG4suite gate와 별도10테이블 백업/복원 검증을 통과했다. 하위 업그레이드 시나리오는 WAR6회 시작,65.283초에 완료했다.

| 항목 | 상태 |
|---|---|
| 신규 도구 단위·안전 회귀 | Windows/Linux15건씩 PASS: 해시·migration 경계·대상 소유권·실패/취소/정리·보고서 보호 |
| 실제 V12→V13 migration 및 저장 리뷰 복구 | PASS: 기존 행·checksum·과거 진행NULL, 동일 요청/행위자·새 claim/실행·B만 재시도 |
| 새 DB V12 백업 복원·구 WAR 복구 | PASS:10테이블 기존 행 일치, 구 WAR 조회·재개, 전체 A AI1회/B AI3회 |
| 원본 DB 보존·시험 자원 정리 | PASS: 기존 DB 지문 일치, 소유 WAR·fixture 종료·새 DB2개 제거 |
| 현재 WAR SHA-256 | `733dd6f8e97d48badaf55acad528126ee5b72acf202a52a637adfe45bc008965` |
| 부모 보고서 디렉터리 | Linux `.local/linux-postgres-0ee156e0494044ddb535ce5b8ae129f1` |
| 하위 로그·dump 디렉터리 | Linux `.local/review-upgrade-dcc8dd76006d491896efa51959071b3b` |

위 시간은 로컬 관찰값이며 운영 성능 보장은 아니다. 이전 회차의 리뷰 실행0행·worker OFF 리허설과 구분한다. 이 도구에서 복원 후 실제 처리로 새 실행·커밋·AI 이슈·감사 기록의 삽입을 확인했지만, 모든 identity 테이블의 복원 후 신규 삽입을 검사한 것은 아니다. 별도 실제 PG migration 회귀에서는 누적 데이터의8개 identity와 요청 삽입, 수동 사유 변경 중 감사 실패의 원자적 rollback을 검증했다.

추가로 일회성 Linux harness에서 복원 DB에 연결하는 다섯 번째 WAR 시작 중 하위 도구에 실제 SIGTERM을 보냈다. 하위 결과는 FAIL/KeyboardInterrupt·종료130을 유지했고 양쪽 새 DB 제거, 원본 DB 보존, WAR·fixture 종료를 확인했다. 부모 클러스터와 실행 lock도 정리했다. 이 실행은55.803초였으며 증거는 Linux `.local/linux-postgres-77c09986cc1c417da67f720008921092`다. 신호 취소를 성공 시나리오 PASS로 바꾸지 않았다.

도구 전체 Python 검증은 Windows193건 중188통과/POSIX5skip, Linux193건 중183통과/PowerShell10skip이었다. 부모 연결40건과 후보 패키지26건이 포함된다. 독립 검토에서 발견한 대문자 WAR SHA 입력의 부모/하위 허용 불일치를 정규화로 수정한 뒤 회귀에 포함했다.
