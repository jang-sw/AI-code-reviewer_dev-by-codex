# 의존성 취약점 점검

## 배포할 WAR를 먼저 검사

`scripts/check-war-dependencies.py`는 네트워크 없이 실제 WAR의 `WEB-INF/lib`·`WEB-INF/lib-provided` 전체를 Maven 목록과 대조한다. `check-dependencies.ps1`은 이 검사가 성공한 경우에만 OSV를 조회한다. WAR 없이 inventory만 검사하거나 이름만 맞는 JAR를 통과시키는 경로는 없다.

- 검증한 빌드 기록에서 받은 기대 WAR SHA256이 필요하다. 현재 소스 POM과 WAR 안의 애플리케이션 POM은 바이트가 같아야 한다. WAR·inventory·POM·허용 목록·보충 정책의 해시를 보고서에 함께 남긴다. 이 연결은 소스 커밋의 서명이나 Maven 재빌드 재현성을 증명하지 않는다.
- 일반 JAR는 정확한 버전·classifier의 로컬 Maven 캐시 파일과 바이트가 같아야 한다. 캐시가 없거나 다르면 실패한다. 내부 `pom.properties`가 있으면 예상 좌표 한 개와 정확히 일치해야 한다. 현재 WAR의 메타데이터 없는 일반48개도 캐시 바이트로 확인하며 파일명만으로 통과시키지 않는다.
- 목록 밖 JAR는 `scripts/packaged-maven-artifacts.json`의 정확한 경로·크기·SHA256과 `public-maven-coordinates.txt`의 공개 좌표 승인을 모두 요구한다. 현재 보충 항목은 공식 체크섬을 확인한 Boot4.0.8 jarmode-tools 한 개다. 출처 URL은 해당 GAV의 공식 Maven Central POM과 같아야 하며 검사 중 URL을 열거나 자동으로 승인하지 않는다. 사용하지 않은 정책 항목은 OSV에 추가하지 않는다.
- 중복/별칭 경로·중복 JAR 이름·모호한 좌표·중첩 하위경로·일반 파일·손상 ZIP·실제 압축 해제량 초과·POM의 DTD/ENTITY·UTF-16/NUL 입력은 거절한다. 제공 범위와 inventory에만 있는 starter도 조회 범위에 유지한다. classifier별 파일 대조와 OSV의 GAV 중복 제거는 구분한다.
- Python 성공의 `COMPLETE`는 로컬 JAR 대응 검사가 끝났다는 뜻이다. 보안 일치0건은 별도 OSV 응답을 끝까지 처리한 결합 보고서에서만 판단한다. 로컬 검사 실패는 OSV 전 차단하고, 결합 종료 코드는 기존처럼0/2/1을 유지한다. 유효한 옵션 형식으로 실행한 검사 오류에는 비공개 좌표·입력 경로·원문 예외를 출력하지 않는다. 명령행 옵션 자체가 잘못되면 Python의 인자 안내가 해당 옵션 값을 표시할 수 있으므로 비밀을 명령행에 넣지 않는다.

CI는 Java 검증 뒤 새 Maven 목록과 이 오프라인 검사를 실행하고, 같은 WAR 해시를 후속 배포물 검사와 후보 포장에 사용한다. 새 체크아웃의 보고서 디렉토리를 준비하고 기존 inventory/report 또는 링크를 거절한다. OSV 실시간 호출은 일반 CI에 추가하지 않았다. 원격 CI 실행은 별도 항목이다.

## 2026-10-16 화면 수정 WAR의 오프라인 재검증

역할별 안내 수정 후 새 검증 WAR `41da585d80382dba9f08cda51efd5e1da21987129b5860fed97f30bacb822c4f`/42,081,247바이트를 Windows·Linux에서 검사했다. 전체Java1338건(1327통과/선택11제외)·필수PG9묶음이 통과한 빌드 기록과 source235파일·현재 POM을 대조했다.

- Windows2.938초·Linux29.884초의 오프라인 검사는 일반75JAR의 캐시 바이트와 승인된 도구1개, 제공 범위7개·목록만 있는12좌표를 확인했다. 이전87좌표 inventory와 현재 POM은 동일하며 조회 대상은88좌표다. 새 Maven 실행·네트워크·OSV 조회는 없다.
- 보존한 이전 WAR와 새 WAR의 내부76JAR는 경로·크기·SHA256이 모두 같다. 아래04:11의88좌표/0advisory는 **과거 조회 결과**이며 이번 검사를 새 취약점 조회로 표시하지 않는다.
- `.local/session27-artifact-windows.json`과 `session27-artifact-linux.json`은 각 새 검사·전체 JAR 대응·빌드/소스/WAR·과거 조회의 해시를 기록한다. Linux 보고서는 양OS 바이트가 같다. 이전 후보·보안 조회 원본을 보존했다.

## 2026-10-16 재사용 검사 실제 검증

기존 Java 검증을 통과한 WAR `9e207c8b5d4a405b15648d16763c3d108143d0c24207fece8ecf647eeeabca5f`를 사용했다. 앱 소스234개와 POM은 해당 빌드 기록과 바이트가 같으며 이번에는 Java를 다시 빌드하지 않았다.

- Python의 독립 반례44개와 PowerShell 오프라인21개를 통과했다. 전체Python은 Windows409개 중404통과/환경5skip, Linux409개 중399통과/환경10skip이며 실패/오류0이다. Linux 원본 로그와 입력 해시는 양OS `.local/session26-linux-python.{log,json}`에 보존했다. 반례는 캐시 누락/변조·추가 JAR 미승인·비공개 좌표·제공 범위·classifier·POM 불일치/ENTITY·위조 ZIP 해제량·출력 보존과 로컬 실패 시 mock transport0회를 포함한다.
- Linux에서 실제 CI Bash 단계를 새 UUID 디렉토리와 없는 `.local`에서 실행했다. 새 Maven 목록87좌표와 WAR76JAR를 대조해 **일반75개 전부 캐시 바이트 일치+승인된 도구1개**, 조회 대상88좌표·목록에만 있는12좌표를 확인했다. 후속 단계의 WAR 해시 출력과 요약 JSON도 일치했다. 같은 명령의 재실행은 Maven 실행 전에 거절했고 최초 파일의 바이트/식별자/수정 시각을 보존했다. `.local/session26-ci-step.json` PASS/5.853초다. Maven의 공개 의존성 해석과 Python 검사의 네트워크 없음은 구분하며 원격 CI를 실행한 결과는 아니다.
- Windows는 공개 Maven Central 캐시 준비와 별도 새 **오프라인** 목록 생성을 구분했다. 실제 결합 CLI가 다시75개 전체 캐시 바이트+도구1개를 확인한 뒤 **04:11:21–04:11:24 UTC OSV88좌표 COMPLETE/0advisory/종료0**을 반환했다. 원본은 `.local/security/osv-20261016T041121243Z-0a4b86a4d13940e7bca2607e15b7f661.json`, SHA256 `838e8203cd19562111f7d69c0a8eaf8952b0cb8c1a64d10b512ec86e3d28d6a0`이다. 새 목록 SHA256은 `da51034d85167857590ed48b8e499cff07b884b173a71738ebfb251d6cf173d9`이며 Linux와 좌표 집합은 같다.
- 실제 CLI의 기대 해시 누락과 빈 캐시 두 실행은 각각 INCOMPLETE/종료1이었다. 패킷을 계측하지는 않았으며, 보고서의 패키지/조회 페이지 없음과 별도의 mock transport0 검증을 근거로 구분한다. 이전 OSV 원본6개는 해시가 그대로다. `.local/security/session26-windows-audit.json`에 전체17.36초 실행·도구/입력 해시·각 원본 보고서를 기록했다. 실제 모델/유료 호출과 서비스 설치는 없다.

## 2026-10-16 현재 후보 재조회와 Jackson 보안 패치

03:34:11–03:34:14 UTC에 기존 검증 WAR의 공개88좌표를 OSV 공식 API로 다시 조회했다. **COMPLETE/FINDINGS, 고유 advisory7개·패키지별 일치14개, 종료 코드2**다. 9월25일의0건 결과를 현재 후보의 보안 통과로 재사용하지 않는다.

- 원본 보고서: `.local/security/osv-20261016T033411053Z.json` (SHA256 `988aeac3067a062d7521a85b6ffa37e0ed9bfa21aa07125824f1d96faf7999e6`). 별도 `.local/security/session25e-dependency-audit.json`에 전체 응답 처리·정확한 좌표/범위·원본 보존을 대조했다.
- 대상은 `4a27dac`의 WAR SHA256 `4087d142fdf847d35dca123d227dc290423e437212ad36ffaa54c2f33b53b690`이며, source234파일·빌드 POM·WAR 내 POM과 후보28파일 체크섬을 연결했다. Windows의 오래된 `source/target` WAR는 사용하지 않았다.
- 새 오프라인 Maven inventory는87좌표(compile74/runtime4/provided9)로 이전과 바이트가 같았지만, 현재 OSV 결과는 달랐다. 실제 WAR의 JAR76개(일반69/provided7)를 대조해 Maven 목록에서 빠지는 `org.springframework.boot:spring-boot-jarmode-tools:4.0.8`을 찾았고, 명시적으로 runtime1좌표를 추가한 별도88좌표 inventory를 사용했다. 원본87좌표 파일은 보존했다.
- 추가 도구의 [공식 POM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-jarmode-tools/4.0.8/spring-boot-jarmode-tools-4.0.8.pom)·공개 SHA256/SHA512/SHA1과 WAR 안의55,149바이트 파일이 일치했다. Windows 캐시 `spring-boot-loader-tools` 내부 리소스도 바이트가 같다. 내부 `pom.properties`는 없으며 MANIFEST의4.0.8과 공식 체크섬으로 연결했다. 나머지75개는 캐시 JAR와 일치했고, 목록에만 있는 starter12좌표도 조회에서 제외하지 않았다. [Spring Boot4.0.8의 도구 자동 포함 설명](https://docs.spring.io/spring-boot/4.0/maven-plugin/packaging.html#packaging.repackage-goal.include-tools)
- 조회 도구의 기존 오프라인12검증은 모두 통과했다. 공개 허용 목록의 정확한 패키지·버전만 전송했으며 앱 소스·저장소 주소·키는 전송하지 않았다. 도구와 후보는 수정·설치하지 않았고17.609초에 조회를 마쳤다.

반환된 항목은 양쪽 Jackson `core`/`databind`의2.21.5·3.1.5에 해당한다. 아래는 공급자 공지의 조건을 요약한 것이며 앱에서 취약점을 재현했다는 뜻이 아니다.

| 공급자 공지 | 주요 조건 | 현재 계열 수정 버전 |
| --- | --- | --- |
| [GHSA-7hhh-6rmp-j9qf](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-7hhh-6rmp-j9qf) | DataInput 파서의 잘못된 토큰에 따른 메모리 증가 | 2.21.7 / 3.1.7 |
| [GHSA-p6pp-m3f8-5c89](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-p6pp-m3f8-5c89) | 숫자 변환의 정규식 처리 비용 증가 | 2.21.7 / 3.1.7 |
| [GHSA-cxp5-3px4-pw24](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-cxp5-3px4-pw24) | 객체 ID 참조를 포함한 컬렉션·맵 역직렬화 | 2.21.7 / 3.1.7 |
| [GHSA-wv8q-qhhj-9h54](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wv8q-qhhj-9h54) | 이름 기반 다형성·대체 구현의 알 수 없는 ID 캐시 | 2.21.7 / 3.1.7 |
| [GHSA-gx83-3vf8-gh7j](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-gx83-3vf8-gh7j) | Comparable 다형성과 기본 검증기의 불완전한 제한 | 2.21.6 / 3.1.6 |
| [GHSA-q4xh-88c3-wmh7](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-q4xh-88c3-wmh7) | XML Duration·Calendar 문자열 변환 | 2.21.6 / 3.1.6 |
| [GHSA-wjgm-6hv5-3cvf](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wjgm-6hv5-3cvf) | Path 바인딩과 추가 파일시스템 공급자의 부작용 | 2.21.6 / 3.1.6 |

앱 직접 코드는 주로3계열의 `readTree(String)`과 응답 크기 제한을 사용한다. 위 특수 바인딩 경로는 정적 검색에서 확인되지 않았지만, Boot 내부와2계열 전이 의존성까지 미영향으로 판단할 근거는 부족하다. 면제하지 않고 **Jackson2 BOM2.21.7 / Jackson3 BOM3.1.7**로 패치했다. 두 BOM과 core/databind4개 공개 POM의 정확한 버전을 Maven Central에서 확인했다. Boot4.0.8과 Tomcat11.0.26, annotations2.21은 유지한다.

패치 후 WSL 전체 Java1334건 중1323통과/선택11skip·실패/오류0, 필수PostgreSQL9묶음과 실제 두 WAR 공유 인증을289.279초에 통과했다. 새 WAR는 **SHA256 `9e207c8b5d4a405b15648d16763c3d108143d0c24207fece8ecf647eeeabca5f` /42,081,083바이트**다. 새source234파일과 빌드snapshot/POM·WAR내POM을 묶고, 원본87좌표에서 core/databind4개 버전만 바뀐 것을 대조했다. 새 JAR76개 모두 대응되었고 Windows캐시 비교는 기존71개에만 해당한다. 새 Jackson4개는 파일명·내부 Maven속성, 패키징 도구1개는 앞서 확인한 공식 해시·MANIFEST로 연결했다. 전체 공급망 서명 검증은 아니다.

최초 WSL 오프라인 목록 생성은 Dependency Plugin3.9.0이 캐시에 없어 실패했다. 원래 빈 파일·로그와 `.local/security/session25f-inventory-failure.json`을 보존했다. 별도25fr 실행에서 공개 플러그인을90초 상한의 온라인 `dependency:help`로 받은 뒤, 새60초 상한의 **오프라인** `dependency:list`를 실행했다. 온라인 준비와 오프라인 목록 생성은 `.local/security/session25f-native-inventory.json`에 구분하며14.623초에 완료했다. 실패를 통과로 바꾸거나 원본 출력을 덮어쓰지 않았다.

**03:46:01–03:46:02 UTC 새88좌표 재조회는 COMPLETE·advisory0개·종료0**이다. `.local/security/osv-20261016T034601315Z.json` (SHA256 `5dad50501f5e81ef7f6d2b281ef74aff3b401b7d82ad9de011a17002ff5b00d8`), 원본87좌표 SHA256 `a69ce7f22a9f9f853951b6039b6fd9a52775cbeb57395676ed2a89c89359da3a`, 별도88좌표 SHA256 `7e59be758c808319c48b448d2f99646e1dfe251c58ab56675a8bcba501de7a9c`다. `.local/security/session25f-dependency-audit.json`에서 정확한 응답 개수·목록 해시·기존7개발견 원본 보존과 도구12검증을 대조했다. 이는 해당 시점의 OSV 조회 결과이며 후보 생성·운영 보안 전체 승인과 구분한다. 새 WAR의 양OS52포트·소유PG/WAR/PID/lock 부재도 별도로 확인했다. 브라우저 인수는 이전B WAR에서 수행한 명시 범위이며 새 패치 뒤 전체 화면 인수를 재실행하지 않았다.

위 초기 도구는 해당 후보에 맞춘 명시 검증이었다. 이후 일반 `dependency:list`가 누락한 추가 JAR를 별도 승인 정책과 함께 검사하는 재사용 절차를 구현했으며 아래 명령을 따른다. 빌드 플러그인·JDK/OS/DB/AI 서버·공급망 전체 검증은 이 결과의 범위가 아니다. 10월16일 확인한 [Apache Tomcat 보안 공지](https://tomcat.apache.org/security-11.html)의 최신 수정 절은11.0.26이었으며 이후 공지를 확인하지 못했다는 것이 새 취약점 부재의 보장은 아니다.

## 2026-09-25 갱신 후 결과

Tomcat을 11.0.26으로 갱신하고 Maven inventory를 다시 생성한 뒤, **14:39:13–14:39:14 UTC 검사에서 운영 의존성 87개에 대한 OSV 일치 항목은 0개였다.** 응답 전체를 처리했고 종료 코드 0을 확인했다. 이는 해당 시점의 알려진 항목 조회 결과이며 보안 결함이 없다는 증명은 아니다.

생성된 `source/target/ai-code-reviewer.war` 내부도 직접 확인했다. `tomcat-embed-el`은 `WEB-INF/lib`, core·jasper·websocket·annotations는 `WEB-INF/lib-provided`에 모두 11.0.26으로 들어 있다. Jasper의 공개 전이 의존성 `org.eclipse.jdt:ecj`는 3.46.0으로 함께 변경되었다. 제공 범위 라이브러리까지 검사했으므로 실행형 WAR에서 사용하는 Tomcat을 누락하지 않았다.

- 갱신 후 원본 보고서: `.local/security/osv-20260925T143913956Z.json`
- inventory SHA-256: `ec60b4ff5468214364b6694828a4458aa0b5b034bae576928db6a2b15962ade3`
- 검사한 WAR SHA-256: `29eba51e7f08dcc2202e033aeb66a4162673d7f2afc0bddf4132178747996bd9`
- 갱신 직후 첫 실행은 미승인 ECJ 버전에서 네트워크 요청 전 중단되었다. [Tomcat Jasper 공개 POM](https://repo.maven.apache.org/maven2/org/apache/tomcat/embed/tomcat-embed-jasper/11.0.26/tomcat-embed-jasper-11.0.26.pom)과 [ECJ 공개 POM](https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.46.0/ecj-3.46.0.pom)을 확인한 뒤 허용 목록을 갱신하고 다시 검사했다.

## 갱신 전 발견 내용

2026-09-25 14:34:27–14:34:29 UTC에 OSV 공식 API 조회를 완료했다. 운영 의존성 87개(compile 74, runtime 4, provided 9) 중 `org.apache.tomcat.embed:tomcat-embed-core:11.0.24`에서 아래 3개 advisory가 반환되었다. 실행형 WAR에 포함되는 provided Tomcat도 검사했다. 다른 86개 좌표에서는 해당 시점의 OSV 일치 항목이 없었다.

| OSV advisory / CVE | 문제와 현재 구성 검토 |
| --- | --- |
| [GHSA-9xv2-5v5q-p794](https://osv.dev/vulnerability/GHSA-9xv2-5v5q-p794) / CVE-2026-65905 | Tomcat DIGEST 인증 재전송 문제. 현재 애플리케이션은 Spring Security 폼 인증을 사용하며 Tomcat DIGEST 설정은 없다. |
| [GHSA-gcx9-497g-6cp6](https://osv.dev/vulnerability/GHSA-gcx9-497g-6cp6) / CVE-2026-65182 | Tomcat의 중첩 경로 보안 제약 처리 문제. 현재 경로 권한은 Spring Security 필터에서 검사하며 container security-constraint 설정은 없다. |
| [GHSA-h3x4-894j-xpx5](https://osv.dev/vulnerability/GHSA-h3x4-894j-xpx5) / CVE-2026-68525 | Tomcat FORM 인증의 HTTP 메서드 제약 우회. 현재 Spring Security formLogin은 Tomcat FORM authenticator 설정과 다르다. |

위 구성 평가는 저장소 `SecurityConfiguration.java`와 서버 설정의 정적 확인에 근거하며 취약점 재현 시험이나 운영 배포 구성의 안전성 증명이 아니다. OSV의 GitHub advisory 등급은 모두 CRITICAL이지만, Apache 자체 평가는 각각 Low, Important, Low이다. 세 항목은 Tomcat 11.0.25에서 수정되었다. [Apache 보안 공지](https://tomcat.apache.org/security-11.html#Fixed_in_Apache_Tomcat_11.0.25)

OSV만으로 최신 공지를 모두 찾지는 못했다. Apache는 2026-09-23에 HTTP/2 헤더 혼동, HTTP/1.0 Transfer-Encoding 처리 등 추가 문제를 공개했고 11.0.26에서 수정했다고 명시한다. 특히 HTTP/1.0 문제는 역방향 프록시 운영과 관련이 있다. 이 공급자 공지를 근거로 11.0.25 대신 **Tomcat 11.0.26으로 전체 관련 모듈을 함께 갱신했다.** 후속 공지가 최초 OSV 조회에 포함되었다고 주장하지 않는다. [Apache 최신 수정 공지](https://tomcat.apache.org/security-11.html#Fixed_in_Apache_Tomcat_11.0.26)

## 방법과 재현

Python3.10 이상과 PowerShell7, 프로젝트 Maven wrapper를 사용한다. 프로젝트 루트에서 실행한다. inventory 생성은 의존성을 다운로드할 수 있으며 검사 스크립트 자체는 Maven을 실행하지 않는다. `APPROVED_64_HEX_WAR_DIGEST`는 독립적으로 확인한 빌드 기록의 값으로 바꾼다. 기존 파일을 재사용하지 않도록 매번 새 inventory 경로를 사용한다.

```powershell
$reviewWar = Join-Path $PWD 'source/target/ai-code-reviewer.war'
$reviewInventory = Join-Path $PWD ('source/target/dependency-' + [Guid]::NewGuid().ToString('N') + '.txt')
if (Test-Path -LiteralPath $reviewInventory) { throw 'A fresh inventory path is required.' }
Push-Location source
try {
    .\mvnw.cmd -B -ntp org.apache.maven.plugins:maven-dependency-plugin:3.9.0:list "-DoutputFile=$reviewInventory"
    if ($LASTEXITCODE -ne 0) { throw 'Dependency inventory generation failed.' }
} finally { Pop-Location }
pwsh -NoProfile -File scripts/test-dependency-scanner.ps1
pwsh -NoProfile -File scripts/check-dependencies.ps1 `
    -WarFile $reviewWar -ExpectedWarSha256 APPROVED_64_HEX_WAR_DIGEST `
    -InventoryFile $reviewInventory
$LASTEXITCODE
```

- 기본 Maven 캐시는 현재 사용자 `.m2/repository`다. 다른 빌드 캐시를 사용했다면 `-MavenRepository`로 정확한 로컬 경로를 명시한다. Linux PowerShell에서는 `-PythonCommand python3`를 지정할 수 있다. Python을 찾지 못하거나 캐시가 불완전하면 조회를 시작하지 않는다.
- Maven이 해석한 정확한 package/version을 사용한다. test 범위는 제외하고 compile/runtime/provided를 포함한다. module 접미사와 classifier가 있는 줄도 처리한다.
- `scripts/public-maven-coordinates.txt`에 사람이 공개 출처를 확인한 정확한 package/version만 허용한다. 새 좌표나 버전은 전송 전에 중단한다. 목록 갱신 시 공개 Maven 배포물인지 확인하고, 사내 패키지·사설 버전을 추가하지 않는다. 원본 inventory 전체, 앱 소스, 저장소 주소, 자격증명은 전송하지 않는다.
- 공식 [querybatch API](https://google.github.io/osv.dev/post-v1-querybatch/)를 최대 100개씩 호출하고, 각 결과의 `next_page_token`이 없어질 때까지 해당 패키지만 재조회한다. 발견한 ID의 [상세 advisory](https://google.github.io/osv.dev/get-v1-vulns/)도 모두 받는다.
- 요청 실패·누락된 결과·잘못된 응답·반복 페이지 토큰·상세 조회 실패는 `INCOMPLETE`이다. 종료 코드는 0=조회 완료 및 일치 없음, 2=조회 완료 및 advisory 있음, 1=검사 미완료이다. advisory의 실제 적용 여부를 자동으로 판정하지 않는다.
- 원본 응답과 패키지 목록은 Git 제외 경로 `.local/security/osv-<UTC>-<GUID>.json`에 독점 생성한다. 보고서 조상의 링크/junction과 기존 파일은 거절한다. 과거 보고서를 덮어쓰지 않으며 부분 응답도 보존한다. 갱신 전 결과 파일은 `osv-20260925T143427222Z.json`, 이전 inventory SHA256은 `9019b2acb8f59161a650f1c6ab7f41b445b00398f6f9a0517c230728b7732a5a`이다.
- Python 로컬 반례44개와 PowerShell 오프라인21개(기존12개 포함)를 통과했다. 실제 OSV 조회와 모의 transport0 검증은 구분한다.

로컬 대응 검사만 실행하려면 같은 기대 해시·새 inventory·빌드 캐시를 Python에 직접 전달한다. 다음 예의 경로는 검증 환경의 실제 값으로 바꾼다. 출력 파일은 아직 존재하지 않아야 하며 부모는 실제 디렉토리여야 한다.

```sh
python3 scripts/check-war-dependencies.py \
  --war-file /absolute/build/ai-code-reviewer.war \
  --expected-war-sha256 APPROVED_64_HEX_WAR_DIGEST \
  --inventory-file /absolute/build/fresh-dependencies.txt \
  --pom-file /absolute/checkout/source/pom.xml \
  --approved-file /absolute/checkout/scripts/public-maven-coordinates.txt \
  --supplemental-file /absolute/checkout/scripts/packaged-maven-artifacts.json \
  --maven-repository /absolute/user/.m2/repository \
  --report-file /absolute/results/new-war-coverage.json
```

## 한계와 릴리스 조건

이 검사는 특정 시점의 OSV 알려진 취약점과 Maven 해석 결과·WAR의 두 라이브러리 디렉토리를 대조한다. 데이터베이스에 미등록된 취약점, 실제 실행 경로, 서명/공급망 변조, 직접 포함된 제3자 class·외부 Class-Path·외부 서블릿 컨테이너·동적 라이브러리, 테스트/빌드 플러그인, JDK·OS·PostgreSQL·Ollama·LiteLLM 배포물은 별도 검증 대상이다. ZIP의 단일 실제 해제량과 파일 크기/개수는 제한하지만 ZIP 중앙 디렉토리를 읽는 라이브러리의 모든 메모리 할당에 절대 상한을 보장하지 않는다. inventory는 매 검사 직전에 새로 생성해야 하며 오래된 파일의 검사 성공은 현재 빌드 검증이 아니다. OSV는 요청별60초/페이지 횟수 제한을 사용하고 실행 환경의 전체 시간 제한도 함께 적용한다. 배포 직전 다시 검사하고 해당 구성에 관련된 공급자 보안 공지도 확인한다.
