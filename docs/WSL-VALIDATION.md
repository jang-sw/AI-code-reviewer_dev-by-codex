# WSL2 Linux 검증 환경과 재현 순서

검증 기준: 2026-10-02. Ubuntu 24.04 LTS의 전용 WSL2 배포판에서 Java 25, PostgreSQL 17, 실행 WAR를 Docker 없이 검증했다. 실제 Linux 전체 빌드·DB·백업·세 WAR 장애/동시성 시험과 별도 systemd·HTTPS·새 DB 복원 시험을 통과했다. 아래 절차는 재현 안내이며, 이번 실행의 버전·결과·한계는 문서 끝에 구분해 기록한다. **운영 서버 부팅·업그레이드·실제 모델 품질 검증과 릴리스 승인은 아직 남아 있다.**

앱 설치·운영 절차는 [Linux 배포 가이드](LINUX-DEPLOYMENT.md), 합성 장애 시험은 [재시작](QUEUE-VALIDATION.md), [동시 실행](WORKER-CONCURRENCY-VALIDATION.md), [DB 장애 복구](DB-RECOVERY-VALIDATION.md), [누적 리뷰 업데이트·복귀](UPGRADE-VALIDATION.md) 문서와 함께 따른다. 실제 Git 계정, AI 키, 운영 DB, 운영 인증서는 사용하지 않는다.

## 1. 전용 WSL2 배포판 준비

대상 배포판 이름은 `ai-reviewer-validation`, Ubuntu 버전은 `Ubuntu-24.04`로 고정한다. 기존 배포판이나 기본 배포판 설정을 변경하지 않는다. Ubuntu 24.04의 현재 WSL 이미지 형식에는 WSL 2.4.10 이상이 필요하며, 가상화 기능을 켠 뒤 Windows 재부팅이 필요할 수 있다. [Canonical 설치 안내](https://ubuntu.com/wsl/docs/latest/howto/install-ubuntu-wsl2/)

최초 구성에 Windows 재부팅이 필요하면 사용자가 진행한다. 이번 전용 배포판은 재부팅 적용 후 등록·실행을 확인했다. 재개 시에는 Windows PowerShell에서 기존 설치 상태를 먼저 확인한다.

```powershell
wsl --version
wsl --status
wsl --list --verbose
wsl --list --online
```

전용 배포판이 목록에 없을 때만 아래 설치를 다시 실행한다. 다른 이름의 Ubuntu가 있다는 이유로 해당 배포판을 재사용하지 않는다. 설치 경로가 이미 있으면 기존 내용과 이번 설치의 소유 여부를 확인하며 삭제하거나 덮어쓰지 않는다.

```powershell
wsl --install Ubuntu-24.04 --name ai-reviewer-validation --no-launch --web-download --location D:\ai-reviewer\.local\wsl\ai-reviewer-validation
wsl --list --verbose
wsl --distribution ai-reviewer-validation
```

배포판 목록의 `VERSION`이 `2`인지 확인하고 초기 설정에서 검증 전용 일반 Linux 사용자를 만든다. 명령 종료 코드가 0이어도 재부팅 안내만 나왔다면 배포판 설치 완료로 기록하지 않는다. 설치 옵션은 설치된 WSL의 `wsl --help`와 [Microsoft 명령 참조](https://learn.microsoft.com/en-us/windows/wsl/basic-commands)를 기준으로 확인한다.

전용 배포판 안에서 `/etc/os-release`의 Ubuntu 24.04, `uname -r`, `id -u`, PID 1의 프로세스 이름과 `systemctl` 동작을 확인한다. 일반 검증 셸의 UID는 0이면 안 된다. 최신 Ubuntu WSL의 기본 설정을 먼저 확인하고, systemd가 비활성일 때만 기존 `/etc/wsl.conf`를 보존하면서 `[boot]`의 `systemd=true`를 설정한다. [Microsoft systemd 안내](https://learn.microsoft.com/en-us/windows/wsl/systemd)

설정 적용을 위해 재시작해야 하면 이 배포판의 소유 서비스와 시험 프로세스를 먼저 정상 중지한 뒤 `wsl --terminate ai-reviewer-validation`을 사용한다. `wsl --shutdown`은 다른 배포판도 중단하므로 이 절차의 기본 명령으로 사용하지 않는다. Windows 재부팅과 WSL 전체 종료를 자동화하지 않는다.

## 2. 파일과 계정 격리

소스·빌드·DB는 모두 전용 배포판의 Linux 파일시스템에 둔다. 예시 검증 루트는 `/home/<검증사용자>/work/ai-reviewer`다. Microsoft도 Linux 도구로 작업하는 파일은 `/mnt/c` 같은 Windows 마운트보다 Linux 홈 아래에 둘 것을 권장한다. [WSL 파일시스템 안내](https://learn.microsoft.com/en-us/windows/dev-environment/wsl-interop)

| 대상 | 허용 위치·처리 |
|---|---|
| 소스 | 검증할 승인 커밋의 별도 checkout 또는 검토한 소스 파일만 Linux 홈으로 복사 |
| Maven 결과 | Linux checkout의 `source/target`, Linux 사용자의 Maven 저장소에서 새로 생성 |
| 검증 PGDATA | Linux checkout 안에 Linux 부모 검증 도구가 생성한 전용 `.local` 경로 |
| 임시 로그·보고서·백업 | Linux checkout의 `.local` 아래, 운영 자료와 구분 |
| 서비스용 WAR·Java | 실제 systemd 단계에서 root가 관리하는 `/opt/ai-reviewer` 아래에 배치 |
| 시험 환경파일·TLS 키 | 전용 배포판의 제한된 디렉터리, Git·후보 패키지에 포함하지 않음 |

Windows의 `D:\ai-reviewer\.local\pg-validation`을 `/mnt/d`로 열어 Linux PostgreSQL에 사용하지 않는다. Windows `source/target`, `.m2`, 기존 비밀 환경파일, `.local` 전체도 복사·공유하지 않는다. 배포판 가상 디스크를 `D:\...\wsl\...`에 저장하는 것과 PostgreSQL이 `/mnt/d`의 Windows 파일을 직접 사용하는 것은 다르다. 배포판 내부의 Linux 경로에서 새 DB를 초기화한다.

복사 시에는 확정 커밋의 추적 파일만 대상으로 삼고, 아직 커밋하지 않은 검증 도구가 필요하면 해당 파일 목록과 해시를 별도로 기록한다. Windows 작업 폴더 전체 복사는 사용하지 않는다. 기존 변경은 원래 checkout에 보존한다. 이번 재현에 필요한 저장소 원격 인증이나 운영 비밀은 Linux 환경에 반입하지 않는다.

관리자 권한은 패키지 설치와 별도 systemd/Nginx 시험 배치에만 사용한다. Maven, Python 검증, `initdb`, 검증용 `pg_ctl`은 일반 사용자로 실행한다. PostgreSQL은 서버 데이터의 소유 사용자로 초기화해야 하며 `initdb`를 root로 실행할 수 없다. [PostgreSQL 17 initdb](https://www.postgresql.org/docs/17/app-initdb.html)

## 3. Java 25와 PostgreSQL 17 설치 조건

공식 저장소 키와 서명 검증을 구성한 뒤, 관리자만 필요한 패키지를 설치한다. 배포판 기본 `default-jdk`나 버전 없는 `postgresql` 패키지가 프로젝트 버전을 선택한다고 가정하지 않는다.

| 구성 요소 | 공식 설치 경로·선택 |
|---|---|
| Java | Adoptium의 Ubuntu `noble`용 DEB 저장소, `temurin-25-jdk` |
| PostgreSQL | PGDG의 `noble-pgdg` 저장소, `postgresql-17`, `postgresql-client-17` |
| 도구 | Bash, Python 3, Git, CA 인증서, curl, unzip, OpenSSL, Nginx |

Adoptium의 공식 DEB 저장소 및 서명 키 설정을 따른다. `temurin-25-jdk`의 설치된 실제 패치 버전과 공급자를 기록한다. [Adoptium Linux 패키지 안내](https://adoptium.net/installation/linux)

PGDG는 Ubuntu 24.04 `noble`을 지원한다. 공식 안내의 저장소 서명 키와 `Signed-By` 설정을 사용하고, 설치 대상은 프로젝트 버전인 17로 명시한다. 패키지 설치가 기본 클러스터를 만들었더라도 이를 시험 DB로 재사용하거나 임의 중지하지 않는다. 검증 도구는 별도 데이터 디렉터리와 빈 loopback 포트의 클러스터만 소유한다. [PostgreSQL Ubuntu 패키지 안내](https://www.postgresql.org/download/linux/ubuntu/)

설치 후에는 Linux `java`, `javac`, Python, `/usr/lib/postgresql/17/bin`의 PostgreSQL 도구가 실제로 선택되는지 확인한다. `java.exe`·`psql.exe` 등 Windows 실행 파일이 PATH를 통해 선택되면 진행하지 않는다. Java 25, PostgreSQL 서버/클라이언트 17, Bash/Python 버전과 실행 경로를 기록한다. Maven은 저장소의 `source/mvnw`를 일반 사용자로 사용하며 Windows 빌드 결과를 재사용하지 않는다.

## 4. 합성 자동 검증 순서

Linux용 부모 도구는 `scripts/test-postgres-linux.py`다. 이번에는 모의 검사와 실제 Linux 실행을 모두 수행했으며 Windows `scripts/test-postgres.ps1`의 결과와 구분한다. 검토된 소스를 Linux 파일시스템에 옮긴 뒤, 검증 루트에서 일반 사용자로 실행할 명령은 다음과 같다. `--java`는 설치 후 확인한 Java 25의 절대 경로로 바꾼다.

```bash
python3 scripts/test-postgres-linux.py \
  --pg-bin /usr/lib/postgresql/17/bin \
  --java /absolute/jdk-25/bin/java \
  --port 55439 \
  --backup-restore \
  --review-restart \
  --review-concurrency \
  --review-database-recovery
```

부모 도구는 checkout의 `.local/pg-validation`만 제어하고 Linux 전용 마커를 요구한다. Windows 클러스터나 미확인 기존 경로는 삭제·초기화하지 않고 거부한다. 로그와 고유 child 보고서는 `.local/linux-postgres-<UUID>` 아래에 둔다. 기본 WAR 포트는 재시작18089, 동시 실행18090/18091, DB 복구18092다. 충돌하면 각각 `--review-restart-port`, `--review-concurrency-port-a`, `--review-concurrency-port-b`, `--review-database-recovery-port`로 비어 있는 포트를 명시한다. `--backup-restore`는 `scripts/verify-postgres-backup.py`를 호출한다. 이 도구는 원본을 읽기 전용으로 백업하고 고유한 새 DB에 복원하며, DB OID·소유자·실행 표식을 확인한 뒤 자신이 만든 복원 DB만 삭제한다. dump는 `.local/postgres-backup-<UUID>`에 보관한다.

기존 `scripts/verify-ci.sh`는 이미 준비된 두 DB에 대해 Maven 검증과 보고서 검사를 수행한다. PostgreSQL 생성·기동·정리를 맡는 도구는 아니다. 이 스크립트에는 같은 loopback 포트의 `reviewer_integration`, `identity_security`, 합성 사용자 `reviewer_test`, 비어 있지 않은 시험 비밀번호가 필요하다. 반면 Linux 부모 도구는 전용 loopback 클러스터의 trust 인증·빈 시험 비밀번호를 사용하고 Maven 및 보고서 게이트를 직접 실행하므로 `verify-ci.sh`를 다시 호출하지 않는다. 이 합성 인증 설정은 서비스 배포에 복사하지 않는다. 환경변수 값은 명령 출력이나 보고서에 노출하지 않는다.

다음 순서로 증거를 수집한다. 부모 도구의 통합 명령은 전체 Maven 검증과 필수 PG 보고서 게이트를 통과한 뒤 선택한 백업·복원 및 WAR drill을 실행한다. 선택하지 않은 검증을 통과 건수에 포함하지 않는다.

1. **오프라인 도구 확인:** Linux에서 Python 안전 회귀와 Bash 문법 검사를 수행한다. OS 의존으로 건너뛴 시험은 이름·이유를 별도로 남기고 성공 건수에 합치지 않는다.
2. **실제 PostgreSQL + WAR 빌드:** Linux 부모 도구가 만든 전용 클러스터에서 전체 Maven 검증과 필수 PG 보고서 게이트를 실행한다. 단순 H2 성공, 컴파일 성공과 구분한다. WAR SHA-256과 소스 기준 커밋을 남긴다.
3. **백업·복원:** 합성 DB를 백업하고 새로 생성한 별도 DB에 복원한다. 테이블·외래 키·Flyway·식별자 시퀀스와 복원 후 삽입을 확인한다. 원본 시험 DB를 복원 대상으로 덮어쓰지 않는다.
4. **실제 WAR 재시작:** `verify-review-restart.py`의 loopback GitLab/Ollama fixture로 저장된 A 커밋 재사용, B 재실행, 동일 요청·행위자·진행 상태와 중복 방지를 확인한다.
5. **두 WAR 동시 실행:** `verify-review-concurrency.py`로 느린 프로젝트를 유지한 상태에서 다른 프로젝트 진행과 동일 프로젝트 잠금 경쟁을 검증한다. 외부 AI의 과금상 exactly-once를 증명하는 시험은 아니다.
6. **DB 중지·재개:** `verify-review-db-recovery.py`를 부모 도구를 통해 실행한다. 준비 확인 503·생존 확인 200, DB 복구 후 요청 진행, 저장 SHA 재사용을 확인한다. 부모가 시작한 클러스터의 경로·마커·PID·시작 시각이 일치할 때만 제어하고, 소유권 불일치 시 중지하지 않는다.

각 fixture는 합성 커밋·계정과 loopback HTTP만 사용한다. 공개 Git smoke, 로컬 모델 smoke, AI 평가와 부하 opt-in은 기본 비활성으로 유지한다. 의존성·패키지 다운로드는 설치/빌드 통신이며, 실제 Git 소스 리뷰·유료 AI 호출과 구분한다. DB 제어는 하나의 부모 실행이 소유하고, 동일 클러스터를 다른 검증 프로세스가 동시에 제어하지 않는다.

부모의 Ctrl-C·SIGTERM은 소유권 확인을 거치는 정리로 연결한다. Python 하위 검증은 먼저 SIGINT로 정상 정리 기회를 주고 제한 시간 이후에만 해당 실행의 프로세스 그룹을 종료한다. 임시 Python 자식·손자를 사용한 실제 Linux 신호 시험 4건에서 협력 정리·원래 실패 보존·손자 생존·제한 후 강제 종료·main의 finally 정리를 확인했다. 이는 실제 PG/WAR 실행 중 취소의 모든 시점을 시험했다는 뜻은 아니다. 강제 종료·SIGKILL·전원 종료나 정리 시간 초과에서는 DB·schema가 남을 수 있다. 다음 실행은 남은 lock/PID를 임의 삭제하지 않고 거부하므로, 이전 로그·보고서와 실제 프로세스 소유권을 대조한 뒤 복구한다.

백업은 PostgreSQL의 [SQL dump 절차](https://www.postgresql.org/docs/17/backup-dump.html)와 [pg_restore](https://www.postgresql.org/docs/17/app-pgrestore.html)를 따른다. 시험 중 DB 중지는 소유 클러스터만 명시한 [pg_ctl](https://www.postgresql.org/docs/17/app-pg-ctl.html)로 수행한다. 중지·재시작의 timeout과 로그가 있어야 하며, 실행 실패 후 임의의 새 PID를 자신의 서버로 채택하지 않는다.

## 5. 실제 systemd·Nginx/TLS 검증

프로세스를 직접 시작하는 Python fixture 성공만으로 systemd·TLS 검증을 통과 처리하지 않는다. 아래는 앞 단계에서 만든 WAR와 합성 DB를 사용하는 별도 설치 시험의 절차다. 이번에 수행한 항목은 뒤의 실제 검증 기록을 따른다. [Linux 배포 가이드](LINUX-DEPLOYMENT.md)의 파일 권한과 설정을 적용한다.

1. 신뢰하는 빌드 기록의 WAR 해시로 `deploy/linux/verify-artifact.sh`를 실행한다. 검증된 WAR와 Java를 `/opt`에 배치하고, 제한된 서비스 계정과 root 전용 환경파일을 만든다. 서비스의 `ProtectHome=yes`를 해제해 홈 아래 Java에 접근시키지 않는다.
2. `systemd-analyze verify`로 복사한 단위를 확인하고 실제 `systemctl start/status`와 journal을 확인한다. `Type=exec` 시작 성공과 앱 준비 완료를 구분한다. JSP 임시 컴파일 경로, 비root UID, WAR·환경파일 쓰기 제한을 실제 HTTP 요청과 파일 권한으로 확인한다.
3. 첫 기동에는 예약·작업 처리를 끈다. 내부 loopback에서 생존/준비 확인의 상태 전용 응답과 익명 사용자의 관리자 metrics 접근 차단만 확인한다. 세션을 사용하는 검증은 HTTPS 구성 뒤에 진행한다.
4. Nginx에 검토한 설정을 복사하고 `nginx -t` 후 기동한다. 합성 전용 CA와 SAN이 맞는 짧은 수명의 시험 인증서로 TLS를 검증한다. `curl --resolve`와 `--cacert`로 이름·신뢰 검증을 수행하며, `-k`로 우회한 접속은 TLS 통과 증거로 사용하지 않는다. 공개 도메인 등록이나 운영 키를 요구하지 않는다.
5. HTTPS에서 합성 계정 로그인·로그아웃·승인·CSRF, worker OFF 안내와 수동 요청 보존을 확인한다. HTTPS 리다이렉트, Secure 세션 쿠키, 일반 사용자의 관리자 기능 접근 차단, forwarded header 재작성, 프록시의 `/actuator` 차단도 확인한다. Nginx가 actuator를 차단하므로 생존/준비 확인은 앱 내부 포트로 수행한다. [Ubuntu Nginx 안내](https://ubuntu.com/server/docs/how-to/web-services/install-nginx/), [Nginx HTTPS 설정](https://nginx.org/en/docs/http/configuring_https_servers.html)
6. 합성 fixture와 연결한 작업 처리를 켜고 systemd 정상 중지·재시작 후 요청·진행 상태·DB 보존을 확인한다. 새 DB로 백업을 복원한 별도 앱을 작업 처리 OFF로 기동해 화면과 데이터가 복구되는지도 확인한다. 원본 DB를 삭제하거나 이전 스키마로 덮어쓰지 않는다.

HTTP fixture는 자체 시험 설정을 사용하지만 HTTPS 설치 시험의 `SESSION_COOKIE_SECURE=true`를 낮추지 않는다. WSL의 NAT/미러링과 Windows 포트 충돌을 확인하고 loopback 접근부터 검증한다. 전체 인터페이스 개방이나 광범위한 방화벽 예외를 이 계획의 전제로 두지 않는다. [Microsoft WSL 네트워크 안내](https://learn.microsoft.com/en-us/windows/wsl/networking)

WSL에서 systemd가 동작해도 서비스만으로 WSL 인스턴스가 계속 살아 있는 것은 아니다. Windows 절전·재부팅과 실제 운영 Linux의 부팅·네트워크·자원 조건도 다르다. 따라서 여기서 통과한 결과는 해당 WSL 환경의 재현 결과로 기록하고, 운영 서버의 지속 실행·외부 TLS·실제 모델 품질 검증을 완료했다고 표현하지 않는다. [Microsoft systemd 설명](https://learn.microsoft.com/en-us/windows/wsl/systemd)

## 2026-10-02 실제 검증 기록

이전 회차의 `.local/session7-wsl-preflight.json`은 WSL 구성요소 설치 직후 재부팅을 기다리던 당시의 기록이다. 이번에는 재부팅 적용과 전용 배포판 등록 후 아래 환경에서 실행했다. 비밀값을 포함하는 환경 덤프는 기록하지 않는다.

| 항목 | 확인 결과 |
|---|---|
| WSL | 3.0.1.0, 전용 `ai-reviewer-validation`, WSL2 |
| Linux | Ubuntu 24.04.5, 커널 `6.18.40.1-microsoft-standard-WSL2` |
| Java / PostgreSQL | Temurin `25.0.4.1+1` / `17.11-1.pgdg24.04+2` |
| Python / systemd | Python 3.12.3 / systemd 255.4 |
| 일반 검증 사용자 | `reviewer`, UID1000 |
| 소스·검증 데이터 | `/home/reviewer/work/ai-reviewer`, Linux ext4의 독립 소스·PGDATA |
| 소스 기준 | `git archive d4a2352`와 이번 회차에 명시적으로 반입한 변경; 단일 확정 커밋의 빌드로 표시하지 않음 |

### Linux 전체 빌드와 합성 복구

최종 부모 실행의 증거 디렉터리는 Linux checkout의 `.local/linux-postgres-731ff1115c754dbdbc0c7a7dab3875ac`다. `clean verify`, 필수 PG 보고서 게이트, 백업·복원, 세 WAR 검증을 모두 통과했다.

| 검증 | 실제 결과 |
|---|---|
| Java 전체 | 906건 중896통과·10skip(외부7·부하3), 실패/오류0 |
| 필수 PostgreSQL suite gate | PASS |
| Python — Linux | 171건 중161통과·Windows PowerShell 전용10skip |
| Python — Windows | 171건 중166통과·POSIX 전용5skip |
| 실제 Linux 취소 신호 | 임시 Python 프로세스 시험4건 PASS; 위 Linux 집계에 포함 |
| 백업·새 DB 복원 | 10개 테이블·identity/queue 삽입·원본 읽기 전용·소유 복원 DB 삭제 PASS |
| WAR 강제 종료·재시작 | PASS, 53.331초 |
| 두 WAR 동시 실행 | PASS, 21.974초 |
| 동일 WAR의 DB 중지·재개 | PASS, 46.080초 |

최종 Linux WAR SHA-256은 `21b174681c22111919c408361747da3320c683dd041231811c5684ba9128f59e`다. 위 시간은 해당 로컬 실행의 관찰값이며 운영 처리량·복구 시간 보장이 아니다.

중간 실행에서 백업 DB OID의 JSON 문자열 표현을 정수로 가정한 문제가 드러나 SQL에서 bigint로 변환하도록 수정했다. 별도 통합 실행에서는 JDBC 취소 시험의 소켓 제한3초가 관측 한도5초보다 먼저 세션을 닫으며 소켓 예외로 실패했다. 해당 시험만 소켓 제한6초로 조정하고 쿼리 제한1초·`pg_sleep(10)`·5초 미만·SQLState `57014` 조건을 유지했으며, 취소 전후 같은 backend PID인지 추가 검증했다. 수정 후 최종 전체 실행이 통과했다. 이 기록만으로 부하 등 실패의 근본 원인을 확정하지 않는다.

### 별도 systemd·HTTPS·복원 서비스

서비스 시험에는 먼저 빌드한 WAR SHA-256 `35325503bc4e6605c91bb7874b3fa1213f4c5ca172b920337031a875480b3067`을 사용했다. 최종 WAR는 JDBC 시험 변경 후 다시 빌드한 것이며 제품 Java 코드는 동일하다. 두 WAR의 해시와 시험 범위를 서로 바꾸어 기록하지 않는다.

서비스용 PostgreSQL17 클러스터 `reviewer_service`는 포트55449에서 SCRAM 인증을 사용했다. 앱 DB 계정의 클러스터 특권5종이 모두 false임을 확인했다. 앱은 전용 OS 계정으로 실행하며 환경파일은 root 소유0600, WAR·Java는 앱 계정 쓰기 불가, state/runtime 경로는 필요한 쓰기 가능 상태를 확인했다. Flyway V13 적용과 JSP/HTTP 동작도 통과했다.

| 검증 | 실제 결과 |
|---|---|
| HTTPS 초기 사용자 흐름 | 가입·승인·로그인·대기 요청 등15개 확인 PASS |
| bootstrap 제거·서비스 재시작 | 기존 관리자 재로그인 등9개 확인 PASS |
| 전달 헤더 위조 | HTTPS 공통5개와 IP quota1개 확인 PASS |
| systemd 강제 종료 후 자동 복구 | SIGKILL 후17.608초, 복구 후 HTTPS9개 확인 PASS |
| systemd 정상 중지 | 정상 종료143을 실패로 표시하던 단위에 `SuccessExitStatus=143` 반영 후 `inactive`·`Result=success` 확인 |
| 서비스 DB 백업·별도 앱 복원 | 새 UUID DB와 별도 WAR 포트8081에서 HTTPS9개 확인 PASS; 소유권 확인 후 새 복원 DB만 삭제, 원본 보존 |

HTTPS는 loopback의 `reviewer.test`와 합성 CA/SAN 인증서로 검증했다. 인증서 이름·신뢰 검증을 수행했으며 `-k`를 사용하지 않았다. 시험 인증서 수명은7일이고 OS 신뢰 저장소에 추가하지 않았다. 실제 모델·외부 Git 서비스·운영 인증서는 사용하지 않았다.

서비스 시험에서는 bootstrap 값을 제거한 뒤에도 worker와 scheduler를 모두 OFF로 유지했다. 따라서 위 서비스 결과를 systemd에서의 예약 리뷰·실제 모델 처리 성공으로 해석하지 않는다. 새 DB 복원 앱 역시 처리를 OFF로 유지했다. 서비스 자동 시작을 `enable`하지 않았다.

WSL은 systemd 서비스만으로 배포판 실행을 유지하지 않아 이번 실행이 소유한 foreground keeper를 사용했다. 시험 hostname 보존을 위해 전용 배포판에 `generateHosts=false`와 전용 hosts 매핑을 적용했다. 이 준비는 운영 Linux의 부팅 검증을 대체하지 않는다. 세션 끝의 서비스·클러스터·keeper·시험 파일 정리 결과는 [WORK.md](../WORK.md)에 별도로 기록하며, 위 시험 통과만으로 전체 정리가 끝났다고 간주하지 않는다.

### 후보 설치·V12→V13 업데이트·백업 복귀

소스 기록 `f623fd27e4fa5a2fb05a43dfb33f287059ca09ad`와 위 최종 WAR로 Linux 후보를 두 번 생성해 동일한 압축 바이트를 확인했다. 아카이브 SHA-256은 `eba79eb01888675584525424aa29d023936b65850bfbd43ed9bf1bef53aec03e`다. 추출 전에 독립 실행 기록의 해시·23개 정규 파일·내부 전체 체크섬·고정 소유자/권한/시간·manifest·WSL 문서 포함을 확인하고 전용 `/opt/ai-reviewer/releases/wsl-session8-final`에 설치했다. 소스 식별자는 도구의 운영자 제공 기록이며 WAR 빌드 서명이나 릴리스 승인은 아니다.

구버전 기준 `ec05f9b1c43479555377a97bc7217c159e5fddaa`는 별도 Linux 소스 디렉터리에서 `-DskipTests package`로 리허설용 WAR를 만들었다. 이 명령을 구버전 전체 테스트 통과로 세지 않는다. 구 WAR 해시는 `783971202a6f1ccc9d45580f1308f2a2ad8ac040dd8ddea4bb0d70f8949da57c`다. 원본 서비스 DB와 별개인 새 UUID DB, 임시 systemd 단위와 포트8082에서 다음을 확인했다.

1. 구 WAR로 V1~V12를 적용하고 HTTPS 가입·관리자 승인·프로젝트 승인·QUEUED 요청 등15개 확인을 통과했다.
2. 앱을 중지하고9개 업무 테이블 행의 합산 해시와 V1~V12 migration 기록(checksum 포함)의 해시를 기록한 뒤 root0600 custom dump를 만들었다.
3. 같은 시험 DB에 최종 후보 WAR를 기동해 V13을 적용했다. HTTPS 기존 계정·프로젝트·요청 확인9개, 기존9개 업무 테이블의 행 지문과 V1~V12 checksum 보존, 새 진행 정보3개 열을 검증했다.
4. 다시 중지한 뒤 **업데이트 전 백업을 또 다른 새 UUID DB로 복원**하고 구 WAR로 기동했다. HTTPS9개와 정확한 V1~V12·기존 업무 행 보존을 확인했다. V13 DB를 역방향 migration하지 않았다.
5. 앱을 중지하고 DB OID·owner·실행 표식·클러스터 동일성·연결0을 대조해 시험 DB2개만 제거했다. 원본 `ai_reviewer`의10개 테이블 지문과 DB 식별자는 전후 일치했다.

이 리허설의 worker·scheduler는 OFF이고 저장된 `review_run`은0행이다. 실행 중 리뷰나 축적된 리뷰 결과를 포함한 모든 업그레이드 경로를 입증하지 않는다. 관련 데이터 보존 회귀와 운영 서버의 실제 migration 정책은 별도로 유지한다. DB 증거는 `/var/lib/ai-reviewer-validation/be8791ed11c54c5485151331f74d3fda/upgrade-record.json`, HTTPS 결과는 Linux checkout의 `.local/service/upgrade/https-{old,final,rollback}.json`에 있다.

최종 정리에서 원본/복원/업데이트 앱, Nginx, 서비스용PG와 부모 검증PG를 모두 중지했다. Nginx 자동 시작을 해제하고 loopback11개 시험 포트의 연결 불가·검증 lock 부재·소유 복원 DB 부재를 확인했다. 원본 합성 DB, 비밀 설정과 백업은 전용 WSL의 제한된 경로에 보존한다. 정리 요약은 `.local/service/session8-validation-summary.json`에 기록한다.

### 저장 리뷰·중단 요청의 업데이트·백업 복귀

2026-10-03에는 `--backup-restore --review-upgrade`로 저장된 A 커밋과 처리 중 B 요청을 실제 구 WAR에서 만들고 강제 종료한 뒤, V13 및 별도 새 DB의 V12 백업 복원본에서 각각 재개했다. SQL로 추가한 과거 AI/수동 이슈·근거·사유·감사 자료의 보존과 화면 escaping, 새 claim·시도별 진행값, A AI1회/B AI3회, 정확한 이슈/체크포인트를 확인했다. WAR6회 시작65.283초, 원본10테이블 보존·소유 DB2개 제거·부모PG 종료까지 통과했다. 전체 Java907건 중897통과/선택10skip이며 필수PG gate도 통과했다. [명령·해시·데이터 생성 구분·한계](UPGRADE-VALIDATION.md)에 세부 증거를 기록했다. 이 시험은 직접 시작한 WAR의 loopback HTTP이며 앞의 systemd/TLS 시험과 구분한다.

### V14 로그인·가입 제한 공유

2026-10-03 V14 구현 후 전체 Java924건 중914통과/외부·선택 부하10skip, 필수PG5suite gate를 통과했다. 실제 PG 동시성14건을 포함하며 기존 예약·업무 데이터 테스트도 유지했다. Linux `.local/linux-postgres-9be02529b7c5458c9670100d1fc6655d`에서12테이블 백업/복원과 저장 리뷰가 있는 V12→V14·새 DB V12복원 후 구 WAR 재개를74.763초/WAR6회 시작으로 통과했다. [업데이트 기록](UPGRADE-VALIDATION.md)의 해시와 범위를 따른다.

이어서 `--shared-auth` 부모 실행에서 같은924건/필수PG gate를 다시 통과하고 두 실제 WAR의 계정10회·가입10회·IP100회 공유, 양쪽429·B 강제 재시작 유지, 저장소 오류 주입 시 양쪽503·복구 후 기존 제한 유지·만료 뒤 정상 요청을60.882초에 확인했다. A1회/B2회 시작이며 소유 WAR/schema·부모PG/lock 정리를 확인했다. 최종 WAR SHA256은 `4053cf49efb714462e93c5acd2baffb738f1d64d41bd11daa716380734237d5c`,42,008,768바이트다. 앞선 업그레이드 WAR와 제품 소스는 같지만 재빌드 바이트/해시는 구분한다.

후속 SIGTERM 시험은 B 재시작 뒤 가입 중 취소,28.676초·FAIL/종료130 유지·기존12테이블 보존·시험 자원 정리를 통과했다. 전체 Python217건은 Linux207통과/Windows전용10skip, Windows212통과/POSIX5skip다. 보고서·재현 명령·SQL 주입과 순차 HTTP의 한계는 [공유 제한 검증](AUTH-LIMITING.md)에 기록했다. 이번에는 별도 systemd/TLS 서비스에 V14를 설치하지 않았다.

### V15 외부 Git·AI 호출 제한 대기

2026-10-07 최종 Java1029건 중1019통과/선택10skip·필수PG6suite를 통과했다. 앞선 V15 빌드의14테이블 백업/복원과 V12→V15·새 DB V12복귀는66.970초/WAR6회로 통과했다. 최종 WAR의 합성Git/AI429·같은origin AI무호출·재시작·저장SHA 재사용/UTC JSP는39.774초/WAR3회로 통과했다. 별도 실제SIGTERM23.190초/종료130은 원본14테이블 보존과 소유 자원·부모PG 정리를 확인했다.

최종 WAR SHA256은 `1de2c1ec5092cf4ce6ec83dde5c429cf813651648d2adaa968ae37977d66e604`,42,029,853바이트다. Python237건은 Windows232통과/5skip, Linux227통과/10skip였다. 재현 명령·보고서·SQL 시간 가속/예약OFF 합성종료 안내의 한계는 [호출 제한 검증](EXTERNAL-RATE-LIMITS.md)을 따른다. systemd/TLS 서비스에 이 WAR를 설치하거나 실제 공급자·유료 모델을 호출하지 않았다.

### 남은 검증

운영 Linux 서버의 실제 부팅·지속 실행·공개 네트워크/TLS와 인증서 갱신, 운영 데이터 규모의 업그레이드·rollback, 백업 암호화·보존·RPO/RTO, 실제 GitLab/LiteLLM 인증과 모델 품질, 장시간 부하·다중 인스턴스 정책 검증은 남아 있다. 이번 WSL 합성 환경의 통과는 이 항목이나 릴리스 승인을 대신하지 않는다.
