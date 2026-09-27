# 서버 상태와 운영 관측

현재 구현의 운영 관측 계약이다. 실제 서버 설치·장기 운영·외부 알림 연동의 검증 완료를 뜻하지 않는다. 실제 실행 결과와 재개 상태는 `WORK.md`에서 확인한다.

## 관리자가 확인하는 순서

1. 관리자 메뉴의 **서버 상태**(`/admin/monitoring`)에서 관측 상태와 마지막 성공 관측 시각을 확인한다.
2. 사용 가능한 관측이면 최근 실행 실패와 오래된 미완료 요청의 **화면 알림**을 읽는다. 링크는 기존 운영 상태의 `FAILED` 또는 `REQUEST_DELAYED` 필터로 이동한다. 대기 요청 수치는 `QUEUED` 목록으로 연결된다.
3. 프로젝트별 운영 상태에서 요청 접수 후 경과와 최근 실행 시작 후 경과를 구분하고 리뷰 기록을 확인한다. 수집 실패·오래된 관측은 정상이나 0건으로 판단하지 않는다.
4. 화면 아래 **현재 서버 설정**에서 요청 처리와 새 예약 생성 설정을 각각 확인한다. 이 화면은 설정을 변경하지 않는다.

알림은 화면에만 표시한다. 이메일·푸시 발송, 자동 확인 처리, 외부 알림 서비스 등록, 실시간 화면 갱신은 구현하지 않았다. **화면 새로고침**은 캐시를 다시 읽으며 집계를 즉시 실행하지 않는다.

## 수집과 관측 상태

각 애플리케이션 인스턴스는 시작 후 초기 지연1초, 이전 수집 종료 후30초 간격으로 운영 요약을 수집한다. 스케줄러와 DB 부하로 실제 시작이 늦어질 수 있다. 겹치는 수집 요청은 하나로 합친다.

수집은 별도 읽기 전용 `REQUIRES_NEW`·`REPEATABLE_READ` 트랜잭션에서 다음 세 SQL 집계를 수행한다. 트랜잭션 완료 후에만 관측 캐시 전체를 교체한다. 일부 쿼리나 commit이 실패하면 부분 결과를 공개하지 않는다.

- 프로젝트 승인 상태별 개수.
- 프로젝트별 마지막 요청의 상태별 개수, 미완료 요청 중 가장 오래된 접수 시각, 지연 기준을 넘긴 미완료 요청 수.
- 프로젝트마다 가장 최근 실행 번호의 상태가 `FAILED`인 프로젝트 수.

관측 시각(`observedAt`)은 성공한 수집의 시작 시각이다. `lastAttemptAt`은 마지막으로 종료된 수집 시도의 시작 시각이며 성공 여부와 별개다. 수집 중에는 직전 캐시가 보인다. 경과 시간은 관측을 읽는 서버 시각으로 계산하고 음수는0으로 표시한다.

| 상태 | 의미 | 화면·JSON의 현재 운영 수치 |
|---|---|---|
| `STARTING` | 아직 성공한 수집이 없음 | 표시하지 않음 |
| `READY` | 마지막 수집 성공, 성공 관측 시작 후90초 미만 | 표시 |
| `STALE` | 성공 관측 시작 후90초 이상 경과 | 표시하지 않음 |
| `FAILED` | 마지막 수집 시도가 실패 | 표시하지 않음 |

최근 실패가 있으면 시간 경과보다 `FAILED`를 우선한다. 이후 성공한 수집으로 회복한다. 내부 캐시는 실패·오래됨에도 이전 성공값을 보존하지만 관리자 화면과 JSON은 이를 현재 수치로 반환하지 않는다. 이전 성공 시각·경과·마지막 시도 시각은 확인할 수 있다. 최초 성공 전에는 성공 시각과 경과도 없다.

## 수치와 경고의 의미

프로젝트 수치는 `PENDING`·`APPROVED`·`REJECTED`·`PAUSED`로 구분한다. 요청 수치는 `QUEUED`·`RUNNING`·`SUCCEEDED`·`FAILED`·`CANCELLED`로 구분하며, 프로젝트마다 보존한 마지막 요청 한 건을 센다. 누적 리뷰 실행 횟수가 아니다.

**최근 실행 실패**는 프로젝트 승인 상태와 무관하게 가장 최근 `review_run.id`의 상태만 본다. 이후 새 실행이 시작되면 과거 실패는 제외되며, 새 요청이 접수된 것만으로 이전 실행 실패가 없어지지는 않는다.

**오래된 미완료 요청**은 `QUEUED`·`RUNNING`이고 원래 접수 시각이 관측 시각보다 `OPERATIONS_STALE_AFTER_MINUTES` 이상 이전인 요청이다. 기본120분, 허용1..10080분이며 경계 시각을 포함한다. 반복 복구로 새 실행이 시작돼도 원래 요청의 대기 기간이 지워지지 않는다. 일시 중지된 프로젝트의 요청도 작업자가 취소 처리하기 전까지 포함될 수 있다.

`RUNNING` 기록은 실제 작업자가 살아 있음을 보장하지 않는다. 지연에는 대기·장기 실행·재시작 복구 시간이 함께 포함되며 완료 예상 시간이나 SLA가 아니다. 이 두 경고가 없어도 미실행 등 다른 확인 대상은 `/admin/operations`에서 확인해야 한다.

## 관리자 JSON 스냅샷

`GET /admin/monitoring/snapshot`은 서버 상태 화면과 같은 캐시를 사용한다. HTML과 JSON 모두 현재 DB의 승인·활성 ADMIN 계정을 재확인한 후 읽는다. 일반 사용자에게는 공개하지 않는다. 이 계정 확인은 DB 접근이므로 DB 장애로 인증 확인 자체가 실패하면 관리자 화면·JSON에 접근하지 못할 수 있다.

권한 확인을 통과한 JSON 요청은 `READY`이면200, `STARTING`·`FAILED`·`STALE`이면503이다. 응답에는 `Cache-Control: no-store`를 적용한다. HTML은 관측 상태를 설명하는 화면을200으로 렌더링하며 동일하게 저장 금지 헤더를 보낸다.

JSON은 다음 고정 필드만 반환한다.

- `status`, `available`, `observedAt`, `lastAttemptAt`, `ageSeconds`, `delayedAfterMinutes`.
- `projectCounts`, `requestCounts`, `oldestActiveRequestedAt`, `delayedActiveRequests`, `latestFailedProjects`.
- `currentServerWorkerEnabled`, `currentServerSchedulerEnabled`.

미가용 상태에서는 두 counts 객체가 비어 있고 가장 오래된 접수 시각·지연 수·실패 수는 null이다. 정상0건처럼 대체하지 않는다. 프로젝트·요청·사용자 ID, 이름, 저장소 주소, 공급자 주소, 키, 예외 원문은 포함하지 않는다. 수집이나 리뷰를 실행하는 POST 기능도 없다.

## 상태 점검 경로와 접근 제한

| 경로 | 허용 대상·메서드 | 확인 범위 |
|---|---|---|
| `/actuator/health/liveness` | 익명 `GET`·`HEAD` | 애플리케이션 liveness 상태 |
| `/actuator/health/readiness` | 익명 `GET`·`HEAD` | 애플리케이션 readiness 상태와 DB 연결 점검 |
| `/actuator/health` | 현재 승인·활성 ADMIN의 `GET`·`HEAD` | 구성된 전체 health 요약, 세부 정보 비공개 |
| `/actuator/metrics`, `/actuator/metrics/{name}` | 현재 승인·활성 ADMIN의 `GET`·`HEAD` | 제공되는 지표 목록·값 |

두 공개 probe의 GET 본문에는 상태만 포함하며 DB 주소·계정·오류·컴포넌트 세부 정보는 없다. HEAD는 본문 없이 같은 상태 코드를 반환한다. 실패 상태는503으로 나타날 수 있다. 공개 probe에 기존 세션 쿠키가 함께 와도 계정 재조회 때문에 liveness가 DB에 의존하지 않도록 해당 두 경로의 계정 재검사를 생략한다. readiness 자체의 DB 점검은 수행한다.

그 밖의 Actuator 경로와 변경 메서드는 관리자에게도 허용하지 않는다. `env`, `configprops`, `heapdump`, `loggers`, health 하위 컴포넌트, discovery 링크와 JMX 노출을 사용하지 않는다. 추가 health 경로도 자동 등록하지 않는다.

health 응답은 Git 저장소 접근·토큰 유효성·AI 모델 연결·리뷰 품질·리뷰 작업자의 진행을 보증하지 않는다. worker 설정을 꺼도 애플리케이션과 DB가 정상이라면 readiness가 정상일 수 있다. 운영 요약의 경고와 실제 리뷰 기록을 함께 확인한다.

## 캐시 기반 업무 지표

다음 Micrometer gauge는 화면과 동일한 캐시를 읽으며 수치 조회 때 SQL 집계를 실행하지 않는다. 관측이 미가용하면 업무 수치는 `NaN`으로 측정하여 정상0과 구분한다. 현재 패키지의 Actuator JSON에서는 이 값이 문자열 `"NaN"`으로 반환되는 것을 확인했다. 숫자0으로 변환하지 말고 `ai.reviewer.observation.available`도 함께 확인한다. 외부 수집기의 표시 형식과 경보 조건은 별도로 확인해야 한다.

| 지표 이름 | 태그 / 의미 |
|---|---|
| `ai.reviewer.observation.available` | 사용 가능한 관측이면1, 아니면0 |
| `ai.reviewer.observation.age.seconds` | 마지막 성공 관측의 경과 초, 최초 성공 전 `NaN` |
| `ai.reviewer.projects` | 고정 `status` 4종별 프로젝트 수 |
| `ai.reviewer.requests` | 고정 `state` 5종별 마지막 요청 수 |
| `ai.reviewer.requests.delayed` | 오래된 미완료 요청 수 |
| `ai.reviewer.requests.oldest.active.age.seconds` | 가장 오래된 미완료 요청 접수 후 경과 초; 사용 가능한 관측에 미완료 요청이 없으면0 |
| `ai.reviewer.projects.latest.failed` | 최근 실행이 실패한 프로젝트 수 |

업무 지표는 총14개 시계열이며 태그 값은 위 승인·요청 상태로 고정한다. 프로젝트·사용자·요청 ID, 저장소 주소, 예외 원문을 태그로 사용하지 않는다. Boot가 제공하는 다른 시스템 지표는 이 업무 시계열 수에 포함되지 않는다. Prometheus endpoint, 외부 수집기 설정, 장기 지표 저장소, 이메일·푸시 전송은 이번 구현에 포함되지 않는다.

## 여러 서버와 운영 한계

각 서버는 메모리에 독립적인 관측 캐시를 가지며 재시작 시 `STARTING`으로 시작한다. 같은 DB를 연결하면 업무 집계 대상은 같지만 수집 시각·실패 여부·캐시 최신성은 다를 수 있다. 요청이 다른 서버로 연결되면 표시되는 관측 상태도 바뀔 수 있다.

`REVIEW_WORKER_ENABLED`와 `REVIEW_ENABLED`는 현재 접속한 서버의 설정이다. 전자는 그 서버의 접수 요청 처리를, 후자는 새 예약 요청 생성을 제어한다. 다른 서버는 계속 처리·접수할 수 있으므로 전체 점검 중지는 [운영 가이드](OPERATIONS.md)의 모든 인스턴스 중지 절차를 따른다. 서버 상태 화면은 설정값을 보여주며 실제 실행 중인 스레드 수나 다른 인스턴스 설정을 추정하지 않는다.

수집 실패 로그는 예외 종류만 기록하며 SQL·파라미터·예외 원문은 남기지 않는다. DB health 점검은 Boot4.0.8의 `org.springframework.boot.jdbc.health.DataSourceHealthIndicator` 로거를 `ERROR`로 제한하여 해당 indicator의 WARN 예외 원문 출력을 억제한다. readiness 상태와503 응답은 유지한다. 이 두 조치는 애플리케이션 전체 또는 별도 debug 로그의 전역 정화를 뜻하지 않는다. 실서버 네트워크 접근 정책·TLS·로그 보존·외부 경보 연동·장기 부하 검증은 별도 운영 과제다. 이 문서의 추가만으로 배포나 알림 전송 설정을 수행하지 않는다.

## 공식 참고

Actuator의 endpoint 접근·노출, health 그룹·probe 구성은 [Spring Boot4.0 공식 Endpoints 문서](https://docs.spring.io/spring-boot/4.0/reference/actuator/endpoints.html)를 참고한다. 위 접근 정책과 관측 캐시는 이 프로젝트의 별도 구현이며 Boot 기본값을 그대로 노출한 구성이 아니다.
