# Linux 배포 후보 패키지

`scripts/package-candidate.py`는 이미 빌드한 WAR와 검토용 Linux 배포 자료를 로컬 `tar.gz`로 묶는다. Java·DB·서비스를 실행하거나 업로드·설치하지 않는다. 현재 Maven 버전 `0.1.0-SNAPSHOT`을 유지하며, 생성 결과는 **정식 릴리스 승인 전 후보**다. WSL Linux 설치·복구 검증 범위는 [검증 기록](WSL-VALIDATION.md)을 따르며 운영 모델 품질·실제 운영 서버·인수 검증은 [릴리스 기준](RELEASE-CHECKLIST.md)에 남아 있다.

2026-10-16 보안 재조회에서 기존 Jackson2.21.5/3.1.5 후보에 advisory7개가 반환되었다. 아래 과거 후보 생성·체크섬 성공을 현재 보안 통과로 재사용하지 않는다. Jackson2.21.7/3.1.7 패치와 새 WAR의 검증·조회 결과는 [의존성 점검](DEPENDENCY-AUDIT.md)을 따르며, 이전 후보는 그대로 보존한다.

같은 날 패치 소스 `f4392ae0fa954f6473e73870fa4547e7ad71a023`와 검증 WAR `9e207c8b5d4a405b15648d16763c3d108143d0c24207fece8ecf647eeeabca5f`를 새28파일 후보2개로 포장했다. 아카이브 SHA256 `7afccc6796a09f92246c97dda6f0d90514bb24a5656ff9f1f6acbcf47837bc1c`/38,121,133바이트다. 동일 입력의 압축바이트·내부/외부 전체체크섬·manifest·고정메타데이터·원본WAR를 확인했다. 전체Java1334건(1323통과/선택11skip)·필수PG9묶음·실제두WAR 공유인증·새88좌표OSV0건을 통과했다. Linux `.local/session25f-candidate-{first,second}`에 보존하며 설치·릴리스 승인·새모델 호출은 없다. 소스revision은운영자제공이며독립출처증명이아니다. 이결과문단은후보생성뒤작성되어해당후보에는포함되지않는다.

새후보는 Windows `.local/session25f-candidate`에도 기존출력을덮어쓰지않고복사했으며외부체크섬과Windows `Get-FileHash`가일치했다. `.local/session25f-validation-summary.json`은앞선검증원본의SHA·새WAR·후보28파일을읽기재대조한기록이며추가테스트/스캔/설치실행이아니다.

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
- `deploy/litellm/local-ollama.yaml.example`과 [로컬 LiteLLM 구성·검증 안내](LITELLM-LOCAL-VALIDATION.md)
- [로그인·가입 공유 제한](AUTH-LIMITING.md)을 포함한 고정 목록의 `docs/` 운영·검증 안내
- `CANDIDATE.json`: 후보 상태, 버전, 운영자 제공 소스 식별자, 파일 해시와 제한
- `SHA256SUMS`: 내부 파일의 SHA-256 목록

현재 고정 목록의 내부 파일은 총28개다. 로그인·가입 제한과 외부 Git/AI 호출 제한의 전환·공유 설정·장애 응답·백업 주의사항도 후보에 함께 포함한다. LiteLLM 자료는 로컬 검증 참고용이며 자동 설치·운영 모델 승인을 수행하지 않는다.

실제 환경파일·키·DB 백업·로그·`.local`·임의 추가 파일은 수집하지 않는다. 환경 예제의 활성 설정은 저장소의 고정 기본값과 대조하므로 비밀값을 채우거나 예약/작업자를 켠 복사본은 거부한다. 허용 텍스트의 OpenAI 키·개인키 패턴도 검사하며 값은 오류에 출력하지 않는다. 이 제한적 검사는 임의 비밀번호나 WAR 내부에 숨긴 모든 비밀을 탐지한다는 보장이 아니다. 기존 [커밋 비밀정보 검사](SECRET-HYGIENE.md)와 빌드 검토가 함께 필요하다.

LiteLLM 예제는 UTF-8/줄바꿈 정규화 후 주석을 포함한 전체 내용의 고정 SHA-256과 대조한다. 실제 키·주소 치환, callback 추가, 보호 설정 변경, 중복 키나 주석 추가도 출력 생성 전에 고정 오류로 거부한다. 예제를 변경할 때에는 변경 내용과 `LITELLM_TEMPLATE_SHA256` 정책을 함께 검토하고 테스트한다. 로컬 복사본 `local-ollama.yaml`·`runtime.env`는 수집하지 않는다.

내부 경로는 저장소의 `docs/`·`deploy/linux/`·`deploy/litellm/` 구조를 유지한다. 문서에서 참조하는 애플리케이션 소스·테스트·개발 스크립트·CI 설정은 묶음에 포함하지 않으므로 해당 소스 체크아웃에서 확인한다. 환경 예제는 배포 후 저장소 밖에서 설정하고 원본 후보 파일은 보존한다.

서버로 전달한 뒤에는 **압축을 풀기 전에** 독립 승인 기록의 전체 아카이브 해시와 비교한다. 신뢰를 확인한 후보만 비어 있는 staging 디렉터리에 풀고 `sha256sum -c SHA256SUMS`로 내용을 확인한다. 내부 manifest와 체크섬은 전송 오류·변조 확인 자료이며, 아카이브와 함께 바꿀 수 있으므로 독립 서명이나 승인 근거를 대신하지 않는다. 이후 설치는 [Linux 배포·복구 가이드](LINUX-DEPLOYMENT.md)를 따른다.

## 재현성과 검증 범위

파일 순서, 텍스트 UTF-8/LF, tar 소유자·권한·시간, gzip 헤더를 고정한다. 같은 WAR 바이트·문서·소스 식별자·epoch를 넣으면 같은 압축 바이트가 생성된다. 이 비교는 같은 Python/zlib 실행 환경의 후보 생성에 대한 것으로, Maven 소스 재빌드나 서로 다른 압축 라이브러리 버전의 동일성을 보장하지 않는다.

모든 입력·출력 경로의 링크와 Windows reparse point를 거부하고 고정 상대경로의 정규 파일만 저장한다. 실패하면 이번 호출이 만든 출력만 정리하며 기존 파일은 보존한다. 자동 테스트는 임시 fixture에서 해시/버전/경로/비밀 입력·기존 출력 보존·재현성을 검증한다. 실제 운영 배포와 구분한다.

2026-09-27 KST, Windows Python의 전용 fixture26개가 통과했다. 이전 회차에는 실제 검증 WAR로 후보 두 개를 생성해 압축 바이트와 외부 체크섬이 일치하고, 내부20개 파일의 전체 체크섬·메타데이터·WAR 원본 보존·빈 비밀 설정을 확인했다. 위조된 ZIP 크기와 CRC를 신뢰하지 않는 실제 manifest 해제량 제한, 손상 압축의 고정 오류, 생성 중 동명 파일을 보존하는 실패 정리 회귀도 포함한다. 로그인·가입 공유 제한 안내를 추가하기 전 고정 목록에는 공개 Git·DB 복구·WSL·누적 리뷰 업데이트 검증 문서까지 포함해 내부 파일이24개였다. CI에는 같은 입력의 두 후보를 비교하는 단계를 추가했으며 실제 원격 실행은 아직 하지 않았다.

2026-10-02 WSL Linux에서도 최종 WAR의 후보2개가 같은 바이트를 생성했고, 추출 전23개 파일의 전체 체크섬·메타데이터·manifest를 검사했다. 전용 WSL에 설치한 후보로 V12→V13 업데이트와 새 DB에 복원한 백업을 이용한 구버전 복귀, 양쪽 HTTPS 로그인을 확인했다. Windows/Linux 후보 fixture26건씩 통과했다. 후보의 소스 식별자·WAR/아카이브 해시·시험 데이터 범위는 [WSL 검증 기록](WSL-VALIDATION.md)을 따른다.

2026-10-07에는 실제65초 공유 대기·UTC 기록 화면 검증 후 제품 소스 `349181ba86ba5e72ef3f87743647659933f6fd21`의 문서와 최종 WAR로26파일 후보2개를 만들었다. 압축 바이트·내부/외부 전체 체크섬·manifest·고정 메타데이터가 일치했다. 아카이브 SHA256은 `17833052ecbc66aa0358855715dd6d3b1e8e7cdb14f8745bd80068ce03effcd5`(38,031,955바이트), WAR SHA256은 `5b91855b81a098fea0fbf48383a6a662b006f1fcb9ff4f36c8e01801c90a3a96`다. Linux `.local/session12-candidate-{first,second}`, Windows `.local/session12-candidate`에 보존한다. 이번 후보는 설치하지 않았고 `releaseApproved=false`다. 이 결과 문단은 후보 생성 후 작성되어 해당 후보 안에는 포함되지 않는다.

같은 날 후속 검증에서 관리자 Git 사용자명·브랜치 정정과 LiteLLM 자료를 반영한 `bae196ac1529a58ae4d77fb0eb52764659c3ad96` 기준28파일 후보2개의 동일 바이트·전체 체크섬·manifest·고정 메타데이터를 확인했다. 아카이브 SHA256 `3a7a01b271f8a851fbe58220f496550f5a8a65a2ddc927dd351b62813dd0f4dd`(38,062,487바이트), WAR SHA256 `e404bf57311232b946d9fc8879b8f850f897af4e8dd0849b37ebd050f8a82c35`다. 패키징29건을 포함한 Python278건이 Windows273통과/5skip·Linux268통과/10skip이었다. Linux `.local/session14-candidate-{first,second}`, Windows `.local/session14-candidate`에 보존하며 설치·릴리스 승인은 하지 않았다. 이 결과 문단은 후보 생성 후 작성되어 해당 후보 안에는 포함되지 않는다.

2026-10-08에는 계정·매핑 권한/잠금·입력 복구와 제한된 예약 자원 검증을 반영한 `ce1fb3655f78f83846c2fc46dba2097f30f79c02` 자료로28파일 후보2개를 만들었다. 압축 바이트·내부/외부 전체 체크섬·manifest·고정 메타데이터·원본WAR가 일치했다. 아카이브 SHA256 `7862b6e601f0bdc798237d7900cdfc5d7d53e833ea749523321ef171b0130198`(38,070,269바이트), WAR SHA256 `ae6d4ccea1d31006b635d95b8ba3bfbdfdc89fa7b82762c6147687a245693c5f`다. Java1186건 중1176통과/선택10skip·필수PG9suite 후 JSP 한 파일만 재포장했고 나머지 내부 파일은 동일함을 확인했다. 최종 JSP는 실제 브라우저·DB에서 다시 검증했다. Python284건은 Windows279통과/5skip·Linux274통과/10skip이다. Linux `.local/session15-candidate-{first,second}`, Windows `.local/session15-candidate`에 보존한다. 설치하지 않았고 `releaseApproved=false`이며 이번 실제 모델 호출은0회다. 이 결과 문단은 후보 생성 후 작성되어 해당 후보 안에는 포함되지 않는다.

2026-10-09에는 조회 조인 범위·오류/빈 페이지 복구를 반영한 `e8a4129f189ce97f55ebf7674669cd2bcde2b402` 자료로28파일 후보2개의 동일 바이트·전체 체크섬·manifest·고정 메타데이터·원본WAR를 확인했다. 아카이브 SHA256 `78e8ab862d835e24a75a293ab671db2db8a17efe20a54196308bdad592b22b77`(38,076,086바이트), WAR SHA256 `fa259c24abba00db236329bfd8fa5d5d96665b0dda67811e11ca5b8e77c410a1`이다. 최종 Java1231건 중1220통과/11선택skip·필수PG9묶음과 실제 화면을 검증했고, Python 전체시험은 도구 변경이 없어 재실행하지 않았다. Linux `.local/session16-candidate-{first,second}`, Windows `.local/session16-candidate`에 보존한다. 설치·실제모델호출은 없고 `releaseApproved=false`다. 이 결과 문단은 후보 생성 후 작성되어 해당 후보 안에는 포함되지 않는다.

2026-10-15에는 외부 오류 상태·DB연결 복구 검증을 반영한 `7c23eab612e41e317ca9669ce55f668117bc9789` 자료로28파일 후보2개의 동일 바이트·전체 체크섬·manifest·고정 메타데이터·원본WAR를 확인했다. 아카이브 SHA256 `d67d60181fe40e444a2ad9d8f8d9ae349294ee1cb6677ac5e02faf0d19633601`(38,078,290바이트), WAR SHA256 `69b235a17f0fea53d22b7ebe219a4ad51b029a405cff8947cec9e1975946621a`다. Java1249건 중1238통과/11선택skip·필수PG9묶음, Python311건 양OS, 실제65초 연결분리·요청재개와SIGTERM취소 정리를 검증했다. Linux `.local/session17-candidate-{first,second}`, Windows `.local/session17-candidate`에 보존한다. 설치·실제모델호출은 없고 `releaseApproved=false`다. 이 결과 문단은 후보 생성 후 작성되어 해당 후보 안에는 포함되지 않는다.

같은 날 COMMIT 응답 유실 검증을 반영한 `881dd1dc7bbd7fcd3566286e8790b023907fcfb6` 자료로28파일 후보2개를 만들었다. 압축 바이트·전체 체크섬·manifest·고정 메타데이터·원본 WAR가 일치했다. 아카이브 SHA256 `f710d0ebf6f7c2ebf865a6609132c9065a7a130454e35b55bf427e6c7673106b`(38,081,085바이트), WAR SHA256 `84764cb8578e842b40243db1457af81e90afc6b867319d20c7ae5a0d760186b6`다. Java1252건 중1241통과/11선택skip·필수PG9묶음, Python342건 양OS, 실제 저장 후 ACK 유실·동일 WAR 재개와 행 잠금 관측자의 SIGTERM 정리를 검증했다. Linux `.local/session18-candidate-{first,second}`, Windows `.local/session18-candidate`에 보존한다. 설치·실제 모델 호출은 없고 `releaseApproved=false`다. 이 결과 문단은 후보 생성 후 작성되어 해당 후보 안에는 포함되지 않는다.

같은 날 감사 표·운영 빈 페이지 개선을 반영한 `aa5bce99d093561d4b791e8e447291534add6e3a` 자료로28파일 후보2개를 생성했다. 압축 바이트·전체 내부/외부 체크섬·manifest·고정 메타데이터·원본 WAR 일치와 Windows 복사 해시를 확인했다. 아카이브 SHA256 `629a2a68cfdc7c8eecac79a37c0cf9bac6ab8f7f9f2f0bc4a2df7a0a7fc69805`(38,088,884바이트), WAR SHA256 `b4ee09b0067fe1c725cfae83651c3048fd4a3fc314f51fa9af1da99e8ff693f2`다. 전체Java1274건 중1263통과/11선택skip·필수PG9묶음, 합성51건과 별도실제0건의390px/1280px·키보드 화면 인수와 조회 전후 데이터 보존을 검증했다. Linux `.local/session21-candidate-{first,second}`·Windows `.local/session21-candidate`에 보존한다. 설치·실제 모델 호출은 없고 모델 선정은 보류하며 `releaseApproved=false`다. 소스 식별자는 운영자 제공 기록이고 이 결과 문단은 후보 생성 후 작성되어 해당 후보 안에는 포함되지 않는다. 최신 근거는 WORK.md를 따른다.

2026-10-16에는 관리자 양식 오류 복구와 잠금 대기 후 복구 권한 재검사를 반영한 `5af341a5624ee553c8965501ce7a9f447f08b115` 자료로28파일 후보2개를 생성했다. 압축 바이트·전체 체크섬·manifest·고정 메타데이터·원본 WAR 및 Windows 복사 해시 일치를 확인했다. 아카이브 SHA256 `44d1ba9715766d164cee7f3cdb6e1b64805be3e94959a9d5f0edcb54a96366af`(38,092,092바이트), WAR SHA256 `a9b19977c16f6cdb9d75d93ea28de9cc2af80bb20149acf834b410e815865dbe`다. 전체Java1288건 중1277통과/선택11제외·필수PG9묶음, 실제1280px Git명409와390px 중지선택/실제51위 선택 복원·거절 전후 업무 보존을 검증했다. Linux `.local/session23-candidate-{first,second}`·Windows `.local/session23-candidate`에 보존한다. 설치·실제 모델 호출은 없고 모델 선정은 보류하며 `releaseApproved=false`다. 소스 식별자는 운영자 제공 기록이고 WAR와의 별도 출처 증명은 아니다. 이 결과 문단은 후보 생성 후 작성되어 해당 후보에는 포함되지 않는다.

같은 날 브랜치 충돌 입력 이동·실제MVC500의 고정 목록 복귀·인증 재시도 안내 상한을 반영한 `e6cb4e253efd1a7791f52d2e2999d252539a5347` 자료로28파일 후보2개를 생성했다. 압축 바이트·전체 내부/외부 체크섬·manifest·고정 메타데이터·원본WAR와 Windows 복사 해시가 일치했다. 아카이브 SHA256 `7a85d20ffc4026644adf2013d5f175aa1a389a132fe95882511f69a7ee1ec7cc`(38,093,939바이트), WAR SHA256 `517a1c17e228dd824896f6f7d2a39c9f229e4131cf02e4de49b8a51801d0092d`다. 전체Java1326건 중1315통과/선택11제외·필수PG9묶음과 실제브라우저11판정·별도DB/HTTP8단계를 확인했다. 첫 검증 실패·시계 후퇴 fixture 재현과 최종 통과는 WORK.md에 구분한다. Linux `.local/session24-candidate-{first,second}`·Windows `.local/session24-candidate`에 보존하며 설치·실제 모델 호출은 없고 모델 선정은 보류한다. `releaseApproved=false`,소스 식별자는 운영자 제공 기록이고 WAR의 독립 출처 증명은 아니다. 이 결과 문단과 최종 정리 기록은 후보 생성 후 작성되어 해당 후보에는 포함되지 않는다.
