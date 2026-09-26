# 리뷰 요청 지속성과 복구 검증

2026-09-26, Windows 로컬의 Java25·PostgreSQL17·빌드한 WAR에서 검증했다. 운영 Linux 서버·실제 Git/AI·장시간 부하 검증과 구분한다.

## 보존 방식

V12는 프로젝트마다 최근 리뷰 요청 한 건과 다음 예약 시각을 저장한다. 수동 요청은 계정·프로젝트 권한 검사와 함께 커밋한 뒤 접수 응답을 반환한다. 활성 요청에 겹치는 예약·수동 요청은 합치며 기존 요청자와 접수 시각을 유지한다. 완료 후 새 요청은 새 식별자를 받는다. 이전 실행·커밋·이슈·감사는 별도 기록으로 남는다.

작업자는 PostgreSQL 세션 advisory lock을 얻은 뒤 새 처리 토큰과 실행을 원자적으로 기록한다. 각 커밋 저장·완료·실패 기록 시 토큰·실행·현재 권한을 다시 검사한다. 이전 작업자가 늦게 응답해도 교체된 실행을 덮어쓸 수 없다. DB 결과가 불확실하거나 종료 인터럽트를 받으면 미완료 요청을 남겨 다음 작업자가 저장된 진척을 확인하게 한다.

예약은 30초마다 미도래·미승인 프로젝트를 제외하고 최대1,000개를 읽는다. 최초/기한 경과 범위 각각의 상한을 적용한 뒤 최대2,000개만 병합 정렬한다. 요청 poll도 QUEUED/RUNNING 각 최대64개를 읽어 최대128개만 병합한다. 실제 인덱스 사용과 처리량은 DB 통계·부하에 따라 달라진다. 활성 요청과 합친 예약도 다음 예약 시각을 이동하므로 같은1,000개만 반복 선택하지 않는다.

## 실제 PostgreSQL 검증

`ReviewRequestPostgresTest`18건은 생성한 전용 schema와 독립 DB 연결을 사용한다.

- 동시 접수의 병합, 커밋된 요청의 새 repository/worker 재구성 후 처리
- 살아 있는 advisory lock과 다른 프로젝트의 병렬 실행, 소유한 잠금 backend 종료 후 복구
- 이전 토큰의 커밋 저장·성공·실패 기록 거부와 늦은 AI 결과 차단
- 접수·claim·완료·실패 기록 중 DB 오류의 롤백과 저장된 SHA 재사용
- 완료된 요청 snapshot과 새 요청의 구분, 요청자·프로젝트 권한 변경 시 취소
- 예약 NULL·과거·정시·미래와 상태/동률 경계, 두 후보 쿼리의 가변 조회 개수 반복

H2 회귀는 후보 순서/페이지 경계, 활성 예약1,000건 뒤 프로젝트의 진행, 중단/정상 반환 인터럽트, 최대1시간의 복구 backoff, V11 데이터 보존 등을 추가 검사한다. V12의 `review_request`를 포함해10개 테이블을 새 테스트 DB로 복원하고 내용 fingerprint·identity 삽입도 확인했다.

## 실제 WAR 강제 종료 검증

Windows 재현:

```powershell
.\scripts\test-postgres.ps1 -BackupRestore -ReviewRestart
```

이미 준비된 로컬 테스트 PostgreSQL에서는 다음 명령도 사용할 수 있다. `TEST_DATABASE_URL`은 명시적 포트의 loopback `reviewer_integration`만 허용한다. 계정·비밀번호는 `TEST_DATABASE_USERNAME`/`TEST_DATABASE_PASSWORD`로 전달한다.

```bash
python3 scripts/verify-review-restart.py \
  --war /absolute/path/ai-code-reviewer.war \
  --java /absolute/path/java --psql /absolute/path/psql --port 18089
```

스크립트는 임의의 전용 schema·context path·합성 관리자와 loopback GitLab/Ollama를 생성한다. 자식 환경에는 실제 키·토큰·Java/Spring override를 상속하지 않는다. 현재 프로세스가 생성한 WAR·schema·임시 작업 디렉터리만 정리한다. 결과는 Git 제외된 `.local/review-restart-result.json`, WAR 로그는 해당 실행 디렉터리에 남는다.

1. 예약·작업자를 끈 WAR에서 로그인/CSRF를 거쳐 프로젝트를 등록·승인하고 수동 요청을 접수한다. QUEUED DB 행·중지 배너·요청 화면과 외부 호출0건을 검사한다.
2. 작업자를 켜고 재시작한다. 첫 커밋 A의 리뷰·이슈가 저장됐지만 진행 기준은 아직 이동하지 않은 상태에서 두 번째 커밋 B의 AI 응답을 보류하고 소유 WAR 프로세스를 강제 종료한다.
3. 다시 시작해 동일 요청/요청자가 재개됨을 확인한다. A의 상세/diff/AI는 각1회, B는 각2회다. 이력과 부모 tree 증명은 다시 수집할 수 있다.
4. 이슈2개·올바른 담당자·진행 기준B, 이전 실행 FAILED/복구 실행 SUCCEEDED, 처리 시도2회와 실제 JSP 이슈 표시를 확인한다.

최종 실행은51.3초에 통과했다. 세 WAR 프로세스가 종료됐고 전용 schema 제거까지 확인했다. 활성 요청 안내는 프로젝트 상세와 리뷰 기록을 각각 조회해 검사했다. CI에도 같은 검증을 연결했으나 실제 원격 CI 실행은 아직 하지 않았다. Linux의 `psql` wrapper가 실행 파일 이름을 이용하므로 실행 경로의 심볼릭 링크를 보존한다. POSIX wrapper 회귀는 Windows에서 선택 제외되며 Linux CI에서 실행하도록 구성했다.

## 보장 범위와 남은 검증

완료한 커밋의 DB 저장·이슈 중복 방지와 요청의 재시작 보존을 확인했다. 외부 AI 호출 자체의 정확히 한 번 실행이나 과금 중복 방지를 보장하지 않는다. 응답 뒤 DB commit 전에 중단되거나 잠금 연결이 끊어지면 미저장 커밋을 다시 호출할 수 있다. 복구 backoff는 재확인 간격이며 작업 종료 시간이나 SLA가 아니다.

실제 DB 서버 재시작·장기 네트워크 분리·운영 다중 인스턴스 장시간 공정성/지연·대형 저장소 처리량·Linux systemd 종료 시간·운영 백업 복구는 별도 검증이 필요하다. `REVIEW_ENABLED=false`는 새 예약만 멈춘다. 완전한 유지보수 중지는 모든 인스턴스에서 `REVIEW_WORKER_ENABLED=false` 또는 서비스 종료를 함께 적용해야 한다.
