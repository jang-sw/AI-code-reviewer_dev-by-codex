# Linux 서버 배포·복구 가이드

Docker 없이 Java 25 실행 WAR, PostgreSQL 17, systemd, 같은 호스트의 TLS 프록시로 배포하는 절차다. 2026-10-02 전용 WSL2 Ubuntu24.04.5에서 설치·제한 계정·HTTPS·재시작·새 DB 복원을 검증했다([범위와 증거](WSL-VALIDATION.md)). **실제 운영 서버의 부팅·인증서 갱신·업그레이드·부하 검증과 배포 승인은 남아 있다.** 배포 대상 배포판/CPU/Java 공급자와 모델이 확정되면 해당 서버의 staging 환경에서 먼저 실행한다. 자동 설치·운영 DB 삭제 스크립트는 제공하지 않는다.

## 파일과 배치 구조

| 경로 | 소유자·권한 / 역할 |
|---|---|
| `/opt/ai-reviewer/releases/<release-id>/ai-code-reviewer.war` | root:root, 파일0644 / 검증한 릴리스별 WAR |
| `/opt/ai-reviewer/current` | root 소유 심볼릭 링크 / 선택된 릴리스 디렉터리 |
| `/opt/ai-reviewer/java` | root 소유 링크 / 승인한 Java 25 설치 디렉터리 |
| `/etc/ai-reviewer/reviewer.env` | root:root,0600; 상위 디렉터리0700 / 비밀 환경설정 |
| `/var/lib/ai-reviewer` | systemd가 전용 계정 소유로 생성 / 작업 디렉터리 |
| `/run/ai-reviewer` | systemd가0700으로 생성 / 임시 JSP 컴파일 등, 재시작 시 제거 가능 |
| PostgreSQL DB | 계정·프로젝트·리뷰·이슈·감사·Flyway 데이터 / 영구 데이터 원본 |

템플릿은 [systemd 서비스](../deploy/linux/ai-reviewer.service), [환경파일](../deploy/linux/reviewer.env.example), [Nginx](../deploy/linux/nginx.conf.example)이다. 서비스의 JVM heap2GiB는 출발 설정이며 서버 RAM, 네이티브 메모리, PostgreSQL/로컬 모델 사용량을 포함한 측정 후 조정한다. JIT를 막는 `MemoryDenyWriteExecute`는 사용하지 않는다. 코어 덤프도 비활성화한다.

systemd가 root 권한으로 환경파일을 읽은 뒤 전용 계정으로 앱을 실행한다. 환경파일은 셸 스크립트가 아니며 `source`·`eval`하지 않는다. 환경변수는 서비스 프로세스에 전달되므로 root/같은 서비스 UID의 접근까지 숨기는 secret 저장소는 아니다. 이 계정을 다른 앱에 공유하지 않는다. 비밀값은 명령줄·Git·화면 캡처·journal에 복사하지 않는다. [systemd 실행환경 문서](https://github.com/systemd/systemd/blob/main/man/systemd.exec.xml)

서비스는 SIGTERM으로 정상 종료하며 Java가 반환하는 종료 코드143도 `SuccessExitStatus=143`으로 성공 처리한다. WSL 실검증에서 정상 종료를 실패로 표시하던 문제를 수정한 설정이다. 비정상 종료는 `Restart=on-failure`의 대상이며, 실제 SIGKILL 후 자동 재시작과 준비 상태 회복도 확인했다. 종료 성공과 미완료 리뷰 요청의 재개는 별도 검증 항목이다.

## 설치 전 확인

1. Java25, PostgreSQL17 서버/클라이언트, Bash, `sha256sum`, `unzip`, systemd, TLS 프록시를 배포판의 신뢰하는 경로로 설치한다. 실제 패키지 설치는 관리자가 수행한다. DB는 전용 인스턴스 또는 독립 DB를 사용한다. 테스트용 trust 인증은 운영에 복사하지 않는다.
2. Java 공급자의 지원기간/업데이트 정책과 현재 취약점 기록을 확인한다. `java -version`, `psql --version`, `systemd --version`을 배포 기록에 남긴다. 서비스가 지정한 `/opt/ai-reviewer/java/bin/java`가 Java25인지 확인한다. 서비스의 `ProtectHome=yes`가 홈 디렉터리 접근을 막으므로 Java는 root가 관리하는 `/opt` 등 홈 밖에 설치한다. `/opt/ai-reviewer/java` 링크의 실제 대상도 `/home`·`/root` 아래에 두지 않는다.
3. 외부 접근은 HTTPS만 허용하고8080/5432는 일반 사용자망에 열지 않는다. 원격 DB라면 PG 서버 인증서 검증을 포함한 TLS를 별도 구성한다. Git/AI 대상은 조직이 승인한 주소만 설정한다.
4. 소스의 검증된 커밋에서 `(cd source && ./mvnw -B -ntp verify)`를 실행해 WAR를 만든다. 이는 기본 테스트이며 실제 PG 검증 결과와 구분한다. CI 전체 검증 또는 격리 PostgreSQL 검증 결과를 함께 보관한다. 운영 DB를 테스트 대상으로 지정하지 않는다.

빌드 호스트에서 `sha256sum source/target/ai-code-reviewer.war`의 결과, 소스 커밋, 테스트 결과를 승인 기록에 저장한다. 서버에 옮긴 파일을 설치 전에 다음과 같이 확인한다. 기대 해시는 승인 기록에서 가져오며 전송한 파일에서 다시 계산한 값을 그대로 기대값으로 사용하지 않는다.

```bash
bash deploy/linux/verify-artifact.sh /absolute/staging/ai-code-reviewer.war EXPECTED_64_HEX_DIGEST
```

검증 도구는 체크섬과 실행 WAR 구조만 읽는다. 신뢰하지 않는 WAR를 실행하지 않으며 실제 서비스 시작·JSP/DB 동작 검증을 대신하지 않는다.

WAR와 빈 환경 예제·서비스·Nginx 템플릿·운영 문서는 [후보 패키지 생성 도구](CANDIDATE-PACKAGE.md)로 묶을 수 있다. 현재 개발 버전과 미승인 상태를 기록하며 실제 환경파일은 포함하지 않는다. 후보 생성이나 체크섬 통과만으로 릴리스 승인이 끝나는 것은 아니다.

## 최초 설치

다음은 Linux 관리자에게 제공하는 단계다. 기존 계정·경로가 있으면 소유자와 목적을 확인하고 재사용 여부를 결정한다. `useradd`/`install`/`ln` 실패를 무시하지 않는다. 서비스 계정은 로그인 불가, root가 관리하는 WAR·설정에 쓰기 불가여야 한다.

```bash
# 서버 관리자가 실행. 배포판에 맞는 nologin 경로를 먼저 확인한다.
sudo useradd --system --user-group --home-dir /var/lib/ai-reviewer --no-create-home --shell /usr/sbin/nologin ai-reviewer
sudo install -d -o root -g root -m 0755 /opt/ai-reviewer /opt/ai-reviewer/releases
sudo install -d -o root -g root -m 0700 /etc/ai-reviewer
```

DB 관리자는 전용 `ai_reviewer` 로그인 role을 생성하되 SUPERUSER/CREATEDB/CREATEROLE/REPLICATION을 부여하지 않는다. 전용 DB `ai_reviewer`의 소유자로 지정한다. 이 버전은 시작 시 Flyway가 DDL을 실행하므로 앱 role은 자기 DB의 schema 변경 권한이 필요하다. 비밀번호는 대화형 `psql`의 `\password ai_reviewer`처럼 기록에 값이 남지 않는 입력 방식으로 설정한다. 공용 DB·기존 role의 권한을 일괄 변경하지 않는다. 접속 인증은 SCRAM을 사용하고 실제 최소권한은 staging에서 확인한다.

릴리스 디렉터리는 **아직 없는 이름**으로 생성한다. `/opt/ai-reviewer/releases/<release-id>`에 검증된 WAR를0644로 복사하고 부모를0755/root 소유로 유지한다. 승인된 Java25 디렉터리를 가리키는 `/opt/ai-reviewer/java`와 릴리스를 가리키는 `current` 링크를 만든다. 처음 설치할 때는 기존 링크를 덮어쓰는 `-f` 옵션을 사용하지 않는다.

환경파일도 최초에는 기존 파일이 없는지 확인하고 `reviewer.env.example`을0600/root 소유로 복사한 뒤 `sudoedit /etc/ai-reviewer/reviewer.env`로 설정한다. `DB_PASSWORD`와 bootstrap 세 값은 비워 둔 채 시작하지 않는다. 실제 비밀값은 이 저장소 밖에서만 입력한다. 모델은 [운영 설정](OPERATIONS.md)을 따라 정하고 `REVIEW_ENABLED=false`, `REVIEW_WORKER_ENABLED=false`로 첫 실행한다. 기본 `gemma3:1b`를 운영 품질 승인으로 해석하지 않는다.

```bash
# 검토한 단위 파일만 설치. 기존 단위가 있으면 먼저 차이를 검토한다.
sudo install -o root -g root -m 0644 deploy/linux/ai-reviewer.service /etc/systemd/system/ai-reviewer.service
sudo systemd-analyze verify /etc/systemd/system/ai-reviewer.service
sudo systemctl daemon-reload
sudo systemctl start ai-reviewer
sudo systemctl status ai-reviewer --no-pager
```

`systemctl start` 성공은 DB/화면 준비 완료의 증명이 아니다. `Type=exec`는 Java 실행 여부까지만 확인한다. 이 단계에서는 서비스 프로세스의 전용 계정, 파일·디렉터리 권한과 아래 상태 응답을 확인하고 journal을 제한된 권한으로 살핀다. 실제 로그인과 사용자 기능 검증은 TLS 프록시를 준비한 다음 진행한다. 서비스 자동 재시작은 반복 실패 시 제한되므로 원인 수정 후 필요할 때 `systemctl reset-failed ai-reviewer`를 사용한다. [systemd 서비스 문서](https://github.com/systemd/systemd/blob/main/man/systemd.service.xml)

루프백에서 아래 GET 요청으로 프로세스의 생존 상태와 DB 연결을 포함한 준비 상태를 확인한다. 정상은200과 `{"status":"UP"}`, 비정상은503이다. 인증 없이 상태만 응답하며 Git·AI 품질이나 리뷰 진행을 보장하지 않는다. `/actuator/health`와 `/actuator/metrics/**`는 승인된 관리자 로그인이 필요하므로302를 성공으로 처리하지 않는다. 실패·지연 및 수집 상태는 관리자 ‘서버 상태’ 화면에서 따로 확인한다. [모니터링 범위와 장애 대응](MONITORING.md)을 따른다.

```bash
curl --fail --silent --show-error --max-time 15 http://127.0.0.1:8080/actuator/health/liveness
curl --fail --silent --show-error --max-time 15 http://127.0.0.1:8080/actuator/health/readiness
```

상태 응답을 확인한 뒤 아래 TLS·프록시 절차를 적용한다. 환경파일의 `SESSION_COOKIE_SECURE=true`를 유지하고, 준비한 실제 HTTPS 주소에서 로그인 화면과 최초 관리자 로그인, 가입 승인, 프로젝트 신청·승인, 수동 요청이 DB 대기 상태로 접수되는지 확인한다. Python 재시작·동시성·DB 복구 도구의 HTTP 통신은 별도 합성 검증용이며 이 운영 서비스의 로그인 절차와 구분한다.

HTTPS에서 최초 관리자 로그인이 확인되면 bootstrap 세 변수를 환경파일에서 제거하고 계획한 재시작을 한다. 기존 DB에서 bootstrap 값을 지워도 기존 관리자는 유지된다. 재로그인을 확인하고 Git·AI 검증 준비가 끝나면 `REVIEW_WORKER_ENABLED=true`로 재시작해 합성/허용된 저장소의 수동 리뷰를 검증한다. TLS/권한/모델 검증 후 `REVIEW_ENABLED=true`로 전환해 재시작하고 예약 실행을 확인한다. 마지막으로 `systemctl enable ai-reviewer`로 부팅 시 실행을 설정한다.

## TLS·프록시 경계

Nginx 예시는 공인 edge가 같은 서버에 있고 앱은127.0.0.1에만 바인딩하는 구성이다. 실제 도메인/인증서 경로로 변경하고 기존 Nginx 구성과 충돌을 검토한 후 `nginx -t`가 성공할 때 적용한다. 예시 도메인은 배포값이 아니다. 인증서 갱신도 별도 운영 절차에 포함한다.

프록시는 `X-Forwarded-*`를 클라이언트 값과 이어 붙이지 않고 재작성한다. Tomcat은127.0.0.1만 프록시로 신뢰하도록 환경파일에서 지정한다. 이 설정 덕분에 앱의 IP 제한이 실제 클라이언트 주소를 볼 수 있지만, 외부 프록시/CDN/다른 호스트를 추가하면 신뢰 경계를 다시 설계해야 한다. 전달 헤더 위조 요청, HTTPS 리다이렉트, secure cookie, CSRF, 로그아웃을 staging에서 시험한다. [Nginx 헤더 설정](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_set_header), [Spring Boot 프록시 설정](https://docs.spring.io/spring-boot/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server)

예시의 요청 제한은 인스턴스 한 대의 출발 설정이다. V14 앱은 같은 DB에서 계정/IP 횟수를 공유하며, 프록시 제한은 DB 접근과 비밀번호 검증 부하를 줄인다. 모든 앱 서버의 제한 설정을 같게 유지하고 [공유 제한 전환 절차](AUTH-LIMITING.md)를 따른다. URL/쿼리 문자열에 이슈 내용이나 비밀을 보내지 않고, Nginx access log의 접근권한·보존기간도 정한다. 최초 적용 전 관리 화면을 인터넷 전체에 공개할지 조직망으로 제한할지 결정한다.

## 백업

최소 보관 대상은 DB custom-format dump, role/권한 재생성 절차, 해당 시점 WAR+해시+커밋, 비밀을 제외한 설정 버전이다. 실제 secret은 별도 암호화된 관리 체계로 보관한다. DB dump에는 비밀번호 해시·작성자 이메일·코드 권고가 있으므로 암호화·접근제어·보관기간·복원 담당자를 정한다. 단순 압축은 암호화가 아니다.

아래는 관리자 소유의 백업 위치에서 실행할 예시이며 기존 파일을 덮어쓰지 않는 새 디렉터리를 사용한다. `PGPASSFILE`은 저장소 밖의0600 파일이며 정확한 host/port/DB/user entry만 허용한다. `PGPASSWORD`나 명령줄 비밀번호를 쓰지 않는다. `reviewer.env`를 셸에 로드해 접속값을 추출하지 않는다. [PostgreSQL password file](https://www.postgresql.org/docs/17/libpq-pgpass.html)

```bash
set -euo pipefail
umask 077
backup_dir="/absolute/protected-backups/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -- "$backup_dir"  # 부모는 사전에 관리자 소유로 준비; 기존 이름이면 중단
export PGHOST=127.0.0.1 PGPORT=5432 PGUSER=ai_reviewer PGDATABASE=ai_reviewer
export PGPASSFILE=/absolute/protected-secrets/backup.pgpass
export PGCONNECT_TIMEOUT=10
pg_dump --no-password --format=custom --lock-wait-timeout=30s --file="$backup_dir/database.dump"
pg_restore --list "$backup_dir/database.dump" > "$backup_dir/archive.list"
sha256sum "$backup_dir/database.dump" > "$backup_dir/database.sha256"
```

위 블록은 Bash의 오류 즉시 중단 옵션을 포함한다. 중간 실패 후 남은 파일을 정상 백업으로 표시하지 않는다. dump는 온라인에서도 DB snapshot의 일관성을 유지하지만 role 같은 전역 객체는 포함하지 않는다. 업데이트 rollback 지점은 모든 앱 인스턴스를 정지한 뒤 dump해 그 시점 이후 쓰기가 없는지 확인한다. 정기 백업의 RPO/RTO와 복사·암호화·원격 보관·복원 리허설은 운영 정책으로 정한다. [PostgreSQL pg_dump](https://www.postgresql.org/docs/17/app-pgdump.html)

## 복원 검증: 항상 새 DB

1. 출처를 신뢰하는 dump의 해시와 생성 시각을 확인한다. 복원 명령은 dump에 담긴 SQL을 실행하므로 임의 외부 dump를 운영 권한으로 열지 않는다.
2. DB 관리자는 **기존에 없는** `ai_reviewer_restore_<시각>` DB를 생성한다. 원본 DB 이름과 다름을 확인한다. role/권한은 미리 준비하고 복원 대상 owner를 정한다. DB 생성 실패 시 멈추며 기존 DB로 fallback하지 않는다.
3. 새 DB 이름을 명시하여 `pg_restore --no-password --exit-on-error --single-transaction --no-owner --no-acl --dbname=ai_reviewer_restore_<시각> /absolute/backup/database.dump`를 실행한다. 원본 DB에 `--clean`/`--create`/`DROP`을 실행하지 않는다. restore role은 새 DB의 승인된 소유자여야 한다. `--no-acl` 때문에 필요한 앱 권한은 새 DB에서 명시적으로 검증한다. [PostgreSQL pg_restore](https://www.postgresql.org/docs/17/app-pgrestore.html)
4. 테이블별 건수, Flyway 버전/성공 이력, 샘플 프로젝트/계정/이슈 상태, FK·sequence를 비교한다. dump 생성 후 온라인 원본이 바뀌었다면 현재 건수와의 단순 비교는 일치 조건이 아니다. 정지 상태에서 잡은 기준 또는 snapshot 기록을 쓴다.
5. 별도 앱 인스턴스/계정/포트로 새 DB를 연결하고 `REVIEW_ENABLED=false`, `REVIEW_WORKER_ENABLED=false`, 외부 Git/AI egress 차단, 운영 프록시에 미연결 상태로 로그인·승인·조회·권한을 검증한다. 시작은 Flyway를 실행하므로 백업 시점 WAR로 먼저 확인한 후 업그레이드 시험을 구분한다. API 토큰은 복원용 환경에 복사하지 않는다.
6. 결과를 기록한다. 검증용 DB 제거는 대상 이름·환경을 다시 확인한 별도 관리자 작업이며 이 절차는 자동 삭제하지 않는다. 실패해도 운영 DB/현재 설정은 바꾸지 않는다.

## 업데이트와 rollback

**업데이트 전:** 새 WAR의 해시/검증 결과, Flyway 변경, 이전 버전과의 DB 호환성을 확인한다. 새 DB 복제본에서 새 WAR로 migration·권한·리뷰·재시작 검증을 마친다. 실제 운영 변경 시간, 복원 시 잃을 수 있는 쓰기, 책임자를 정한다. 이 프로젝트는 자동 down migration을 제공하지 않는다.

**전환:** 프록시에서 유지보수 상태로 전환하고 모든 앱 인스턴스를 중지한다. 실행 중 리뷰는 종료 과정에서 중단될 수 있다. V12 이후 접수 요청은 DB에 보존되며 저장된 커밋을 재사용한다. V11 이전 메모리 대기 요청은 업그레이드 전에 완료시키거나 별도로 기록해 재접수한다. 진행 중 외부 요청과 마지막 저장 결과를 확인해야 한다. 원본 정지 백업을 만든 후 새 릴리스를 별도 디렉터리에 설치한다. `current`가 예상 이전 릴리스를 가리키는 링크인지 `readlink -f`로 확인한다. root 소유 같은 파일시스템에 임시 링크를 만든 뒤 `mv -T`로 `current` 링크를 교체한다. `current`가 실디렉터리면 중단하고 조사한다. 이전 WAR/백업을 삭제하지 않는다.

**시작 후:** `REVIEW_ENABLED=false`, `REVIEW_WORKER_ENABLED=false` 상태로 migration·관리자 로그인·권한·목록/이슈·보존된 요청을 확인한다. 외부 연결 검증 준비 후 `REVIEW_WORKER_ENABLED=true`로 재시작해 기존 접수 요청의 복구와 수동 리뷰를 확인하고, 프록시 트래픽과 새 예약 생성을 순서대로 재개한다. `REVIEW_ENABLED=false`만으로는 기존 요청 처리가 멈추지 않는다. journal의 예외를 통째로 외부 공유하지 않는다. 오래된 RUNNING은 강제 종료 후 남은 기록일 수 있으며 복구는 PG 프로젝트 잠금을 얻은 작업자만 수행한다.

**실패 시:** 앱을 중지하고 원인을 보존한다. 스키마가 바뀌었으면 WAR 링크만 이전으로 돌려 실행하지 않는다. 호환성이 확인된 경우에만 코드 rollback을 선택한다. 호환되지 않거나 불명확하면 업데이트 전 백업을 **새 DB에** 복원·검증하고 이전 WAR와 그 DB를 함께 연결한다. 원본 DB는 보존한다. 운영 전환 이후 쓰기가 있었다면 유실 범위와 병합/재입력 방안을 결정한 뒤 전환한다. DB URL 변경은 root 환경파일을 편집하고 재시작한다. Flyway 실패를 숨기기 위해 history 행 삭제나 무검토 repair를 하지 않는다.

## 남은 서버별 검증

- 실제 Linux에서 `systemd-analyze verify`, 전용 계정 권한, JVM/JSP 임시 쓰기, 재부팅/중지/재시작.
- Nginx TLS·전달 헤더 신뢰·인증서 갱신·IP 제한·공개 포트 확인.
- 실제 DB 최소권한, DB 지연/중단 후 복귀, backup/restore 및 migration/rollback 리허설.
- 운영 모델 품질, 실제 GitLab/LiteLLM 인증, 예상 동시성/대형 저장소 처리시간·메모리·RPO/RTO.

2026-09-26 Windows Git Bash의 문법·artifact fixture5건·WAR 구조 검증에 이어, 2026-10-02 전용 WSL Linux에서 실제 서비스 구동·합성 HTTPS·백업/새 DB 복원 후 로그인을 확인했다. 앱 계정의 DB 클러스터 특권 부재, root0600 환경파일, WAR·Java 쓰기 차단도 검사했다. V12의 합성 계정·프로젝트·대기 요청을 V13으로 올리고 업데이트 전 백업을 새 DB에 복원해 구 WAR로 복귀하는 리허설도 통과했다. 위 서버별 항목 중 운영 부팅·외부 TLS/갱신·장기 장애·운영 데이터 규모의 업그레이드와 모델 품질은 아직 미검증이다. 서버 검증과 릴리스 점검이 끝나기 전에는 운영 완료로 표시하지 않는다.

[CI 설정](../.github/workflows/verify.yml)은 기존 secret 검사와 PostgreSQL 필수 검증이 성공한 뒤 배포 도구 fixture5건, Bash 문법, 그 실행에서 빌드한 WAR의 구조를 확인한다. 이때 계산한 해시는 CI 산출물 자체의 구조 검사용이며 서버 전송 후 독립된 승인 해시 비교를 대체하지 않는다. 이 단계는 서비스 설치·실행·배포를 하지 않으며 원격 GitHub Actions 실행 결과는 별도로 확인한다.
