# WSL2 Linux 검증 환경과 재현 순서

작성 기준: 2026-10-01. Ubuntu 24.04 LTS의 전용 WSL2 배포판에서 Java 25, PostgreSQL 17, 실행 WAR를 Docker 없이 검증하기 위한 계획이다. **현재 Ubuntu 등록과 Linux 검증은 미실행 상태다.** 아래 명령은 재부팅 후 진행할 절차이며, 문서 작성 중 실행한 명령이 아니다. 기존 Windows 검증 통과를 Linux 결과로 옮겨 적지 않는다.

앱 설치·운영 절차는 [Linux 배포 가이드](LINUX-DEPLOYMENT.md), 합성 장애 시험은 [재시작](QUEUE-VALIDATION.md), [동시 실행](WORKER-CONCURRENCY-VALIDATION.md), [DB 장애 복구](DB-RECOVERY-VALIDATION.md) 문서와 함께 따른다. 실제 Git 계정, AI 키, 운영 DB, 운영 인증서는 사용하지 않는다.

## 1. 전용 WSL2 배포판 준비

대상 배포판 이름은 `ai-reviewer-validation`, Ubuntu 버전은 `Ubuntu-24.04`로 고정한다. 기존 배포판이나 기본 배포판 설정을 변경하지 않는다. Ubuntu 24.04의 현재 WSL 이미지 형식에는 WSL 2.4.10 이상이 필요하며, 가상화 기능을 켠 뒤 Windows 재부팅이 필요할 수 있다. [Canonical 설치 안내](https://ubuntu.com/wsl/docs/latest/howto/install-ubuntu-wsl2/)

Windows 재부팅은 사용자가 진행한다. 재부팅 뒤 Windows PowerShell에서 설치 상태를 먼저 확인한다.

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

## 3. Java 25와 PostgreSQL 17 설치 계획

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

Linux용 부모 도구 초안은 작업 트리의 `scripts/test-postgres-linux.py`다. 현재 모의 검증 단계이며 실제 Linux 실행 전이라 커밋하지 않았다. **Windows `scripts/test-postgres.ps1`의 성공을 이 도구의 Linux 결과로 대체하지 않는다.** 검토된 소스를 Linux 파일시스템에 옮기고 도구 검토가 끝난 뒤, 검증 루트에서 일반 사용자로 실행할 명령은 다음과 같다. `--java`는 설치 후 확인한 Java 25의 절대 경로로 바꾼다.

```bash
python3 scripts/test-postgres-linux.py \
  --pg-bin /usr/lib/postgresql/17/bin \
  --java /absolute/jdk-25/bin/java \
  --port 55439 \
  --review-restart \
  --review-concurrency \
  --review-database-recovery
```

부모 도구는 checkout의 `.local/pg-validation`만 제어하고 Linux 전용 마커를 요구한다. Windows 클러스터나 미확인 기존 경로는 삭제·초기화하지 않고 거부한다. 로그와 고유 child 보고서는 `.local/linux-postgres-<UUID>` 아래에 둔다. 기본 WAR 포트는 재시작18089, 동시 실행18090/18091, DB 복구18092다. 충돌하면 각각 `--review-restart-port`, `--review-concurrency-port-a`, `--review-concurrency-port-b`, `--review-database-recovery-port`로 비어 있는 포트를 명시한다. 부모 도구의 현재 범위에는 백업·복원 옵션이 없으므로 아래 백업 검증은 별도 실행·증거가 필요하다.

기존 `scripts/verify-ci.sh`는 이미 준비된 두 DB에 대해 Maven 검증과 보고서 검사를 수행한다. PostgreSQL 생성·기동·정리를 맡는 도구는 아니다. 이 스크립트에는 같은 loopback 포트의 `reviewer_integration`, `identity_security`, 합성 사용자 `reviewer_test`, 비어 있지 않은 시험 비밀번호가 필요하다. 반면 Linux 부모 도구는 전용 loopback 클러스터의 trust 인증·빈 시험 비밀번호를 사용하고 Maven 및 보고서 게이트를 직접 실행하므로 `verify-ci.sh`를 다시 호출하지 않는다. 이 합성 인증 설정은 서비스 배포에 복사하지 않는다. 환경변수 값은 명령 출력이나 보고서에 노출하지 않는다.

다음 순서로 증거를 수집한다. 부모 도구의 통합 명령은 전체 Maven 검증 후 선택한 WAR drill을 실행한다. 별도 백업·복원 검증이 아직 없으면 그 항목은 미실행으로 남긴다.

1. **오프라인 도구 확인:** Linux에서 Python 안전 회귀와 Bash 문법 검사를 수행한다. OS 의존으로 건너뛴 시험은 이름·이유를 별도로 남기고 성공 건수에 합치지 않는다.
2. **실제 PostgreSQL + WAR 빌드:** Linux 부모 도구가 만든 전용 클러스터에서 전체 Maven 검증과 필수 PG 보고서 게이트를 실행한다. 단순 H2 성공, 컴파일 성공과 구분한다. WAR SHA-256과 소스 기준 커밋을 남긴다.
3. **백업·복원:** 합성 DB를 백업하고 새로 생성한 별도 DB에 복원한다. 테이블·외래 키·Flyway·식별자 시퀀스와 복원 후 삽입을 확인한다. 원본 시험 DB를 복원 대상으로 덮어쓰지 않는다.
4. **실제 WAR 재시작:** `verify-review-restart.py`의 loopback GitLab/Ollama fixture로 저장된 A 커밋 재사용, B 재실행, 동일 요청·행위자·진행 상태와 중복 방지를 확인한다.
5. **두 WAR 동시 실행:** `verify-review-concurrency.py`로 느린 프로젝트를 유지한 상태에서 다른 프로젝트 진행과 동일 프로젝트 잠금 경쟁을 검증한다. 외부 AI의 과금상 exactly-once를 증명하는 시험은 아니다.
6. **DB 중지·재개:** `verify-review-db-recovery.py`를 부모 도구를 통해 실행한다. 준비 확인 503·생존 확인 200, DB 복구 후 요청 진행, 저장 SHA 재사용을 확인한다. 부모가 시작한 클러스터의 경로·마커·PID·시작 시각이 일치할 때만 제어하고, 소유권 불일치 시 중지하지 않는다.

각 fixture는 합성 커밋·계정과 loopback HTTP만 사용한다. 공개 Git smoke, 로컬 모델 smoke, AI 평가와 부하 opt-in은 기본 비활성으로 유지한다. 의존성·패키지 다운로드는 설치/빌드 통신이며, 실제 Git 소스 리뷰·유료 AI 호출과 구분한다. DB 제어는 하나의 부모 실행이 소유하고, 동일 클러스터를 다른 검증 프로세스가 동시에 제어하지 않는다.

부모의 Ctrl-C·SIGTERM은 소유권 확인을 거치는 정리로 연결한다. Python 하위 검증은 먼저 SIGINT로 정상 정리 기회를 주고 제한 시간 이후에만 해당 실행의 프로세스 그룹을 종료한다. 강제 종료·SIGKILL·전원 종료나 정리 시간 초과에서는 DB·schema가 남을 수 있다. 다음 실행은 남은 lock/PID를 임의 삭제하지 않고 거부하므로, 이전 로그·보고서와 실제 프로세스 소유권을 대조한 뒤 복구한다. 이 취소 동작의 실제 Linux 신호 검증도 아직 남아 있다.

백업은 PostgreSQL의 [SQL dump 절차](https://www.postgresql.org/docs/17/backup-dump.html)와 [pg_restore](https://www.postgresql.org/docs/17/app-pgrestore.html)를 따른다. 시험 중 DB 중지는 소유 클러스터만 명시한 [pg_ctl](https://www.postgresql.org/docs/17/app-pg-ctl.html)로 수행한다. 중지·재시작의 timeout과 로그가 있어야 하며, 실행 실패 후 임의의 새 PID를 자신의 서버로 채택하지 않는다.

## 5. 실제 systemd·Nginx/TLS 검증

프로세스를 직접 시작하는 Python fixture 성공만으로 systemd·TLS 검증을 통과 처리하지 않는다. 아래는 앞 단계에서 만든 WAR와 합성 DB를 사용하는 별도 설치 시험이다. [Linux 배포 가이드](LINUX-DEPLOYMENT.md)의 파일 권한과 설정을 적용한다.

1. 신뢰하는 빌드 기록의 WAR 해시로 `deploy/linux/verify-artifact.sh`를 실행한다. 검증된 WAR와 Java를 `/opt`에 배치하고, 제한된 서비스 계정과 root 전용 환경파일을 만든다. 서비스의 `ProtectHome=yes`를 해제해 홈 아래 Java에 접근시키지 않는다.
2. `systemd-analyze verify`로 복사한 단위를 확인하고 실제 `systemctl start/status`와 journal을 확인한다. `Type=exec` 시작 성공과 앱 준비 완료를 구분한다. JSP 임시 컴파일 경로, 비root UID, WAR·환경파일 쓰기 제한을 실제 HTTP 요청과 파일 권한으로 확인한다.
3. 첫 기동에는 예약·작업 처리를 끈다. 내부 loopback에서 생존/준비 확인의 상태 전용 응답과 익명 사용자의 관리자 metrics 접근 차단만 확인한다. 세션을 사용하는 검증은 HTTPS 구성 뒤에 진행한다.
4. Nginx에 검토한 설정을 복사하고 `nginx -t` 후 기동한다. 합성 전용 CA와 SAN이 맞는 짧은 수명의 시험 인증서로 TLS를 검증한다. `curl --resolve`와 `--cacert`로 이름·신뢰 검증을 수행하며, `-k`로 우회한 접속은 TLS 통과 증거로 사용하지 않는다. 공개 도메인 등록이나 운영 키를 요구하지 않는다.
5. HTTPS에서 합성 계정 로그인·로그아웃·승인·CSRF, worker OFF 안내와 수동 요청 보존을 확인한다. HTTPS 리다이렉트, Secure 세션 쿠키, 일반 사용자의 관리자 기능 접근 차단, forwarded header 재작성, 프록시의 `/actuator` 차단도 확인한다. Nginx가 actuator를 차단하므로 생존/준비 확인은 앱 내부 포트로 수행한다. [Ubuntu Nginx 안내](https://ubuntu.com/server/docs/how-to/web-services/install-nginx/), [Nginx HTTPS 설정](https://nginx.org/en/docs/http/configuring_https_servers.html)
6. 합성 fixture와 연결한 작업 처리를 켜고 systemd 정상 중지·재시작 후 요청·진행 상태·DB 보존을 확인한다. 새 DB로 백업을 복원한 별도 앱을 작업 처리 OFF로 기동해 화면과 데이터가 복구되는지도 확인한다. 원본 DB를 삭제하거나 이전 스키마로 덮어쓰지 않는다.

HTTP fixture는 자체 시험 설정을 사용하지만 HTTPS 설치 시험의 `SESSION_COOKIE_SECURE=true`를 낮추지 않는다. WSL의 NAT/미러링과 Windows 포트 충돌을 확인하고 loopback 접근부터 검증한다. 전체 인터페이스 개방이나 광범위한 방화벽 예외를 이 계획의 전제로 두지 않는다. [Microsoft WSL 네트워크 안내](https://learn.microsoft.com/en-us/windows/wsl/networking)

WSL에서 systemd가 동작해도 서비스만으로 WSL 인스턴스가 계속 살아 있는 것은 아니다. Windows 절전·재부팅과 실제 운영 Linux의 부팅·네트워크·자원 조건도 다르다. 따라서 여기서 통과한 결과는 해당 WSL 환경의 재현 결과로 기록하고, 운영 서버의 지속 실행·외부 TLS·실제 모델 품질 검증을 완료했다고 표현하지 않는다. [Microsoft systemd 설명](https://learn.microsoft.com/en-us/windows/wsl/systemd)

## 현재 확인된 환경과 남은 실행

아래 설치 상태는 2026-10-01 담당 실행자가 실제 확인해 로컬 `.local/session7-wsl-preflight.json`에 기록한 결과다. 비밀값을 포함하는 환경 덤프는 기록하지 않는다.

| 항목 | 확인 결과 |
|---|---|
| Windows | Windows 11 Pro, 빌드 26200.9457 |
| WSL 구성요소 | WSL 3.0.1.0, 커널 6.18.40.1-1 설치 명령 종료 코드 0 |
| 가상화 적용 | BIOS 가상화 활성, VirtualMachinePlatform 설치 상태 1, HypervisorPresent=false, RebootPending=true |
| Ubuntu 전용 설치 시도 | 종료 코드 0이나 재부팅 적용 안내만 반환; 재확인한 배포판 목록은 비어 있음 |
| 다음 환경 작업 | 사용자가 Windows 재부팅 후 상태 확인, 전용 Ubuntu 설치 명령 재실행 |
| Linux 패키지·systemd | 미실행 |
| Linux 전체 PG·백업·재시작·동시 실행·DB 복구 | 미실행 |
| Linux 서비스 설치·Nginx TLS·서비스 복구 | 미실행 |

재개 시에는 배포판/커널/도구 버전, 소스 기준 커밋, 각 실행 명령의 종료 코드, 성공·실패·skip 수, WAR 해시와 안전한 보고서 경로를 추가한다. 실패한 단계와 미실행 단계를 분리하고, Linux 실검증 완료 전에는 이 계획을 검증 완료 기록이나 릴리스 승인으로 사용하지 않는다. 신규 Linux 검증 도구와 모의 테스트는 작업 트리에 보존하며, 필수 실제 Linux 검증 전 코드 커밋은 보류한다. 설치 현황과 재개 절차 문서는 별도로 기록한다.
