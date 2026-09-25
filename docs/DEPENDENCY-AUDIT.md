# 의존성 취약점 점검

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

PowerShell 7과 프로젝트 Maven wrapper를 사용한다. 프로젝트 루트에서 실행한다. inventory 생성은 의존성을 다운로드할 수 있으며 검사 스크립트 자체는 Maven을 실행하지 않는다.

```powershell
Push-Location source
try {
    .\mvnw.cmd dependency:list '-DoutputFile=target/dependency-list.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Dependency inventory generation failed.' }
} finally { Pop-Location }
pwsh -NoProfile -File scripts/test-dependency-scanner.ps1
pwsh -NoProfile -File scripts/check-dependencies.ps1
$LASTEXITCODE
```

- Maven이 해석한 정확한 package/version을 사용한다. test 범위는 제외하고 compile/runtime/provided를 포함한다. module 접미사와 classifier가 있는 줄도 처리한다.
- `scripts/public-maven-coordinates.txt`에 사람이 공개 출처를 확인한 정확한 package/version만 허용한다. 새 좌표나 버전은 전송 전에 중단한다. 목록 갱신 시 공개 Maven 배포물인지 확인하고, 사내 패키지·사설 버전을 추가하지 않는다. 원본 inventory 전체, 앱 소스, 저장소 주소, 자격증명은 전송하지 않는다.
- 공식 [querybatch API](https://google.github.io/osv.dev/post-v1-querybatch/)를 최대 100개씩 호출하고, 각 결과의 `next_page_token`이 없어질 때까지 해당 패키지만 재조회한다. 발견한 ID의 [상세 advisory](https://google.github.io/osv.dev/get-v1-vulns/)도 모두 받는다.
- 요청 실패·누락된 결과·잘못된 응답·반복 페이지 토큰·상세 조회 실패는 `INCOMPLETE`이다. 종료 코드는 0=조회 완료 및 일치 없음, 2=조회 완료 및 advisory 있음, 1=검사 미완료이다. advisory의 실제 적용 여부를 자동으로 판정하지 않는다.
- 원본 응답과 패키지 목록은 Git 제외 경로 `.local/security/osv-*.json`에만 저장한다. 갱신 전 결과 파일은 `osv-20260925T143427222Z.json`, 이전 inventory SHA-256은 `9019b2acb8f59161a650f1c6ab7f41b445b00398f6f9a0517c230728b7732a5a`이다.
- 오프라인 스크립트 검증 12개를 통과했다. 파싱, 공개 좌표 제한, 빈 결과, 페이지별 재조회, 토큰 반복, 네트워크 실패, 응답 개수/형식 오류, 상세 조회 실패와 부분 증거 보존을 확인했다.

## 한계와 릴리스 조건

이 검사는 특정 시점의 OSV 알려진 취약점과 Maven 해석 결과를 대조한다. 데이터베이스에 미등록된 취약점, 실제 실행 경로, 공급망 변조, 테스트/빌드 플러그인, JDK·OS·컨테이너 이미지·PostgreSQL·Ollama·LiteLLM 배포물은 별도 검증 대상이다. inventory는 매 검사 직전에 새로 생성해야 하며 오래된 파일의 검사 성공은 현재 빌드 검증이 아니다. 배포 직전 다시 검사하고 해당 구성에 관련된 공급자 보안 공지도 확인한다.
