# 현재 작업 상태

- 작업: 실사용 AI 소스코드 리뷰 시스템. **개발 중, 릴리스 완료 아님.**
- 이번 회차: 2026-10-02 16:08 KST 시작, 최대 18:08 KST까지 구현·검증 후 인계한다.
- 기록 기준: `main` / `f623fd2` 이후 검증 기록. 회차 시작은 `d4a2352`와 Linux 검증 도구·테스트 2개 미추적 파일이었으며 이를 보존해 검증·커밋했다. 이전 이력과 이번 커밋은 모두 미푸시.
- 이번 완료 조건: 사용자 요청에 따라 WSL Linux 검증 환경을 구축하고 Docker 없이 기존 Java25/PostgreSQL17 빌드·배포·복구 검증을 수행한다. 운영 서버 배포와 실제 모델 품질 승인은 별도다.
- 이번 단계: WSL 전체 빌드·복구·systemd/HTTPS와 후보 설치, 별도 합성 V12→V13 업데이트·백업을 통한 구버전 복귀까지 통과했다. 코드·도구를 로컬 커밋했고 시험용 앱·Nginx·PG를 중지해11개 포트 연결 불가를 확인했다. 소유 keeper도 PID/시작 시각 대조 후 종료했다. 운영 배포·실제/유료 모델 호출 없음. LiteLLM 주소·모델은 아직 미정이며 제품은 계속 개발 중이다.
- 확정 요구사항: `docs/REQUIREMENTS.md`. 내부 이슈함 우선, 최초 전체 이력, Java25/Spring/Maven/JSP/PostgreSQL 유지.
- 재개 시 이 문서와 실제 Git 상태를 대조하고 아래 다음 작업부터 진행한다. 모든 릴리스 항목 검증 전 완료라고 보고하지 않는다.

## 구현한 내용

- Maven Wrapper/WAR, PostgreSQL Flyway V1~V13, JSP/JSTL 반응형 화면.
- 공개 회원가입→관리자 승인/반려/재검토, 승인 전 로그인·활성화 우회 차단, 중복 접수 동일 안내, 가입 IP 제한. 관리자 직접 계정 생성 웹 기능 제거, 최초 bootstrap 유지.
- ADMIN 계정 활성/초기화, USER 로그인/비밀번호, BCrypt12·CSRF·CSP·로그인 제한·세션 폐기·동시 마지막 관리자 보호.
- clone 형태 HTTP(S) URL 프로젝트 신청·승인/반려/중지/재개·소유권 검사.
- GitHub/GitLab 전체 이력·부모 그래프·diff 완전성 검사, Ollama/LiteLLM 엄격한 구조화 출력 검증.
- 매 정시/수동 백그라운드 리뷰, 프로젝트별 PG advisory lock, 커밋별 리뷰/이슈 원자 저장, 실패 후 재사용과 중복 방지.
- 큰 merge를 여러 배치로 처리하는 명시적 checkpoint, 저장된 SHA는 quota/diff/AI 호출에서 제외. force-push/누락/안전 한도 초과는 실패.
- 내부 이슈함·상태·배정 사유, 관리자 origin/email 작성자 매핑, 서버별 복수 Git 토큰 격리, 감사 기록.
- 검증된 EMPTY/METADATA_ONLY를 본문 AI 검토와 구분하고 수동 확인 범위 표시. 일반 누락을 제외 성공으로 처리하지 않는다.
- GitHub rename/0행 변경 후보도 불변 현재/첫 부모 tree로 전체 변경 경로·blob·모드를 확인한다. 잘림/누락·copy는 실패하며 submodule 등 지원 밖 본문은 전체 변경 범위 증명 후 수동 전환 조건을 따른다. 일반 본문-only 경로의 tree 증명 확대는 별도다.
- 양쪽 Git 공급자의 정규 빈 파일 생성·삭제 증명, 관리자 PAUSED 프로젝트 진행 기준 복구. 기존 결과/이슈를 보존하며 DB 잠금·원자 감사 기록·실패 원인 화면을 포함한다.
- 실행/커밋 기록 독립50건 페이지, 이슈/리뷰의 안전한 원본 커밋 링크.
- OpenAI Responses 직접 연결: 별도 OPENAI_MODEL/OPENAI_API_KEY, 고정 공식 HTTPS 목적지, strict JSON·완료/거절/도구/크기·시간 검증. 유료 실제 연결은 미실행.
- 프로젝트 검색/승인 상태50건, 사용자 승인함/검색50명, 이슈25건 짧은 목록+권한 재검사 상세. 상태 변경 후 검색/페이지 유지. 예약 후보 최대1000/요청 poll최대64개와 SQL 범위별 정렬 상한.
- 한국어 상태·역할별 메뉴·URL 중심 등록·안전한 입력 복원·키보드/모바일 흐름. 예약이 꺼져 있으면 수동 실행 안내. JSP fragment 한글 인코딩 수정.
- Git tracked/index 비밀정보 검사와 CI gate: OpenAI 키·개인키·env 파일 차단, 값 비출력, 링크/reparse point/읽기 오류 실패 처리.
- 격리 PG 자동 검증/백업복원, Java25·PG17 CI 정의와 필수DB검증 gate, OSV 의존성 검사.
- 파일 경계 AI 분할(기본8회, 최대32회), 커밋 전체 공통 시간 제한, 분할별 파일/행 검증. 단일 파일 내부는 나누지 않으며 분할 간 맥락 검토에는 한계가 있다.
- 미제공/지원 밖 diff·입력 한도는 전체 변경 경로를 고정 현재/첫 부모 tree로 증명한 경우에만 커밋 전체 MANUAL_ONLY로 전환한다. 파일별 수동 확인 이슈·blob/mode 근거를 원자 저장하고 다음 커밋을 진행한다. 최대1000경로, API/증명/AI응답 오류는 계속 실패한다.
- 수동 이슈는 AI 심각도/행번호 없이 구분하고, 담당자/관리자만5~1000자 처리 사유와 함께 상태를 변경한다. 사유·상태·감사 기록은 함께 저장된다.
- 관리자 운영 현황: 최근 실패/오래된 실행 시작/미실행/대기/장기 미완료 요청 필터·50건 페이지. 요청과 실행의 경과 시간을 구분하고 최근 복구된 RUNNING도 최초 접수 시각으로 장기 지연을 표시한다. 실제 다른 프로세스 실행 여부나 SLA를 보장하는 지표는 아니다.
- JDBC query30초/socket45초/connect10초 기본 제한·설정 충돌 검증·PG 잠금 쿼리 제한. 종료·처리 거부 시 DB 요청을 보존한다.
- Linux 비Docker WAR/systemd/Nginx/env 템플릿과 설치·업데이트·새 DB 복원/롤백 절차. 읽기 전용 WAR 검증 도구/CI 검사 포함. WSL에서 실제 설치·HTTPS·새 DB 복원은 검증했고 운영 서버·업그레이드 검증은 남아 있다.
- MVC 미처리 예외의 SQL/입력값이 Tomcat 로그에 전파되지 않도록 고정500 화면·예외종류/참조번호만 기록한다. 필터/JSP 렌더 오류와 별도 debug 로그까지 전역 정화하는 기능은 아니다.
- 신규 METADATA_ONLY도 경로·권한·파일 유형·canonical 빈 파일 영향을 재증명하여 METADATA_CHANGE 수동 업무로 저장한다. 파일 변경이 없는 EMPTY, 메타데이터 header가 AI에 포함되는 FULL, 과거 저장 이력의 재사용은 유지한다. 진행 기준과 사람의 확인 완료를 화면에서 구분한다.
- DB 최근 요청·다음 예약 시각, 중복 접수 병합, 미실행 예약 따라잡기, 저장된 커밋 재사용과 서버 재시작 후 재개. 현재 요청/claim 토큰/실행/권한을 저장 트랜잭션에서 검사하여 이전 작업자의 늦은 결과를 차단한다. DB 오류·종료 인터럽트는 미완료 요청을 보존하고30초~1시간 복구 backoff를 적용한다.
- `REVIEW_ENABLED`는 새 예약 생성, `REVIEW_WORKER_ENABLED`는 현재 서버의 처리를 각각 제어한다. 작업자 중지 배너·최근 요청 카드·취소 사유·중복 요청 버튼·사용자 관점 안내를 제공한다. Linux 초기/복원/업데이트 시 양쪽 중지를 문서화했다.
- 관리자 ‘서버 상태’와 JSON 요약: 30초 별도 읽기 전용 REPEATABLE_READ 집계, commit 후 캐시 교체, 실패/90초 경과 시 수치 숨김·JSON503. 최근 실행 실패·원래 접수 후 오래된 요청의 화면 알림과 해당 운영 목록 링크, 현재 서버 설정·UTC 초 단위 시간 표시를 제공한다. 메트릭14개는 고정 태그와 캐시만 사용하고 미가용 업무 수치는 NaN이다.
- 익명 GET/HEAD 생존·DB 포함 준비 probe는 상태만 반환한다. health 루트와 metrics는 현재 승인·활성 ADMIN만 조회하며 다른 Actuator 경로·변경 메서드를 차단한다. DB health indicator의 WARN 예외 원문을 좁게 억제하고 상태503은 유지한다. 전체 로그 정화나 외부 Git/AI 정상 보장은 아니다.
- 공유 예약 풀을3스레드로 맞춰 세 동기 작업(집계·예약 접수·대기 요청 확인)의 자리를 둔다. 앞의 두 작업이 막힌 동안에도 새 요청 확인이 실행되는 회귀를 추가했다. DB 고갈이나 정시 시작 SLA를 해결하는 설정은 아니다.
- Linux 후보 포장:0.1.0-SNAPSHOT 유지, 기대 WAR 해시·구조·버전과 고정 파일 목록·빈 환경 기본값 확인, tar/gzip 메타데이터 고정과 내부/외부 SHA256 목록. caller 소스 커밋은 검증되지 않은 제공 기록, releaseApproved=false로 표시한다. 링크/reparse·기존 출력·값이 채워진 환경 예제를 거부하고 실패 시 직접 생성한 파일만 식별자를 확인해 정리한다. 실제 설치·릴리스 승인은 수행하지 않는다.
- LiteLLM 우선 평가 도구: 명시 주소·모델, HTTP 없는 호출 계획, 최대6개 고정 합성 사례/각1회 요청, 기대 파일·행·권고 개수와 사람 의미 검토 분리. 중단/설정 오류/요청 실패를 구분하고 알려진 키·주소가 결과에 재현되면 저장하지 않는다. 실제 모델 품질 승인은 별도다.
- 현재 요청에 연결된 실행의 마지막 처리 단계·이번 시도 새 저장 건수·마지막 저장 시각을 프로젝트/리뷰 화면에 표시한다. 원자 저장·claim/권한 보호를 유지하며 새 요청/복구 시도에 이전 실행의 수치를 붙이지 않는다. 과거 실행의 없는 시각은 만들지 않으며 수동 업무 배정과 사람의 확인 완료를 구분한다.
- 부모 실행이 시작한 격리 PostgreSQL만 중지·재시작하는 실제 WAR 검증 도구. 시작 전 고정 경로·모든 조상의 링크/reparse·marker/버전 검사, 제어 전 PID/시작 시각·SQL 서버 대조, 실행별 보고서 토큰과 검증한 소유권만 인계해 마지막 종료 대상을 보호한다. 실패 시 임의 교체 서버를 채택하지 않는다.
- WSL2 전용 Ubuntu24.04·Java25·PostgreSQL17 준비와 Linux native 파일시스템 격리·systemd/TLS/복원 재현 계획을 `docs/WSL-VALIDATION.md`에 기록했다. 기존 Linux 배포 가이드의 로그인 검증을 TLS 이후로 옮기고 ProtectHome과 Java 실제 설치 경로 제약을 명시했다.
- Linux 부모 검증 도구는 non-root·PG17/Java25·PGDATA 마운트/링크/마커·PID/시작 시각·SQL 대조, clean verify/필수 PG gate·선택 백업/세 WAR 검증 연결과 소유권 인계를 제공한다. 백업은 새 UUID DB만 복원/제거하고 원본을 보존한다. 모의 회귀와 Linux 실제 신호 취소4건·전체 실행을 통과해 반영 가능한 상태다.

## 최신 실제 검증

- WSL2 전용 ai-reviewer-validation에 Ubuntu24.04.5·커널6.18.40.1·Temurin25.0.4.1·PG17.11·Python3.12.3·systemd255를 설치했다. 소스는 `/home/reviewer/work/ai-reviewer`, 검증 PGDATA도 Linux ext4에 별도로 생성했다. 최종 Java906건 중896통과/선택10skip(외부7+부하3), 실패/오류0, 필수PG4suite gate 통과. Linux Python171건 중161통과/Windows PowerShell10skip, Windows171건 중166통과/POSIX5skip. 실제 Linux 취소4건 포함.
- 최종 Linux 부모 통합 실행에서 10테이블 backup/restore·identity/queue 삽입·새 DB 제거·원본 보존 통과. WAR 재시작53.331초·동시 실행21.974초·DB 중단/복구46.080초 통과, 소유 WAR/schema/PG 정리 확인. 보고서: Linux `.local/linux-postgres-731ff1115c754dbdbc0c7a7dab3875ac`. 최종 WAR SHA256 `21b174681c22111919c408361747da3320c683dd041231811c5684ba9128f59e`.
- 별도 서비스 DB17/reviewer_service:55449와 클러스터 특권 없는 계정, 전용 앱 UID·root0600 환경파일·앱의 WAR/Java 쓰기 차단·state/runtime 쓰기·Flyway13 확인. systemd SIGKILL 자동 복구17.608초, HTTPS initial15·bootstrap 제거/재시작 resume9·위조 전달헤더 quota6·강제 종료 뒤 resume9·새 UUID DB 복원 별도 WAR의 HTTPS resume9 모두 통과. 합성 CA/SAN 검증을 우회하지 않았으며 외부 포트 개방·실제 Git/AI 호출 없음. 복원 DB만 소유권 확인 후 제거하고 원본 DB를 보존했다. 서비스 시험 WAR는 최초 Linux 빌드 `35325503bc4e6605c91bb7874b3fa1213f4c5ca172b920337031a875480b3067`이며 최종 빌드와 제품 코드는 같다.
- 발견/수정: 백업 OID가 PG JSON에서 문자열이어서 SQL bigint 변환을 추가했고, 소유권 미확정으로 보존했던 새 빈 DB2개는 정확한 OID/owner/빈 관계를 확인한 후 제거했다. JDBC 취소 시험1건이 socket timeout으로 실패하여 쿼리1초·관측5초는 유지하고 socket3→6초 및 취소 전후 동일 backend PID 검증을 추가했다. 위 최종 전체 재검증으로 해소했다. CPU 부하를 원인으로 확정하지 않는다. systemd 정상 종료143 실패 표시를 `SuccessExitStatus=143`으로 수정한 후 정상 중지 `inactive/Result=success` 확인. 일회성 복원 helper가 중지 후 상태값이 반드시143이라고 가정한 마지막 assertion은 실패했지만, HTTPS9개·정상 중지 성공·원복·소유 DB 정리는 각각 확인했다.
- 초기 설치 helper의 클러스터 이름/주소 인용과 WSL 재기동 뒤 수동 DB·hosts 복원 문제를 수정했다. 전용 hosts와 `generateHosts=false`, 검증 중 소유 foreground keeper를 사용했다. 제품 소스 오류·운영 부팅 검증과 구분한다. 관련 안전 코드의 독립 읽기 검토에서 추가 차단 결함 없음.
- Linux 최종 WAR의 구조/체크섬·artifact fixture5건·systemd 단위 검사 통과. 패키지의 WSL 문서 링크를 위해 고정 목록을23파일로 늘린 후 Windows/Linux 후보 fixture26건씩 통과했다. 최종 Linux 후보2개 바이트가 같고23파일 전체 체크섬·metadata·manifest 확인 후 설치했다. 후보 소스 기록f623fd2, 아카이브 SHA256 `eba79eb01888675584525424aa29d023936b65850bfbd43ed9bf1bef53aec03e`; `.local/session8-candidate-final-first`·`second`는 Linux에 보존한다.
- 구버전 ec05f9b를 별도 Linux 디렉터리에서 테스트 생략 리허설 WAR로 빌드했다(구버전 전체 재검증 아님). 새 UUID DB에서 V12의 계정/프로젝트/QUEUED 생성→중지/backup→최종후보로V13 적용→중지/또새DB에V12백업복원→구WAR복귀를 통과했다. HTTPS15/9/9, V1~V12 checksum과9업무테이블 행 보존·V13진행3열·원본ai_reviewer의10테이블 지문 보존 확인. worker/scheduler OFF·review_run0행인 범위로 한정한다. 소유DB2개만정리,원본과root0600backup보존. 세부증거는 `docs/WSL-VALIDATION.md`와 Windows `.local/session8-validation-summary.json`에 기록했다.

### 이전 회차: WSL 구성요소 설치·재부팅 대기

- WSL 설치: Windows11 Pro 빌드26200.9457, WSL3.0.1.0/커널6.18.40.1-1, VirtualMachinePlatform 활성(InstallState1), 펌웨어 가상화 활성 확인. 당시 Windows 재부팅 대기·HypervisorPresent=false·등록된 배포판 없음까지 확인했다. `.local/session7-wsl-preflight.json`에 요약을 보관했다. 해당 회차의 Linux 실행·Java/PG 빌드·systemd/TLS·복구 검증은 미실행이었다.
- 신규 Linux 도구 모의30건 전부 통과, 전체 Python133건 중132통과/POSIX wrapper1skip, 실패/오류0. `.local/session7-linux-runner-unit.log`와 `.local/session7-python-full.log`에 보존했다. 독립 읽기 리뷰에서 발견한 PG 배포판 버전 접미사·Maven cwd·중첩 마운트·취소 정리 결함을 수정하고 위 검사와 재검토를 통과했다. 실제 Linux 신호·프로세스·DB 검증은 별도다.
- WSL 문서의 로컬 링크·PowerShell 명령 블록2개 구문, Linux 서비스 ProtectHome/secure-cookie 설정 대조와 diff 공백 검사를 통과했다. 이 회차에는 Java 소스 변경이 없어 Maven/실제 PG/브라우저 검증은 재실행하지 않았다. 아래 Java/WAR 결과는 이전 회차 결과다.

### 이전 회차: 진행 정보·LiteLLM 준비·DB 복구

- 최종 전체 Java906건 중896통과/선택10skip(외부7+부하3), 필수PG4개 suite gate·10개 테이블 백업/복원 통과. 앞선 V12→V13 실제PG 업그레이드에 이어 최종 WAR 강제 종료/3회 시작52.844초, 두 WAR 잠금 경쟁/후속 진행22.375초 통과. 두 JSP에서 재시작 전 저장1건, 재시도 중 신규저장0건/이전 결과 재사용을 확인했다. 모바일 진행 정보를 상단으로 옮긴 최종 JSP도 실제 WAR390px/1280px·키보드 이동·본문 가로 넘침 없음으로 확인했다.
- LiteLLM 평가 신규18건+기존12건 자동 회귀 및 실제 HTTP 없는2사례 preflight 통과. 잘못된 행·추가 권고·주입/한국어 heuristic·중단/실패 기록·known key/base 비노출을 검증했다. 자동 precheck와 사람 의미 검토 PENDING/품질 NOT_ASSESSED를 구분하며 실제 프록시 연결·운영 품질 통과가 아니다.
- 이전 회차 공개 Git 실서비스5건 통과: GitHub2건4.067초, GitLab3건13.00초. 새3사례에서 GitHub 바이너리 루트→다음 바이너리→빈 재개, GitLab 이미지 수정→일반 본문→빈 재개, canonical 빈 파일 메타데이터→수동 근거 재증명을 확인했다. 토큰·AI·DB 호출 없음. 신규3건 예상43GET은 코드 기반 계산이며 실측 호출량은 아니다. `docs/PUBLIC-GIT-VALIDATION.md`와 `.local/session6-public-git.log`에 범위·한계를 기록했다.
- 전체 Python103건 중102통과/POSIX1skip. DB 복구 경계22건·PowerShell 소유권 helper10건·후보26건 포함. 실제 격리 DB fast stop/재시작42.984초 통과: 같은 WAR1회 시작, 준비503/생존200→준비200, 동일 요청/요청자·이슈 중복 없음·미저장B만 재호출·새 시도 진행 카드·소유 schema/WAR/PG 정리. actionlint·Bash 문법·배포 artifact fixture5건도 통과했다. 최종 보고서는 `.local/session6-final-full.log`, `.local/session6-python-full.log`, `.local/review-db-recovery-44e7fd2ffaae4fc697c00577715d3df1.json`에 있다.
- 최종 WAR42,002,558바이트 구조·SHA256 확인 후 후보2개37,965,105바이트가 동일하고, 내부22개 파일 전체 해시/메타데이터/원본 WAR/빈 비밀 설정을 재검증했다. `.local/session6-candidate-first`·`second`와 `.local/session6-candidate-result.json`에 보존했다. sourceRevision은7aec5e9의 운영자 제공 기록이며 releaseApproved=false다. Git Bash WAR 검사에 Windows식 경로를 전달해 처음 거부된 명령은 `/d/...` 절대경로로 바로잡아 통과했다. 실제 Linux 설치·원격 CI·운영 AI 품질 검증은 남아 있다.

### 이전 회차: 운영 관측과 후보 묶음

- 최종 전체870건 중863통과/선택7skip, 필수PG4개 suite gate·10개 테이블 백업/복원·최종 WAR 구조 검사 통과. 최종 WAR 강제 종료/재시작50.031초, 두 WAR의 잠금 경쟁/후속 진행22.063초 통과 및 소유 프로세스·schema·작업 폴더·PG 정리 확인.
- 실제 production scheduled3개를 등록한 슬롯 회귀 통과. 기존pool2를 잠시 적용한 대조 실행은 ‘두 작업이 막힌 동안 새 요청 확인’ 항목에서 예상 실패했고, pool3으로 정확히 복원한 뒤 위 전체 검증을 통과했다. 다른 테스트 실패로 대조를 오인하지 않았다.
- 후보 도구 fixture26개 포함 전체Python69건 중68통과/POSIX wrapper1skip. 독립 리뷰에서 찾은 실제 manifest 해제량·손상압축 오류를 보완했고 위조 크기/CRC·손상DEFLATE 회귀가 통과했다. 최종 WAR41,998,495바이트로 만든 후보2개의37,951,629바이트가 동일하고, 내부20파일의 전체SHA256/metadata/원본WAR/빈 비밀값을 재검증했다. `.local/session5-final-candidate-first`·`second`에 결과를 보관하며 sourceRevision은513a91e의 운영자 제공 기록이다. 중간3개 실패는 Windows ZipInfo가 backslash를 정규화한 fixture를 실제 ZIP header 주입 방식으로 고쳐 해소했다. 원격CI·실제Linux 포장/설치는 미실행이다.
- 추가 집계 opt-in 전체869건 중863통과/선택6skip,10개 테이블 백업/복원 통과. PostgreSQL 10,000프로젝트·100,000실행·10,000요청의 정확 집계와14개 고정 지표를 검증했다. 데이터 삽입·ANALYZE 후 집계71ms, 최신 실패 SQL 실행34.407ms는 캐시가 준비된 로컬 관찰이며 운영 처리량 보장이 아니다. 별도 schema 제거까지 통과했다.
- 최종 기본 선택 설정의 전체869건 중862통과/선택7skip(외부4+부하3), 필수PG4개 suite gate 통과. 같은 최종 WAR 두 개의 동시성 검증22.406초 통과: 느린 AI 응답 중 B의BUSY defer·A의claim/run/attempt 유지, 빠른2프로젝트 완료, 세 프로젝트 각각실행/커밋/이슈/AI1개와양쪽JSP공유결과 확인. 소유 프로세스·schema·작업 디렉터리·검증PG 정리까지 통과했다. 기본 실행의 부하skip과 앞선 opt-in 통과는 별개다.
- Python43건 중42통과/POSIX wrapper1skip, 새 부하·두 서버 검증 도구의 독립 정적 리뷰에서 차단 결함 없음. actionlint·PowerShell/Bash 문법 통과. 원격CI·실제Linux·장시간 공정성·외부/유료 호출은 미실행이다.

### 이번 회차 첫 기능 묶음 검증

- 최종 전체868건 중862통과/선택6skip(외부4+부하2). 신규 운영 관측/권한/HTTP 검사50건 포함. 실제 PG 집계4건에서 독립 세션 commit 사이의 REPEATABLE_READ·readOnly·정확 경계·오류 후 복구를 확인했고, 필수PG report gate·HTTP17건·10개 테이블 백업/복원을 통과했다.
- 최종 WAR 강제 종료/3회 재시작49.9초 통과. 별도 최종 WAR의 실제 검증DB 중지 중 익명/로그인쿠키 생존200·준비503·GET status-only/HEAD 빈본문을 확인하고 DB 재시작 후 준비200 복구를 검증했다. 실제 집계 SQL 오류는 JSON503/수치 제거/이전 성공 시각 유지, Actuator 업무 값 문자열NaN으로 확인했다. 모두 자체 격리 schema와 합성 계정이며 외부/유료 호출 없음.
- 최종 WAR390px 정상/수집 실패와1280px 운영 화면, UTC 초 표시·원본datetime 유지, 관리자 메뉴 키보드 진입·지연 필터 링크를 확인했다. 본문 가로 넘침 없음. Python32건 중31통과/POSIX1skip, artifact fixture5건·최종WAR검사·actionlint·staged비밀정보검사 통과. 독립 staged 코드 검토에서 차단 결함 없음.
- 검증 중 fixture의 SimpleMeterRegistry close 방식·MockMvc HEAD/상대redirect 기대·합성SQL의 Windows 인코딩을 수정했다. 실행 중 WAR 파일 잠금에 의한 재패키징 실패는 검증 프로세스를 종료하고 위 최종 전체 검증으로 해소했다. DB 장애용 로컬 helper의 pg_ctl stdout pipe 대기는 DEVNULL로 바꿔 다시 통과했다. 제품 런타임·운영 배포 오류로 섞어 보고하지 않는다.

### 이전 회차 검증: 요청 지속성과 재시작

- 최종 전체818건 중812통과/선택6skip(외부4+부하2). 실제 PG 신규18건과 필수DB suite gate,10개 테이블 백업/복원·identity 삽입 통과. V11→V12 H2 데이터 보존, NULL/시간/상태/동률·가변K 조회와1000개 활성 예약 뒤 후보 진행 포함. 범위별 staged 독립 리뷰에서 남은 차단 결함 없음.
- 실제 WAR3회 시작·중단 커밋 처리 중 강제 종료·원래 요청자/접수 복구·2커밋/2이슈/체크포인트 검증 통과. 마지막 수정 후 재검증51.3초, 프로젝트 상세와 리뷰 기록 양쪽의 활성 요청 안내도 실제 HTTP로 검사했다. 합성 GitLab/Ollama만 호출하며 외부/유료 호출 없음. `docs/QUEUE-VALIDATION.md`에 재현·한계를 기록했다.
- 최종 WAR390px 일반 사용자 접수 카드/리뷰 기록/중지 배너·중복 버튼, 관리자 장기 미완료 RUNNING/QUEUED·50→2건 페이지·키보드 가로40px 이동 확인.1280px 운영 표 링크 줄바꿈 수정, 첫 행73px·본문 가로 넘침 없음. 현재 요청과 이전 실패를 별도로 안내한다.
- Python31건 중30통과/POSIX 실행 링크1skip, Git Bash artifact fixture5건·최종 WAR 구조/해시·actionlint 통과. staged 비밀정보·공백 검사 통과. 원격 CI와 Linux 서비스 구동은 미실행이다.
- 중간 fixture 오류(FK DROP TABLE·redirect ViewResolver·DB 마이크로초 경계·변경 전 안내)와 H2 중첩UNION의 가변LIMIT 재사용 문제를 수정했다. 화면 검증용 대기 요청 때문에 HTTP10초 검사가 지연된 실행은 실패로 기록하고 합성 자료를 정리한 뒤 전체를 재검증했다. 마지막 화면 검증 후에도 해당52개 요청을 취소 상태로 정리해 활성 요청0건을 확인했다.

### 이전 회차 최종 검증

- 최종 후속 전체737건 중731통과/선택6skip(외부4+이력/수동 부하2). 기존 데이터가 있는 PG의 V10→V11 업그레이드·HTTP15건·H2 기존3종 사유/증거/해결 기록/FK 보존 통과.9개 테이블 백업/새 DB 복원·identity 삽입도 재검증했다. 아래 수동1000파일 선택 검증은 이번 회차 앞선 실행에서 별도 통과했으며 최종 실행의 skip을 통과로 세지 않는다.
- 최종 WAR390px 메타데이터 업무의 사유·100644→100755·본문 미검토 표시와 리뷰 진행 기준 안내 확인, 본문 가로 넘침 없음. 최종 WAR Linux 구조 검사 통과. 범위별 독립 코드 리뷰에서 추가 차단 결함 없음.
- 이번 코어 최종 전체727건 중722통과/선택5skip, 실제 PG HTTP15건·JDBC 취소/연결 재사용·수동1000파일·MVC 로그6건 포함.9개 테이블 백업/새 DB 복원·identity 삽입 통과. 중간의 과거 unsupported 정책 fixture와 잘못된 hunk fixture 수치를 수정하고 재검증했다.
- 수동 업무1000건: 저장647ms/40페이지 조회2277ms/재시도307ms, 중복·누락 없음, 권한 검사·AI 호출 없음. 로컬 합성 저장 검증이며 운영 처리량/RSS/동시성/실제Git·AI 성능은 측정하지 않았다.
- MVC 로그 보호6건 통과(실제 Tomcat 포함). 최초 fixture중복bean3오류를 제거하고 재실행했다. SQL 원인/요청값 비노출 및400/403/405/409/422/302 유지 확인.
- Python gate23건, Git Bash 배포 artifact fixture5건, actionlint·Bash 문법 통과. Linux 서비스 구동/권한/TLS는 아직 검증하지 않았다.
- 공개 GitHub 전체 이력·재개2.086초, 공식 공개 GitLab pinned 이력·루트diff·재개3.110초 재검증 통과(각1건). 토큰/AI 호출 없음, 실제 binary/설치형/비공개 호환 검증과 구분한다.
- 실제 WAR390px: 일반 사용자 수동 이슈 구분·사유 입력·확인 완료 후 목록 복귀, 관리자 메뉴 키보드 진입 확인. 운영 표 폭950px/첫 행69px로 가독성 보완, 좌우 키보드 이동40px 확인.50건 다음 페이지·실패 필터 변경 시1페이지 복귀·1280px 화면 확인, 본문 가로 넘침 없음.

### 이전 회차 검증

- 후속 최종 전체566건 중562통과/외부 선택4skip. 빈 파일38개 fixture·복구40개 서비스/컨트롤러/보안 검사·실제PG HTTP13개 포함.8개 테이블 백업복원과identity삽입 통과. 중간 안내 문구 검증/반복DB 오류주입 범위 문제는 수정하고 재검증했다.
- 별도 opt-in `GitHistoryLoadSmokeTest`1건(양쪽 공급자×3단계)1.928초 통과. 각10,000커밋 이력 순회와100건배치/저장SHA재사용,선택0건checkpoint 검증. 실제 Git/AI/DB와RSS는 측정하지 않았다. 재현/상세한계는 `docs/LOAD-VALIDATION.md`.
- 최종 WAR 화면:1280px 대시보드/이슈 확인,390px 관리자 복구 펼치기·주소불일치 안내·안전한 복귀·정상 복구 확인. DB에서 PAUSED/null기준/리뷰1개·이슈27개·기존RESOLVED 유지 확인. 가로 넘침 없음.
- GitHub 메타데이터40개 fixture 추가 후 전체487건 중483통과/외부 선택4skip, 실제PG/HTTP 및8개 테이블 백업복원 통과. 독립 코드 리뷰 통과. GitHub metadata 실서비스 호출은 아직 미실행.
- 이번 묶음은 staged index를 `.local/session2-staged-verify`로 내보내 병렬 Git 작업과 분리해 검증했다. 격리 PG + 전체447건 중443통과/외부 선택4skip, 실제HTTP12건·OpenAI fixture70건 포함. 최종 fragment 한글·예약OFF 화면 회귀도 통과.8개 테이블 백업/복원·identity 검증 통과.
- Python23건(비밀정보 검사17+보고 gate6), actionlint 통과. staged 비밀정보 검사 발견0, diff 검사 및 범위별 독립 리뷰 통과.
- 실제 WAR 브라우저: 가입 오류의 ID/Git값 복원·비밀번호 비표시, 가입 접수·승인 전 로그인 실패·관리자 승인·사용자 로그인·URL 등록·프로젝트 승인 확인. 프로젝트 검색/상태50→2, 이슈25→2·상세·해결 후 원래 page/filter 복원.390px 모바일 해당 화면 가로 넘침 없음, 관리자 메뉴 Enter 조작 확인. 합성 데이터만 사용했고 외부 AI/Git 호출 없음.

### 초기 검증 기록

- `scripts/test-postgres.ps1 -BackupRestore`: 최종 전체330건 중326통과, 외부 선택4skip. 실제 PG11개 HTTP/저장 검사 및 동시 관리자 보호 포함. V5→V6 마이그레이션, 페이지 권한/경계, 부분 merge·빈 선택 checkpoint 복구, rollback 검증.
- pg_dump→새 격리 DB pg_restore 후8개 테이블 내용/건수 fingerprint와 identity 시퀀스 삽입 일치. 생성한 복원 DB만 제거하고 원본 보존.
- 공개 GitHub octocat/Hello-World 전체 이력/재개 + 새 batch API의 이미 저장된 이력 checkpoint 실제 smoke 통과(2.284초).
- 공식 공개 GitLab gitlab-org/gitlab-test pinned47개 이력·루트1개diff·재개 smoke 실제 통과(3.794초). 전체diff/비공개/설치형 인증 검증 아님.
- 1001개 side-branch 커밋 merge fixture에서100건 분할·재시도·이미 검토한 diff 미호출, 두 provider 및 뒤쪽/빈 선택 checkpoint 회귀 통과.
- Tomcat11.0.26 수정 후 운영 의존성87개 OSV 일치0건(시점 한정), 실제 WAR Tomcat5모듈 버전 확인. JDK/OS/DB/AI 환경 보안은 별도.
- actionlint·셸 문법·Python gate6건·의존성 scanner12건 통과. 원격 CI는 미푸시로 미실행.
- 실제 WAR 브라우저 로그인·프로젝트 등록/승인·관리자 매핑/감사·리뷰 페이지50→2건 독립 이동·원본 href·메타데이터 펼치기 확인. 390/1280 viewport page overflow 없음. 모바일 날짜 줄바꿈을 수정하고 재패키지 후 행 높이233→54px, 표 가로 스크롤·키보드 오른쪽 이동 확인.
- Gemma1B 합성6사례 JSON6/6, 거친 품질0/6; Llama8B 기본 설정 JSON2/6 및120초 초과4건, 거친 품질1/6도 의미 합격 아님. `docs/AI-EVALUATION.md`에 직접 대조 기록.
- Llama8B context8192/출력1024/60초 재평가도 같은4사례 시간 초과, 나머지2사례 의미/근거 미달. 평가 설정 검증11건 통과. 평가 BUILD SUCCESS를 품질 합격으로 해석하지 않는다.

## 다음 작업

0. WSL 설치·실제PG/세WAR·systemd/TLS·복원·후보 설치·합성V12→V13/backup rollback은 통과했다. 재개 시 전용 배포판·서비스 상태·소스 동기화를 확인한다. 다음 Linux 검증은 실제 리뷰/이슈가 누적된 데이터와 실행 중 요청을 포함하는 업데이트·복귀, 운영 서버별 부팅·인증서/보존 정책이다. WSL의 결과를 운영 서버 승인으로 대체하지 않는다.
1. 공개 GitHub/GitLab 바이너리·빈 파일 실서비스5건은 통과했다. 실제 대형/설치형/비공개 응답으로 호환 범위를 확대하며 전체 변경 경로를 증명할 수 없는 응답이나 API 장애는 계속 실패 처리한다.
2. LiteLLM 주소·모델이 정해지면 준비된 dry-run으로 예산을 확인하고, 승인된 호출 범위에서 합성 평가와 사람의 의미 검토를 진행한다. 실제 설치형 GitLab·비공개 GitHub 연결과 운영 모델 품질도 남아 있다.
3. 실제 대형 저장소 부하/API할당량·다중 인스턴스 장시간 공정성/지연·장기 네트워크 단절·모니터링/보존 정책 구현·검증. 로컬 단일 WAR 중단과 DB fast stop 후 복구는 `docs/QUEUE-VALIDATION.md`·`docs/DB-RECOVERY-VALIDATION.md` 범위로 통과했다.
4. 확정 배포 환경인 Linux 서버(도커 미사용)에 맞춰 패키지/설치·업데이트·백업·롤백 구성, 운영 보안·원격CI·사용자 인수 검사. `docs/RELEASE-CHECKLIST.md` 기준.

## 재개·운영 메모

- 운영 대상은 사용자 답변으로 Linux 서버, 도커 미사용으로 확정했다(2026-09-26). 사용자 요청에 따른 전용 WSL2 Ubuntu 로컬 Linux 환경 구축과 설치/복구 검증은 2026-10-02 수행했다. 실제 서버 배포는 별도 승인 대상이다.
- origin: jang-sw/code-reviewer_by-codex. main 직접 푸시 별도 승인 없음 → 로컬 커밋만, 푸시/배포 없음.
- `.local/pg-test`: Windows 개발검증 전용127.0.0.1:55432. Windows `.local/pg-validation` 및 staged snapshot의pg-validation:55439. Linux PGDATA는 Linux 홈의 별도 checkout에만 만들고 Windows PGDATA를 공유하지 않는다.
- `.local`은 Git 제외. Linux 부모 WAR/schema/PG와 복원용 UUID DB들은 정리했다. 원본/복원/업데이트 앱·Nginx·서비스 PG를 중지하고80/443/8080~8082/18089~18092/55439/55449 연결 불가·검증 lock 부재를 확인했다. 앱 unit은 disabled, Nginx 자동 시작 해제, 서비스 PG는 manual이다. 원본 합성 DB·root 전용 설정/백업·후보·시험 기록은 전용 WSL에 보존한다. 소유 keeper도 종료했다. 사용자 Windows Ollama/55432 PG를 변경하지 않았고 Windows 재부팅은 수행하지 않았다.
- 런타임 Git 수집은 매번 pinned 전체 이력을 재검증, 기본1000페이지/metadata32MiB/저장SHA131072개 안전 한도. 제한을 자동 확장/절삭하지 않는다.
