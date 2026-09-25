# 현재 작업 상태

- 작업: 실사용 AI 소스코드 리뷰 시스템. **개발 중, 릴리스 완료 아님.**
- 이번 회차: 2026-09-26 00:13 KST 시작, 상한 약2시간 (02:13 KST).
- 기록 기준: `main` / `76b9cff`(사용자 정정4개). GitHub 메타데이터 검증 묶음 커밋 준비 중. 빈 파일 증명·관리자 이력 복구는 별도 후속 작업이다.
- 이번 완료 조건: 공개 회원가입/관리자 승인·반려/승인 우회 차단, OpenAI API 직접 연결 및 키 비노출, 필요한 목록 페이지·필터와 사용자 중심 흐름, 실제DB/화면/관련 회귀 검증.
- 병렬 범위: identity=가입승인/V7, project=프로젝트목록/흐름/V8필요시, AI=OpenAI Responses, root=전체UI/문서/실제PG통합/중앙Maven.
- 확정 요구사항: `docs/REQUIREMENTS.md`. 내부 이슈함 우선, 최초 전체 이력, Java25/Spring/Maven/JSP/PostgreSQL 유지.
- 재개 시 이 문서와 실제 Git 상태를 대조하고 아래 다음 작업부터 진행한다. 모든 릴리스 항목 검증 전 완료라고 보고하지 않는다.

## 구현한 내용

- Maven Wrapper/WAR, PostgreSQL Flyway V1~V9, JSP/JSTL 반응형 화면.
- 공개 회원가입→관리자 승인/반려/재검토, 승인 전 로그인·활성화 우회 차단, 중복 접수 동일 안내, 가입 IP 제한. 관리자 직접 계정 생성 웹 기능 제거, 최초 bootstrap 유지.
- ADMIN 계정 활성/초기화, USER 로그인/비밀번호, BCrypt12·CSRF·CSP·로그인 제한·세션 폐기·동시 마지막 관리자 보호.
- clone 형태 HTTP(S) URL 프로젝트 신청·승인/반려/중지/재개·소유권 검사.
- GitHub/GitLab 전체 이력·부모 그래프·diff 완전성 검사, Ollama/LiteLLM 엄격한 구조화 출력 검증.
- 매 정시/수동 백그라운드 리뷰, 프로젝트별 PG advisory lock, 커밋별 리뷰/이슈 원자 저장, 실패 후 재사용과 중복 방지.
- 큰 merge를 여러 배치로 처리하는 명시적 checkpoint, 저장된 SHA는 quota/diff/AI 호출에서 제외. force-push/누락/안전 한도 초과는 실패.
- 내부 이슈함·상태·배정 사유, 관리자 origin/email 작성자 매핑, 서버별 복수 Git 토큰 격리, 감사 기록.
- 검증된 EMPTY/METADATA_ONLY를 본문 AI 검토와 구분하고 수동 확인 범위 표시. 일반 누락을 제외 성공으로 처리하지 않는다.
- GitHub rename/0행 변경 후보도 불변 현재/첫 부모 tree로 전체 변경 경로·blob·모드를 확인한다. 동일 blob만 메타데이터 처리하며 copy/submodule/잘림/누락은 실패한다. 일반 본문-only 경로의 tree 증명 확대는 별도다.
- 실행/커밋 기록 독립50건 페이지, 이슈/리뷰의 안전한 원본 커밋 링크.
- OpenAI Responses 직접 연결: 별도 OPENAI_MODEL/OPENAI_API_KEY, 고정 공식 HTTPS 목적지, strict JSON·완료/거절/도구/크기·시간 검증. 유료 실제 연결은 미실행.
- 프로젝트 검색/승인 상태50건, 사용자 승인함/검색50명, 이슈25건 짧은 목록+권한 재검사 상세. 상태 변경 후 검색/페이지 유지. 예약 후보도 큐 용량까지만 SQL 조회.
- 한국어 상태·역할별 메뉴·URL 중심 등록·안전한 입력 복원·키보드/모바일 흐름. 예약이 꺼져 있으면 수동 실행 안내. JSP fragment 한글 인코딩 수정.
- Git tracked/index 비밀정보 검사와 CI gate: OpenAI 키·개인키·env 파일 차단, 값 비출력, 링크/reparse point/읽기 오류 실패 처리.
- 격리 PG 자동 검증/백업복원, Java25·PG17 CI 정의와 필수DB검증 gate, OSV 의존성 검사.

## 최신 실제 검증

- GitHub 메타데이터40개 fixture 추가 후 전체487건 중483통과/외부 선택4skip, 실제PG/HTTP 및8개 테이블 백업복원 통과. 독립 코드 리뷰 통과. GitHub metadata 실서비스 호출은 아직 미실행.
- 이번 묶음은 staged index를 `.local/session2-staged-verify`로 내보내 병렬 Git 작업과 분리해 검증했다. 격리 PG + 전체447건 중443통과/외부 선택4skip, 실제HTTP12건·OpenAI fixture70건 포함. 최종 fragment 한글·예약OFF 화면 회귀도 통과.8개 테이블 백업/복원·identity 검증 통과.
- Python23건(비밀정보 검사17+보고 gate6), actionlint 통과. staged 비밀정보 검사 발견0, diff 검사 및 범위별 독립 리뷰 통과.
- 실제 WAR 브라우저: 가입 오류의 ID/Git값 복원·비밀번호 비표시, 가입 접수·승인 전 로그인 실패·관리자 승인·사용자 로그인·URL 등록·프로젝트 승인 확인. 프로젝트 검색/상태50→2, 이슈25→2·상세·해결 후 원래 page/filter 복원.390px 모바일 해당 화면 가로 넘침 없음, 관리자 메뉴 Enter 조작 확인. 합성 데이터만 사용했고 외부 AI/Git 호출 없음.

### 이전 회차 검증(현재 회차 전체 테스트와 구분)

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

0. 이번 요청의4개 정정과 GitHub 동일 blob 메타데이터 검증까지 수행했다. 정규 빈 파일의 생성/삭제 증명과 관리자 force-push 진행기준 복구 기능을 후속 구현 중이다.
1. `docs/REVIEW-COVERAGE-PLAN.md` 나머지 제안의 파일별 범위 계약, binary/큰 diff 처리. 미검토 파일의 수동 이슈 후 계속/중단 정책을 사용자에게 질문했으며 답변 전 보수적 중단을 유지한다.
2. 운영 모델/설정 품질 개선과 독립 평가 확대. 실제 설치형 GitLab·비공개 GitHub·LiteLLM 연결 검증.
3. 대형 저장소 부하·force-push 관리자 복구·queue 지속성/공정성·장애 복구·모니터링/보존 정책 구현·검증.
4. 배포 환경 답변 확인 후 패키지/설치·업데이트·백업·롤백 구성, 운영 보안·원격CI·사용자 인수 검사. `docs/RELEASE-CHECKLIST.md` 기준.

## 재개·운영 메모

- 사용자에게 운영 대상(Linux Docker Compose / Windows / 기존 환경)을 질문했으며 아직 미확정.
- origin: jang-sw/code-reviewer_by-codex. main 직접 푸시 별도 승인 없음 → 로컬 커밋만, 푸시/배포 없음.
- `.local/pg-test`: 개발검증 전용127.0.0.1:55432. `.local/pg-validation`: 테스트 스크립트55439. 두 검증 클러스터 모두 종료했다. 설치된 다른 PG 서비스는 변경하지 않음.
- `.local`은 Git 제외. 합성 UI 검증 WAR127.0.0.1:18080도 종료했고 임시 브라우저 탭/viewport를 정리했다. 사용자 Ollama 서비스는 그대로 둠.
- 런타임 Git 수집은 매번 pinned 전체 이력을 재검증, 기본1000페이지/metadata32MiB/저장SHA131072개 안전 한도. 제한을 자동 확장/절삭하지 않는다.
