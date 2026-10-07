# 두 서버의 리뷰 처리 검증

실제 WAR 두 개가 같은 PostgreSQL의 합성 프로젝트 세 개를 처리하는 검증이다. Windows 로컬의 Java25·PostgreSQL17을 사용한다. 각 서버의 처리 동시성은1, 새 예약은 비활성화하며, GitLab/Ollama는 loopback fixture다. 실제 저장소·모델·유료 API를 호출하지 않는다.

## 확인하는 동작

1. 서버 A에서 로그인·CSRF·프로젝트 신청·관리자 승인을 거쳐 세 프로젝트를 준비하고 느린 프로젝트만 수동 접수한다. AI 응답을 보류하여 A의 실행 슬롯과 프로젝트 advisory lock을 유지한다. 아직 커밋·이슈·진행 기준이 저장되지 않았음을 검사한다.
2. 같은 DB schema를 사용하는 서버 B를 시작한다. 소유한 합성 요청의 `available_at`만 과거로 바꿔 즉시 잠금 경쟁을 유도한다. B가 다음 확인을 미뤄도 요청ID·claim token·실행ID·시도1회·RUNNING 상태는 바뀌지 않아야 한다.
3. B에 나머지 프로젝트 두 개를 접수한다. 느린 응답을 계속 보류한 동안 두 프로젝트가 모두 성공하고 각 커밋·이슈·진행 기준이 저장되는지 확인한다.
4. 느린 응답을 해제한다. 세 프로젝트 모두 요청자 보존·실행1개·시도1회·커밋1개·이슈1개·올바른 담당자·진행 기준을 확인한다. 각 프로젝트의 Git 상세/diff·AI 호출은 각각1회여야 하며, 두 서버의 JSP 이슈함에서 세 이슈가 보여야 한다.

단순히 서버 두 개를 띄우거나 최종 성공 상태만 확인하는 검사가 아니다. 느린 작업이 미완료인 중간 상태에서 잠금 소유권과 다른 프로젝트의 완료를 함께 검사한다. 과거 시각을 주입하는 동작은 해당 도구가 만든 schema의 단일 RUNNING 요청에만 수행한다.

## 재현과 정리

저장소 루트의 Windows 명령은 빌드·실제 DB 테스트 후 같은 검증 클러스터에서 두 WAR를 실행한다. 다른 검증과 동시에 실행하지 않는다.

```powershell
.\scripts\test-postgres.ps1 -ReviewConcurrency
# 필요한 경우 비어 있는 포트를 지정한다.
.\scripts\test-postgres.ps1 -ReviewConcurrency -ReviewConcurrencyPortA 18090 -ReviewConcurrencyPortB 18091
```

이미 준비한 로컬 검증 DB에서는 다음 명령을 사용한다. `TEST_DATABASE_URL`은 명시적 포트의 `127.0.0.1`/`localhost`와 DB 이름 `reviewer_integration`만 허용하고 URL 파라미터는 금지한다. 전용 계정은 `TEST_DATABASE_USERNAME`/`TEST_DATABASE_PASSWORD`로 전달한다. 이 이름의 운영 DB에 연결하지 않는다.

```bash
python3 scripts/verify-review-concurrency.py \
  --war /absolute/path/ai-code-reviewer.war \
  --java /absolute/path/java --psql /absolute/path/psql \
  --port-a 18090 --port-b 18091
```

두 WAR는 공유 합성 관리자·독립 포트·작업 디렉터리·로그와 임의 UUID schema를 사용한다. 기존 재시작 도구의 URL 검증·자식 환경 allowlist·같은 origin/context 로그인·프로세스 소유권 규칙을 재사용한다. 실제 키·토큰·프록시·Java/Spring override는 상속하지 않는다. `psql` 실행 경로의 심볼릭 링크는 POSIX wrapper 호환을 위해 유지한다.

전체 예산 기본240초, 서버 시작 예산 기본45초이며 각 대기 단계에도 상한이 있다. 성공/실패 모두 소유 WAR를 각각 중지하고 schema·임시 작업 디렉터리를 제거한다. 하나라도 종료되지 않으면 해당 데이터와 작업 디렉터리를 보존하고 실패로 보고한다. 로그 디렉터리와 Git 제외된 `.local/review-concurrency-result.json`은 남겨 진단할 수 있게 한다. 보고서는 SQL·HTTP 원문·비밀번호·claim token을 포함하지 않는다.

## 보장 범위

2026-09-27 KST 실제 로컬 실행은22.406초에 통과했다. 두 WAR 시작, 세 프로젝트의 각 상세/diff/AI1회, 각 이력2회·tree1회, 두 서버의 이슈 화면을 확인했다. 두 소유 프로세스 종료·schema 제거·작업 디렉터리 제거가 모두 성공했으며 검증 클러스터도 종료했다. Python 안전회귀11개를 포함한43개 중42개가 통과하고 Windows에서 POSIX wrapper1개는 건너뛰었다. CI에 같은 두 서버 검증을 연결했지만 원격 실행은 아직 하지 않았다.

이 검사는 정상 DB와 세 합성 프로젝트의 제한된 경쟁을 다룬다. 임의 규모의 공정성, 장시간 지연 상한, 실제 API 할당량, 운영 용량, 장애 중 외부 AI의 정확히 한 번 호출이나 중복 과금 방지를 보장하지 않는다. 프로세스 중단 후 복구는 별도의 [재시작 검증](QUEUE-VALIDATION.md)을 따른다. Linux systemd·TLS와 실제 원격 CI 실행도 별도다.

## 실제 예약 반복과 누락 일정 따라잡기

`scripts/verify-review-schedule.py`는 위의 수동 접수 검증과 별도로 두 실제 WAR의 예약·처리를 모두 켜는 검증 도구다. 같은 전용 UUID schema와 loopback GitLab/Ollama fixture를 사용하고 각 서버의 처리 동시성은1로 제한한다. 검증용 cron은 `0 * * * * *`이며 운영 기본 예약을 변경하지 않는다. SQL로 `next_review_at`이나 요청 시각을 앞당기지 않는다.

기본 `--cycles 5`는 서버 중지 전에 프로젝트마다 성공해야 하는 최소 예약 실행 횟수다. 서버 시작 직후 기한이 지난 일정을 조정하며 접수한 요청도 포함하므로, 정확한 분 경계에서만 다섯 번 실행했다는 뜻은 아니다. 검사는 다음 조건을 함께 확인한다.

1. 예약·처리를 끈 상태에서 합성 프로젝트 세 개를 신청·승인한다. A의 예약·처리를 켜고 첫 느린 프로젝트의 AI 응답을 보류한 뒤 B도 켠다. 실제 다음 예약 시각이 지나 `next_review_at`이 전진해도 느린 요청ID·예약 출처·요청자 값·claim token·실행ID·시도 횟수는 유지되어야 한다. 느린 작업을 보류한 동안 나머지 두 프로젝트는 완료되어야 한다.
2. 응답을 해제하고 세 프로젝트가 각각 최소 지정 횟수만큼 성공할 때까지 관측한다. 각 프로젝트의 첫 실행은 커밋1개를 저장하고 이후 정적 이력의 실행은 새 저장0개여야 한다. 전체 커밋·이슈는 각각3개, 프로젝트별 Git 상세/diff·AI 호출은 각각1회여야 한다.
3. 모두 성공하고 처리 중인 요청이 없는 시점에 두 WAR를 중지한다. 단조 시계 기준 최소125초 동안 중지 상태를 유지하며, DB 현재 시각도 중지 전 다음 예약 시각보다 최소60초 뒤인지 검사하여 두 번의 실제 예약 시각이 지났음을 확인한다. 중지 중 요청·실행·다음 예약 시각은 바뀌지 않아야 한다.
4. 실제 UTC 분 경계 근처에 두 WAR를 재시작하고 같은 분 안에서 프로젝트마다 새 요청·실행이 정확히1개만 성공하는지 확인한다. 누락된 예약마다 실행을 반복하지 않아야 하며 두 WAR를 다시 중지한 뒤에도 최종 건수를 확인한다. 시작이 다음 분으로 넘어가거나 관측을 놓치면 따라잡기 증거를 확정하지 않고 실패한다.

예약 접수 감사 기록의 요청ID 중복, 프로젝트별 같은 UTC 분의 중복 접수, 요청별 실행1개도 검사한다. 과거 실행에 요청 외래키가 없으므로 감사 기록 사이의 시간 구간과 실행 중 관측한 현재 요청·실행 연결을 대조한다. 이 검증을 과거 모든 요청·실행에 대한 DB 외래키 보장으로 해석하지 않는다.

### Linux 재현 명령

Linux native 파일시스템의 소스 checkout에서 일반 사용자로 실행한다. Java25·PostgreSQL17의 실제 설치 경로를 명시하며, [WSL/Linux 검증 환경 조건](WSL-VALIDATION.md)을 먼저 따른다. 부모 도구는 전용 검증 클러스터 준비, 전체 빌드·필수 PostgreSQL 테스트, 예약 검증, 소유 클러스터 종료를 차례로 수행한다. 다른 검증과 동시에 실행하지 않는다.

```bash
python3 scripts/test-postgres-linux.py \
  --pg-bin /absolute/path/to/postgresql-17/bin \
  --java /absolute/path/to/java-25/bin/java \
  --review-schedule
```

WAR 포트 기본값은 A18099/B18100이다. 필요한 경우 부모의 `--review-schedule-port-a`와 `--review-schedule-port-b`로 비어 있는 서로 다른 포트를 지정한다. 부모는 기본5회·자식 전체 예산780초로 실행하며, 예약 자식 명령에는 지정 예산에90초의 정리 여유를 더한다. 이는 검증 대기 예산이며 실행 성능이나 서비스 응답 보장 시간이 아니다.

현재PC에서 유한 반복 횟수와 자원 사용을 함께 확인하려면 Linux 부모에 아래 옵션을 추가한다. 실제 모델 호출이나 다운로드는 없으며 WAR당 `-Xmx384m`과 서버당 작업 동시성1을 유지한다. heap 설정은 프로세스 전체 메모리 상한이 아니다.

```bash
python3 scripts/test-postgres-linux.py \
  --pg-bin /absolute/path/to/postgresql-17/bin \
  --java /absolute/path/to/java-25/bin/java \
  --review-schedule --review-schedule-cycles 8 \
  --review-schedule-timeout-seconds 900 --review-schedule-observe-resources
```

관측은 Linux에서만 선택적으로 켜며 기본값은 OFF다. 10초 간격과 시작/종료 경계에서 자신이 시작한 두 WAR의 PID·시작시각을 확인해 실행 세대별 RSS/HWM·thread 수·CPU 누적값을 수집한다. DB는 고유 application name의 두 앱 연결 상태와 전용 schema의 relation 크기만 읽는다. 최대160회이며 누락된 관측이나 잘못된 값은0으로 채우지 않고 실패한다. 보고서에는 표본 수·첫/끝 시각·실측 peak와 중단 전/복구 후 프로젝트별 실제 성공 횟수를 남긴다. PID·application name·SQL·계정·환경변수 원문은 자원 집계에 넣지 않는다. 표본 사이의 순간값, heap 실제 사용량, 메모리 누수 없음이나 장기 운영 용량을 증명하는 검사는 아니다.

이미 실행 중인 전용 로컬 검증 DB와 검증용 WAR가 있으면 자식 도구만 실행할 수도 있다. 위와 동일한 `TEST_DATABASE_URL`·전용 계정 조건을 적용하며 자식은 PostgreSQL을 시작하거나 종료하지 않는다. `--report`는 해당 checkout의 `.local` 아래에 아직 존재하지 않는 절대 경로를 지정한다.

```bash
python3 scripts/verify-review-schedule.py \
  --war /absolute/path/to/checkout/source/target/ai-code-reviewer.war \
  --java /absolute/path/to/java-25/bin/java \
  --psql /absolute/path/to/postgresql-17/bin/psql \
  --port-a 18099 --port-b 18100 \
  --cycles 5 --timeout-seconds 780 \
  --report /absolute/path/to/checkout/.local/review-schedule-result.json
```

자식의 `--cycles`는3~10, 전체 예산은 `cycles × 60 + 240`초 이상1200초 이하, 서버별 시작 예산은 기본45초다. 횟수를 늘릴 때 전체 예산도 함께 맞춘다. 설정한 전체 예산 외에 소유 자원의 종료·정리를 시도하는 시간이 필요할 수 있다.

### 정리와 증거의 한계

보고서는 요청·실행·호출 건수와 시간, 고정 검사 결과를 기록하며 SQL/HTTP 응답 원문·자격증명·claim token은 기록하지 않는다. 시작 전후 기존 `public`의14개 테스트 테이블 지문을 읽기 전용으로 비교한다. 자식이 만든 schema의 OID·소유자·표식을 다시 확인한 뒤에만 제거하고, 직접 시작한 WAR만 종료한다. 소유권 확인이나 프로세스 종료에 실패하면 관련 자원을 보존하고 실패로 남긴다. 로그와 결과 보고서는 진단용으로 남긴다.

성공은 `cleanupFailed=false`와 `ownedWarsStopped`, `ownedSchemaRemoved`, `ownedWorkRemoved`, `originalTestTablesPreserved` 네 검사의 확인을 모두 요구한다. `SIGTERM`이나 사용자 중단은 정리를 시도하고 성공으로 보고하지 않는다. 강제 종료·전원 중단 뒤의 자동 정리는 보장하지 않는다.

이 도구는 제한된 합성 데이터로 실제 예약 반복, 느린 요청의 예약 병합, 다른 프로젝트 진행, 결과 재사용과 두 서버 중지 후 따라잡기를 검증한다. 실제 운영 서버의 장기간 가용성·부하·공정성·외부 API 중복 과금 방지나 임의 장애에서의 정확히 한 번 처리를 증명하지 않는다.

### 실제 WSL 실행 결과

2026-10-07 KST 전체 Java1070건 중1060통과/선택10skip·필수PG6suite 이후 예약 검증을479.629초에 통과했다. A3회/B2회 기동, 느린 AI25.639초 보류 중 다음 예약 병합과 나머지 프로젝트 완료를 확인했다. 중지 전 성공 횟수는 느린 프로젝트5회, 나머지 각각6회였다. 실제167.727초 동안 두 서버를 중지한 뒤 재시작하여 프로젝트마다 정확히1회만 추가 성공했다. 전체 요청/실행은6·7·7개이고 저장 커밋/이슈는 각각3개였다. 프로젝트별 상세/diff/AI는 각각1회, 이력 조회는12·14·14회였다.

SQL 시간 변경 없이 요청 출처·행위자·claim 보존과 중복 접수/저장 부재, 원본14테이블 및 소유 WAR/schema/work·부모PG 정리를 확인했다. 보고서는 Linux `.local/linux-postgres-61c70dc2d2a54a979466f84e12252532/review-schedule-61c70dc2d2a54a979466f84e12252532.json`에 있다. WAR SHA256은 `42f2490ee84112f9063cf60f050215eaeb33da81498b62470aaa64ab0d45b94e`다. Python274건은 Windows269통과/5skip, Linux264통과/10skip이며 신규 도구12건·부모55건을 포함한다. 운영 기본1시간 간격을 장기간 반복한 시험은 아니다.

같은 WAR의 별도 실제SIGTERM 검증도31.997초에 통과했다. A2/B1회 기동, 느린 예약1개 RUNNING·다른2개 SUCCEEDED 시점에서 취소하여 자식이 FAIL/KeyboardInterrupt/종료130을 유지하고 소유 자원·부모PG/lock을 정리하는지 확인했다. 원본14테이블은 보존됐다. Linux `.local/linux-postgres-00657a6a20fa41fa81e5dd57fb6243c6/cancel-harness.json`에 증거를 보존한다.

### 현재PC의8회 반복·자원 관측

2026-10-07 KST i5-12500H·Windows RAM15.67GiB, WSL 메모리 약7.59GiB에서 실제 모델 없이 같은 합성3프로젝트를 검증했다. 전체 Java1186건 중1176통과/선택10skip·필수PG9suite 뒤 `--cycles 8 --timeout-seconds 900 --observe-resources`가662.446초에 통과했다. WAR SHA256은 `1aae662399647912ce599b9f760fd5c2d5a3aa63c110e892da67565df5406f24`다.

느린 응답25.444초 보류 중 다른 프로젝트 진행과 예약 병합을 확인했다. 중단 전 성공8·9·9회, 두 서버171.987초 중단 후9·10·10회로 프로젝트마다 정확히1회만 추가됐다. 총29개 요청/실행에서 저장 커밋·이슈는 각각3개였고 프로젝트별 상세/diff/합성 AI 호출은 각각1회였다. 실제 모델 호출은0회다.

68회 관측에서 A의 세 기동과 B의 두 기동을 모두 확인했다. WAR별 최고 RSS는 A476,626,944바이트(약454.5MiB), B470,814,720바이트(약449.0MiB)였고 heap설정은 각384MiB였다. 각 세대의 CPU 누적값·thread와 관측 시작/끝을 보고서에 남겼다. 두 앱의 DB 연결 합계는 관측상 최대6개, 전용 schema relation크기는 최대933,888바이트였다. 각 항목의 최대값은 서로 다른 시점일 수 있으며10초 표본으로 순간 부하·장기 누수·운영 용량을 확정하지 않는다.

원본14테이블 보존과 소유 WAR/schema/work·부모PG 정리를 모두 통과했다. 증거는 Linux `.local/linux-postgres-b3a54150264247b7b8376b82c70f612f/review-schedule-b3a54150264247b7b8376b82c70f612f.json`이다. 운영1시간 간격의 장기간 반복, 실제 Git/API 할당량과 모델 추론 메모리는 이번 범위에 포함하지 않는다.

같은 WAR의 자원 관측ON 실제SIGTERM 검증도31.965초에 통과했다. 느린 예약1개 RUNNING·다른2개 SUCCEEDED 시점에서 취소해 종료130/KeyboardInterrupt/FAIL을 유지했다. 취소 전4개 표본·3개 기동을 관측했지만 `complete=false`로 남기며, 원본14테이블·소유 WAR/schema/work·부모PG/lock 정리를 확인했다. `.local/session15-schedule-cancel.json`은 이 의도된 취소 시나리오의 통과 기록이며 예약 완료나 자원 관측 완료를 뜻하지 않는다.
