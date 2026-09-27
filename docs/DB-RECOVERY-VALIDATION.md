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
