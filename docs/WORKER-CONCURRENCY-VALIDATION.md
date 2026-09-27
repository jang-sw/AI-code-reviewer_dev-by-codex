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
