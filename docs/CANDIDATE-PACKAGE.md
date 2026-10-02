# Linux 배포 후보 패키지

`scripts/package-candidate.py`는 이미 빌드한 WAR와 검토용 Linux 배포 자료를 로컬 `tar.gz`로 묶는다. Java·DB·서비스를 실행하거나 업로드·설치하지 않는다. 현재 Maven 버전 `0.1.0-SNAPSHOT`을 유지하며, 생성 결과는 **정식 릴리스 승인 전 후보**다. WSL Linux 설치·복구 검증 범위는 [검증 기록](WSL-VALIDATION.md)을 따르며 운영 모델 품질·실제 운영 서버·인수 검증은 [릴리스 기준](RELEASE-CHECKLIST.md)에 남아 있다.

## 생성

Python3.10 이상을 사용한다. 저장소 루트에서 실제 DB 테스트와 WAR 검증을 마친 뒤 실행한다. `--war`와 `--output-dir`은 절대경로이며, 출력 디렉터리의 부모는 존재하고 출력 디렉터리 자체는 아직 없어야 한다. 기존 출력은 덮어쓰지 않는다.

```powershell
python scripts/package-candidate.py `
  --war D:\ai-reviewer\source\target\ai-code-reviewer.war `
  --expected-war-sha256 APPROVED_64_HEX_WAR_DIGEST `
  --source-revision VERIFIED_40_HEX_SOURCE_COMMIT `
  --output-dir D:\ai-reviewer\.local\candidate-new `
  --source-date-epoch 0
```

해시와 커밋 자리표시는 검토한 빌드 기록의 실제 값으로 바꾼다. 배포 시 기대 해시는 승인 기록처럼 독립적으로 신뢰할 수 있는 경로에서 받아야 한다. 전달받은 WAR의 해시를 새로 계산해 기대값으로 넣는 것만으로 출처를 검증할 수는 없다.

소스 커밋은 형식만 검사하는 **운영자 제공 기록**이다. 도구가 WAR와 해당 Git 커밋의 동일성을 증명하거나 서명하는 것은 아니다. WAR 검증에는 기대 SHA-256, 크기·ZIP 경로·실행 구조·Spring Boot 및 현재 개발 버전을 포함한다. 검증한 WAR 바이트를 그대로 묶는다.

## 포함 파일과 확인

출력은 `ai-reviewer-0.1.0-SNAPSHOT-candidate.tar.gz`와 그 `.sha256` 파일이다. 압축 내부에는 다음만 포함한다.

- `ai-code-reviewer.war`
- `deploy/linux/`의 systemd 서비스, 빈 자격증명의 환경 예제, Nginx 예제, 읽기 전용 WAR 검증 도구
- 고정 목록의 `docs/` 운영·검증 안내
- `CANDIDATE.json`: 후보 상태, 버전, 운영자 제공 소스 식별자, 파일 해시와 제한
- `SHA256SUMS`: 내부 파일의 SHA-256 목록

실제 환경파일·키·DB 백업·로그·`.local`·임의 추가 파일은 수집하지 않는다. 환경 예제의 활성 설정은 저장소의 고정 기본값과 대조하므로 비밀값을 채우거나 예약/작업자를 켠 복사본은 거부한다. 허용 텍스트의 OpenAI 키·개인키 패턴도 검사하며 값은 오류에 출력하지 않는다. 이 제한적 검사는 임의 비밀번호나 WAR 내부에 숨긴 모든 비밀을 탐지한다는 보장이 아니다. 기존 [커밋 비밀정보 검사](SECRET-HYGIENE.md)와 빌드 검토가 함께 필요하다.

내부 경로는 저장소의 `docs/`·`deploy/linux/` 구조를 유지한다. 문서에서 참조하는 애플리케이션 소스·테스트·개발 스크립트·CI 설정은 묶음에 포함하지 않으므로 해당 소스 체크아웃에서 확인한다. 환경 예제는 배포 후 저장소 밖에서 설정하고 원본 후보 파일은 보존한다.

서버로 전달한 뒤에는 **압축을 풀기 전에** 독립 승인 기록의 전체 아카이브 해시와 비교한다. 신뢰를 확인한 후보만 비어 있는 staging 디렉터리에 풀고 `sha256sum -c SHA256SUMS`로 내용을 확인한다. 내부 manifest와 체크섬은 전송 오류·변조 확인 자료이며, 아카이브와 함께 바꿀 수 있으므로 독립 서명이나 승인 근거를 대신하지 않는다. 이후 설치는 [Linux 배포·복구 가이드](LINUX-DEPLOYMENT.md)를 따른다.

## 재현성과 검증 범위

파일 순서, 텍스트 UTF-8/LF, tar 소유자·권한·시간, gzip 헤더를 고정한다. 같은 WAR 바이트·문서·소스 식별자·epoch를 넣으면 같은 압축 바이트가 생성된다. 이 비교는 같은 Python/zlib 실행 환경의 후보 생성에 대한 것으로, Maven 소스 재빌드나 서로 다른 압축 라이브러리 버전의 동일성을 보장하지 않는다.

모든 입력·출력 경로의 링크와 Windows reparse point를 거부하고 고정 상대경로의 정규 파일만 저장한다. 실패하면 이번 호출이 만든 출력만 정리하며 기존 파일은 보존한다. 자동 테스트는 임시 fixture에서 해시/버전/경로/비밀 입력·기존 출력 보존·재현성을 검증한다. 실제 운영 배포와 구분한다.

2026-09-27 KST, Windows Python의 전용 fixture26개가 통과했다. 이전 회차에는 실제 검증 WAR로 후보 두 개를 생성해 압축 바이트와 외부 체크섬이 일치하고, 내부20개 파일의 전체 체크섬·메타데이터·WAR 원본 보존·빈 비밀 설정을 확인했다. 위조된 ZIP 크기와 CRC를 신뢰하지 않는 실제 manifest 해제량 제한, 손상 압축의 고정 오류, 생성 중 동명 파일을 보존하는 실패 정리 회귀도 포함한다. 현재 고정 목록에는 공개 Git·DB 복구·WSL 검증 문서도 포함해 내부 파일이23개다. CI에는 같은 입력의 두 후보를 비교하는 단계를 추가했으며 실제 원격 실행은 아직 하지 않았다.

2026-10-02 WSL Linux에서도 최종 WAR의 후보2개가 같은 바이트를 생성했고, 추출 전23개 파일의 전체 체크섬·메타데이터·manifest를 검사했다. 전용 WSL에 설치한 후보로 V12→V13 업데이트와 새 DB에 복원한 백업을 이용한 구버전 복귀, 양쪽 HTTPS 로그인을 확인했다. Windows/Linux 후보 fixture26건씩 통과했다. 후보의 소스 식별자·WAR/아카이브 해시·시험 데이터 범위는 [WSL 검증 기록](WSL-VALIDATION.md)을 따른다.
