# 실제 PostgreSQL 중지·복구 검증

`scripts/verify-review-db-recovery.py`는 실행 중인 WAR를 유지한 채 **부모 검증 스크립트가 이번 실행에 시작한 격리 PostgreSQL17 클러스터**를 중지·재기동한다. 기본 테스트에서는 실행하지 않는다. 실제 운영 DB·외부 Git·유료/설치 모델·Docker를 사용하지 않는다.

## 재현 범위

권장 경로는 저장소 루트에서 부모 검증 스크립트를 실행하는 것이다. 이 부모가 독립 클러스터를 시작하고 현재 소유권을 기록한 뒤 자식 검증에 전달하며, 마지막 종료까지 맡는다.

```powershell
.\scripts\test-postgres.ps1 -BackupRestore -ReviewRestart -ReviewConcurrency -ReviewDatabaseRecovery
```

부모 `scripts/test-postgres.ps1`가 PostgreSQL을 시작한 뒤 빌드한 WAR와 `--pg-ctl`, `--cluster-path`, `--expected-postmaster-pid`, `--expected-postmaster-started-at`, 이번 실행의 `--parent-run-token`과 고유 `--report` 경로를 전달한다. PID와 시작 시각은 부모가 PostgreSQL을 시작한 직후 기록한 값이며 자식 최초 확인부터 둘 다 일치해야 한다. `--port`는 WAR의 별도 loopback HTTP 포트이며 PostgreSQL 포트는 `TEST_DATABASE_URL`에서 얻는다. URL은 `jdbc:postgresql://127.0.0.1:<port>/reviewer_integration` 또는 localhost 형태만 허용하고 JDBC 추가 파라미터는 거부한다. 비밀번호는 `TEST_DATABASE_PASSWORD` 환경으로만 전달한다.

직접 호출 형식은 다음과 같다. PID는 임의로 추측하지 않고 현재 부모 실행이 소유한 `postmaster.pid`에서 받아야 한다. 이미 다른 테스트가 실행 중인 클러스터를 공유하거나 이 명령을 운영 서버에 적용하지 않는다.

```text
python scripts/verify-review-db-recovery.py
  --war <built-war> --java <java-executable> --psql <psql-executable>
  --pg-ctl <pg_ctl-executable> --cluster-path <repository>/.local/pg-validation
  --expected-postmaster-pid <parent-owned-current-pid> --port 18092
  --expected-postmaster-started-at <parent-recorded-positive-epoch-seconds>
  --parent-run-token <this-parent-run-32-lowercase-hex> --report <unique-run-report.json>
```

스크립트는 저장소의 `.local/pg-validation` 이외 디렉터리를 거부한다. 경로의 symlink/reparse point, 전용 marker의 내용과 파일 식별자, PG_VERSION17, PID 파일의 PID·디렉터리·포트·시작 시각·listen 주소를 확인한다. SQL로 접속한 실제 서버의 data_directory/port/listen 주소와 postmaster PID·시작 시각도 대조한 후 `pg_ctl -m fast stop`을 한 번 요청한다. PID는 positive int32(1..2147483647), 시작 시각은 positive int64(1..9223372036854775807)로 제한한다. 최초 시작 시각도 부모 기록과 정확히 비교하므로 같은 PID가 재사용되어도 새 서버를 원래 서버로 인정하지 않는다. 새 서버는 같은 고정 디렉터리에 loopback 주소·같은 포트로만 시작하고 새 PID도 SQL과 재검증한다. 자식이 직접 정상 시작한 경우에만 PID와 시작 시각을 갱신하며, 예고 없는 교체 서버는 자기 것으로 받아들이지 않는다.

`pg_ctl`의 자식 postmaster가 파이프를 상속해 검증이 멈추지 않도록 stdout/stderr는 DEVNULL, 서버 로그는 해당 실행의 전용 로그 파일로 보낸다. lifecycle 명령은25초, 서버 대기는20초로 제한한다. WAR는 기존 helper의 숨김 자식 프로세스·환경 allowlist·120초 AI 예산을 사용한다. 전체 시나리오는 기본240초(90..600 설정 가능), 시작 대기는45초이며 개별 HTTP/SQL/정리도 유한한 대기를 사용한다. 작업 종료 시간의 SLA나 엄격한 단일 wall-clock 제한을 보장하는 수치는 아니다.

## 확인하는 실패·복구 순서

1. 임의 UUID schema와 합성 관리자를 만들고 실제 로그인/CSRF로 GitLab 형태의 loopback 프로젝트를 등록·승인·접수한다. 두 커밋 A/B의 Git·Ollama 프로토콜 응답만 제공한다.
2. A의 리뷰·이슈와 이번 시도 저장1건·저장 시각이 원자 저장되고, B의 AI 응답은 보류된 상태를 확인한다. 진행 기준은 아직 null이다.
3. 소유권을 재확인해 격리 PostgreSQL을 중지한다. 같은 WAR의 익명 생존 확인은200/UP, 준비 확인은503/DOWN이며 JSON에는 status만 있어야 한다.
4. B의 응답을 허용하고 **DB가 계속 중지된 동안** 해당 프로젝트의 작업자 실패 로그를 확인한다. 고정 `Project <id> review execution failed:` 진단만 검사하며 원문 로그·HTTP/SQL 응답·비밀번호를 결과 JSON에 복사하지 않는다. 이 진단 문구가 변경되면 테스트도 함께 변경해야 한다.
5. PostgreSQL을 재시작한다. B 재시도 응답을 다시 보류하여 동일 요청/요청자·attempt2·A 결과 보존·이슈1개·새 실행의 저장0건과 저장 시각 없음·REVIEWING을 확인한다. 준비 확인은200으로 돌아오고 프로젝트/리뷰 JSP의 진행 카드도 새 실행0건을 표시한다.
6. B를 허용하면 이슈2개, 올바른 담당자, 진행 기준B, 실행 FAILED/SUCCEEDED 및 각 저장1건, 마지막 FINALIZING/저장 시각이 남아야 한다. A 상세/diff/AI는 각1회, B는 각2회이며 WAR 시작 횟수는1이어야 한다.

## 정리와 결과 해석

2026-09-27 KST, 최종 WAR와 Windows의 격리 PostgreSQL17에서42.984초에 통과했다. WAR 시작1회, 같은 요청의 처리 시도2회, A 상세/diff/AI 각1회와 미저장B 각2회를 확인했다. 이슈2개·진행 기준B·FAILED/SUCCEEDED 실행·새 시도 진행 카드, 생존200/준비503→준비200, 소유 WAR/schema 제거와 부모의 최종 PostgreSQL 종료까지 통과했다. 부모·자식 소유권 경계의 독립 리뷰 뒤 경로/marker/버전/PID 재사용/실패 정리32개 fixture도 통과했다. 보고서는 Git 제외된 `.local/review-db-recovery-44e7fd2ffaae4fc697c00577715d3df1.json`에 보존했다. 원격 CI와 실제 Linux 서버 실행은 미실행이다.

모든 경로에서 두 AI latch를 해제하고 직접 시작한 WAR만 종료한다. DB를 중지하려고 시도했다면 같은 클러스터를 복원하여 부모 스크립트가 마지막 종료를 맡을 수 있도록 한다. 소유 WAR가 남거나 DB 복원이 실패하면 schema를 삭제하지 않으며 정리 실패를 PASS로 표시하지 않는다. 생성 UUID schema만 삭제하고, WAR가 종료된 경우에만 생성한 임시 작업 디렉터리를 제거한다. 결과는 Git 제외된 `.local/review-db-recovery-<run-token>.json`, 서버·WAR 로그는 전용 실행 디렉터리에 남긴다. 자식 단독 호출에서도 report를 생략하면 전달된 실행 토큰으로 같은 형태의 경로를 만든다.

보고서에는 전달받은 `parentRunToken`을 기록한다. 정리 후 현재 클러스터의 디스크·SQL 소유권을 다시 확인한 경우에만 `parentOwnedPostmaster`의 PID와 시작 시각을 기록한다. 실패한 시작 뒤 나타난 모호한 PID, 예고 없는 PID 교체, marker 불일치는 인계 대상으로 기록하지 않는다. 부모는 고유 보고서의 실행 토큰을 확인한 뒤에만 인계를 받아 마지막 종료 대상을 갱신해야 한다. 인계 실패가 부모의 무조건 종료로 이어져서는 안 된다.

이는 한 번의 fast stop과 재연결·작업 복구를 검증한다. OS 강제 종료, WAL 손상, 장시간 네트워크 단절, 여러 운영 DB/노드, 실제 서비스의 복구 시간이나 외부 AI 과금의 정확히 한 번 실행을 보장하지 않는다. 중단 시 미저장 B의 AI 호출이 반복되는 것은 의도한 시나리오다. 운영 백업/복원·대형 부하·Linux 서비스 종료 검증은 별도로 남는다.

## 살아 있는 DB의 연결 단절 검증

`scripts/verify-review-network-recovery.py`는 DB 프로세스를 유지하며 직접 시작한 WAR의 DB 연결만 loopback TCP 프록시로 차단한다. 실제 방화벽이나 사용자 네트워크 설정은 바꾸지 않는다. PostgreSQL의 즉시 연결 종료와 달리, 양방향 application stream의 바이트를 버려 실제 pgJDBC/Hikari 시간 제한·죽은 연결 퇴출·새 연결 확보를 확인하는 선택 시험이다. kernel packet loss나 TCP 재전송 전체를 재현하는 도구는 아니다.

권장 Linux 재현은 저장소 루트에서 다음을 실행하는 것이다. 일반 Linux 사용자와 native 파일시스템의 전용PG17/Java25를 사용한다. 전체 빌드와 필수PG 보고 gate가 통과한 뒤에만 실행하며 WAR 기본포트는18101이다.

```bash
python3 scripts/test-postgres-linux.py \
  --pg-bin /usr/lib/postgresql/17/bin \
  --java /usr/lib/jvm/temurin-25-jdk-amd64/bin/java \
  --review-network-recovery
```

기존의 검증용 `reviewer_integration` DB를 명시적으로 사용할 때는 다음과 같이 직접 실행할 수 있다. `TEST_DATABASE_URL`은 query 없는 명시적 loopback JDBC URL이어야 하며, 계정/비밀번호는 기존 검증과 같은 환경변수로 전달한다. URL의 이름만으로 소유권이 보장되지 않으므로 임의의 운영DB를 연결하지 않는다. 증거 경로는 이번 checkout의 `.local` 안에서 존재하지 않는 새 파일을 지정한다.

```bash
python3 scripts/verify-review-network-recovery.py \
  --war /absolute/path/ai-code-reviewer.war \
  --java /absolute/path/java --psql /absolute/path/psql \
  --port 18101 --report /absolute/checkout/.local/network-new.json
```

새 UUID schema에 합성 사용자/프로젝트를 만들고, 관측자는 원래DB로 직접 접속하며 WAR만 전용 프록시로 접속한다. A 저장·B의 AI 응답 보류를 확인한 뒤 양방향 전달을65초 중지하고 B 응답을 허용한다. query5초/socket10초/connect5초·pool 연결 대기10초인 검증 설정을 사용한다. 준비503/생존200의 상태 전용 응답, 작업자 DB 실패·요청ID/담당자/접수시각/claim/run/attempt·A저장/진행 시각 보존·B미저장과 DB PID/시작시각 불변을 관측한다. 실제 기본 운영 한도의 복구 SLA로 해석하지 않는다.

프록시는 기존 연결의 양쪽 소켓을 닫고 새 연결만 전달하며, 같은 WAR가 새claim/run·attempt2로 재개해야 한다. 이전 결과A의 내용/AI는1회, 미저장B는2회이고 이슈2개·진행기준B·새 실행의 진행 카드가 맞아야 한다. SQL로 접수/재시도 시각을 당기지 않는다. 이전 작업자의 저장 거절은 기존PG fencing 회귀의 범위이며 이 시험은 새 토큰 발급과 기존 결과 보존을 확인한다.

모든 경로에서 직접 시작한 WAR·fixture·프록시만 종료한다. 프록시는 단일 selector 스레드, 최대32연결·방향별64KiB buffer·유한 전환/종료 시간을 사용하며 payload를 기록하지 않는다. EOF는 전달해 취소한 클라이언트가 서버 세션을 붙잡지 않게 한다. WAR 종료·프록시 정리·고유 ApplicationName의 DB 세션0건 후 schema OID/owner/marker를 재검사해 직접 만든 schema만 삭제한다. 원본14테이블 지문과 DB 프로세스 불변, 작업 디렉터리 제거가 확인돼야PASS다. 실패·정리 실패·SIGTERM은 합격으로 기록하지 않고 기존 보고서를 덮어쓰지 않는다.

이 시나리오는 한 번의 제한된 연결 분리·재연결이며, commit 응답만 유실되는 경우·몇 시간의 단절·운영 다중 서버·실제 Git/AI 호출·과금 중복 방지를 검증하지 않는다. 자동화는 CI에 정의했으며 실제 원격 실행과 이번 로컬 실행 결과는 WORK.md에서 구분한다.

2026-10-15 WSL의 검증WAR와 새 부모PG 실행에서65.020초 단절·총77.532초에 통과했다. 작업자 실패는30.495초 후 관측했고55회 관측에서 원 요청/토큰·A의 저장과 진행 기록이 보존됐다. 같은WAR/요청의 새claim·attempt2로B만 재검토해 이슈2개·진행기준B를 확인했다. 프록시 accepted17/peakActive4/폐기930바이트/연결오류0, 소유WAR/프록시/세션/schema/work·부모PG 정리·원본14테이블 지문 보존을 확인했다. 실제 운영 시간 제한의 성능 보장은 아니다. 근거는 Git 제외 `.local/session17-network-trial1.json`이다.

같은 날 실제 단절 중 작업자DB실패와A보존을 확인한 뒤 자식도구에SIGTERM을 보냈다.65초 관측이 끝나기 전 취소되어 보고서는FAIL·종료130·KeyboardInterrupt를 유지했고, 소유WAR/프록시/세션/schema/work·원본14테이블과 부모PG종료 정리를 확인했다. 이 취소 검증은42.662초에 통과했으며 정상복구 합격과 구분한다. 근거는 `.local/session17-network-cancel.json`이다.

## COMMIT 성공 후 완료 응답 유실

이 경계는 저장 전 SQL 오류나 위의 연결 전체 단절과 구분한다. 서버가 이미 저장한 트랜잭션을 호출자가 실패로 인식할 수 있으므로, 요청을 실패로 덮어쓰거나 기존 리뷰를 다시 생성하지 않고 다음 소유자가 저장 상태를 확인해야 한다.

`ReviewRequestPostgresTest`의 세 회귀는 실제 PostgreSQL 연결의 `commit()`이 성공한 뒤 독립 연결로 저장 내용을 확인하고, 호출자에게 SQLState `08006` 예외를 한 번 전달한다. claim 발급 후 오류에서는 Git/AI 호출 없이 남은 요청을 새 token/run으로 복구하고, 커밋별 저장 후 오류에서는 기존 행 전체와 감사·시각·배정을 재사용한다. 최종 완료 후 오류에서는 이미 성공한 요청·진행 기준·성공 감사가 유지되며 이전 snapshot의 재처리는 SKIPPED여야 한다. 이전 claim의 늦은 실패/완료도 저장을 바꿀 수 없다. 이 회귀는 driver/application 경계의 예외이며 실제 응답 프레임 유실이나 pool의 연결 퇴출을 재현하지 않는다. 재시도 대기 시각은 기존 테스트 helper로 앞당기므로 실제 대기 시간의 근거도 아니다.

실제 통신 경계는 별도 `scripts/verify-review-commit-ack.py`에서 검증한다. `pg-commit-ack-fixture.py`는 PostgreSQL startup의 DB·사용자·실행별 ApplicationName과 BackendKeyData의 backend PID로 특정 연결을 확인한다. 기본 `--phase per-commit`에서는 소유 관측자가 해당 프로젝트 행을 잠시 잠근다. 그 관측자가 차단한 WAR backend를 `pg_blocking_pids`로 식별해 한 번만 arm한 뒤 잠금을 해제한다. 프록시는 backend의 정확한 CommandComplete `COMMIT` 프레임을 전송 전에 차단하고 ReadyForQuery를 포함한 해당 연결의 이후 모든 서버 바이트를 EOF/heal까지 버린다. 다른 연결·frontend 바이트는 계속 전달한다. SQL 문자열이나 전체 실행의 몇 번째 COMMIT인지로 주입 위치를 추측하지 않는다.

프레임 형식은 [PostgreSQL17 공식 프로토콜](https://www.postgresql.org/docs/17/protocol-message-formats.html)을 따른다. 서버의 commit 후 완료 응답 순서는 [PG17 서버 구현](https://github.com/postgres/postgres/blob/REL_17_STABLE/src/backend/tcop/postgres.c), 클라이언트의 COMMIT 처리 경계는 고정 의존성 [pgJDBC42.7.13 구현](https://github.com/pgjdbc/pgjdbc/blob/REL42.7.13/pgjdbc/src/main/java/org/postgresql/jdbc/PgConnection.java)을 참조했다. 프록시는 검증용 loopback plaintext만 지원하며 이 시험의 WAR에만 SSL/GSS 암호화를 명시적으로 끈다. 제품의 TLS 설정을 변경하지 않는다.

시험의 JDBC 옵션에만 `assumeMinServerVersion=17`도 지정한다. [드라이버의 startup 구현](https://github.com/pgjdbc/pgjdbc/blob/REL42.7.13/pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java)은 최소 버전 가정이 없으면 ApplicationName을 연결 후 SET으로 전달하므로, 검증PG17임이 확정된 이 시험에서는 시작 메시지에 포함시켜 엄격한 실행 식별자 검사를 유지한다. 다른 WAR 시험의 기본 옵션은 그대로다.

별도의 psql 관측자는 [libpq의 `PGAPPNAME`](https://www.postgresql.org/docs/17/libpq-envars.html)으로 실행별 이름을 명시한다. `PGOPTIONS`의 서버 설정에만 이름을 넣고 psql의 연결 이름과 같다고 가정하지 않는다.

권장 Linux 재현은 다음과 같다. 부모 도구는 전체 빌드·필수PG gate 뒤 새 시험을 실행하며, WAR 포트 기본값은18102다.

```bash
python3 scripts/test-postgres-linux.py \
  --pg-bin /usr/lib/postgresql/17/bin \
  --java /usr/lib/jvm/temurin-25-jdk-amd64/bin/java \
  --review-commit-ack
```

이미 시작한 소유 검증DB에서 직접 실행할 때는 다음과 같다. 기존 도구와 같은 query 없는 loopback `TEST_DATABASE_URL` 및 환경 계정을 사용하고, 새 report는 checkout `.local` 안에 생성한다. 전체 시나리오 기본180초·시작45초·query5/socket10/connect5초·pool10초의 합성 설정이며 운영 복구 시간의 보장이 아니다.

```bash
python3 scripts/verify-review-commit-ack.py \
  --war /absolute/path/ai-code-reviewer.war \
  --java /absolute/path/java --psql /absolute/path/psql \
  --port 18102 --report /absolute/checkout/.local/commit-ack-new.json
```

응답을 버린 뒤 독립 관측자에게 A/B 리뷰·이슈2건과 첫 실행 저장2건이 보여야 하고, 요청은 원래 claim의 RUNNING·진행 기준null이어야 한다. 실제 클라이언트 EOF와 작업자 DB 실패 뒤 같은 WAR가 원 요청의 새claim/attempt2로 재개하며, A/B의 상세/diff/AI는 모두 각1회·기존 전체 행/시각/배정 감사 보존·실행 FAILED(2)/SUCCEEDED(0)·진행 기준B를 요구한다. SQL로 재시도 시각을 당기지 않는다. 연결 하나의 응답 유실이므로 준비503을 필수 결과로 가정하지 않으며 최종 준비200만 확인한다.

최종 완료 응답은 같은 도구의 별도 명시 모드로 검증한다. 부모 `--review-commit-ack`의 기본 동작은 기존 커밋별 저장 시험이며, 최종 완료 시험은 이미 시작한 소유 검증DB에서 직접 실행한다.

```bash
python3 scripts/verify-review-commit-ack.py \
  --phase final-completion \
  --war /absolute/path/ai-code-reviewer.war \
  --java /absolute/path/java --psql /absolute/path/psql \
  --port 18134 --report /absolute/checkout/.local/final-ack-new.json
```

새 UUID schema의 전용 gate 한 행과 `RUNNING→SUCCEEDED` 갱신에만 반응하는 지연 constraint trigger를 사용한다. 관측자가 gate를 잠그면 B 저장과 FINALIZING 기록은 통과하고 최종 완료 COMMIT만 대기한다. 대기 backend를 확인하고 arm/release한 뒤, 클라이언트 오류가 나기 전에 독립 연결에서 동일 요청/claim/run의 SUCCEEDED·attempt1·BATCH_COMPLETED·완료 시각, 커서B·성공 실행1개/저장2개·성공 감사1개와 배정 감사2개를 확인해야 한다. 이 gate와 trigger는 시험용 스키마에만 존재하며 제품 migration·SQL·프록시 프로토콜은 변경하지 않는다.

실제 응답 폐기1회·대상 연결 EOF·작업자 DB 실패를 확인한 뒤 heal한다. 이후12초 관측에서도 요청·프로젝트·실행·리뷰·이슈·감사의 전체 행이 같아야 한다. 새 요청을 제출하거나 재시도 시각을 앞당기지 않고, 동일 WAR/DB·attempt1·A/B 상세/diff/합성AI 각1회·최종 준비200을 요구한다. 원본 행은 메모리 비교에만 사용하며 보고서에 저장하지 않는다. 이 유한 관측은 이후 모든 시각의 무재시도나 외부 유료 AI의 정확히 한 번 과금을 보장하지 않는다. claim 발급의 wire 응답 유실은 여전히 별도다.

프록시는 단일 selector·최대32연결·방향별64KiB pending buffer, startup8KiB·backend 프레임 length1MiB·CommandComplete 본문128바이트 상한과 짧은 제어 프레임만 보류하는 점진 파서를 사용한다. frontend 일반 메시지는 해석하지 않고 기존64KiB queue로 전달한다. 관측자 명령·프로세스 종료도 유한 대기를 적용하고, cancel secret은 전달용 바이트를 제외한 식별 정보·로그·보고서에 저장하지 않는다. 먼저 관측자 트랜잭션/프로세스를 종료하고 WAR/프록시를 중지한 뒤 양쪽 ApplicationName의 DB세션0·schema OID/owner/marker를 확인해 직접 만든 schema만 제거한다. 원본14테이블·DB PID/시작시각·작업 디렉터리까지 확인돼야 PASS다. 프로세스·세션·schema 소유권을 확인할 수 없으면 해당 schema/work 삭제를 거부하고 모든 정리 실패를 FAIL로 기록한다. 모드별 필수 성공 근거와 claim wire 미검증 범위를 보고서에서 구분한다. 실제 운영 다중 서버·TLS·장기 단절·외부 AI 과금의 정확히 한 번 실행은 별도로 남는다.

2026-10-15 WSL에서 최종 Java1252건(1241통과/11선택skip)·필수PG9묶음, Python342건(Windows337통과/5skip·Linux332통과/10skip)과 실제 ACK 시험44.099초 PASS를 확인했다. 프록시는 COMMIT/idle 응답18바이트를 폐기했다. 작업자 실패는10.194초 후 관측했고, 그 전에 독립 DB에서 A/B 저장2건을 확인했다. 같은 WAR 시작1회/동일DB·요청의 attempt2에서 상세/diff/AI는 A/B 각1회, 기존 행·시각·배정 감사와 이슈2건·진행 기준B·FAILED(2)/SUCCEEDED(0)가 유지됐다. proxy accepted4/peakActive3/connectionErrors0이며 관측자·WAR·프록시·세션/schema/work·부모PG 정리와 원본14테이블 보존을 확인했다. 근거는 Git 제외 `.local/session18-ack-trial3.json`이다.

별도 취소 검증은 실제 관측자가 행 잠금을 가진 idle-in-transaction과 A 저장을 확인한 뒤 SIGTERM을 보냈다. 시험용 local wrapper는 lock 획득 후 최대20초 대기를 추가했다. 자식 FAIL·종료130·KeyboardInterrupt·ACK 주입0을 유지하며 전용 관측자 ROLLBACK/프로세스·WAR/프록시/세션/schema/work·부모PG 정리와 원본14테이블 보존을12.196초에 확인했다. 근거는 `.local/session18-ack-cancel-trial2.json`이며 정상 ACK 복구의 PASS와 구분한다. 최초 startup/observer 설정 불일치2실행은 FAIL로 보존했고 각 자원을 확인·정리한 뒤 위 최종 실행을 진행했다. 원격 CI·운영 환경의 실제 실행은 미수행이다.

2026-10-16 WSL에서는 새 검증WAR `41da585d80382dba9f08cda51efd5e1da21987129b5860fed97f30bacb822c4f`로 최종 완료 전용 모드를 실제 실행했다. 부모55.865초/자식35.836초 PASS, COMMIT/idle18바이트 폐기1회·작업자 실패10.167초를 확인했다. 오류 전에 독립 DB에서 성공 요청/실행·attempt1·커서B·이슈2건·성공 감사1개/배정 감사2개를 확인했고, heal 후12.205초/53회에도 전체 행과 A/B 상세/diff/합성AI 각1회가 유지됐다. WAR시작1회/동일DB·proxy연결오류0·필수17항목과 원본14표·소유자원/부모PG 정리를 통과했다. `.local/session27b-ack-verification.json`과 양OS동일 child SHA `4a6275a97a0b2fae1cdae033bd8b36de430c2c3850e8afbb5fd62a3f891fa276`이 근거다. 앞의 과거 기본모드·Java 회귀와 새실행을 구분하며 실제 모델은 호출하지 않았다.

같은 새 도구의 기본 모드도 기존 부모 `--review-commit-ack` 검증기로 다시 실행했다. 부모63.840초/자식44.341초 PASS, worker실패10.109초·ACK18바이트/1회·attempt2·A/B 상세/diff/합성AI 각1회·기존 행과 이슈2건/커서B·부모의기존필수근거/원본14표·소유자원정리를 확인했다. `.local/session27b-default-ack-verification.json`과 raw child SHA `bcfa3941b4d332e87631d31adde8ab55ff53813447bd7cc2a96e408cc9d7eef6`이 근거이며 새 최종 완료 시험을 기존 기본 모드의 통과로 대신하지 않는다. 집중26건과 전체Python419건은Windows414통과/환경5skip·Linux409통과/환경10skip, 실패/오류0이다. 앱235파일과WAR를유지했으므로이후속에서새Java빌드로세지않는다.
