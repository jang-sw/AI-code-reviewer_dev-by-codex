# 릴리스 완료 기준

모든 필수 항목의 구현과 검증을 마치고 실제 환경의 제한을 해소한 뒤에만 릴리스 가능/완료라고 보고한다. 체크는 증거가 있을 때만 변경한다.

## 기능과 안전성

- [x] 공개 회원가입·관리자 승인/반려/재검토, 승인 전 로그인·권한 우회 차단
- [x] 관리자·일반 사용자, 비밀번호·세션 폐기·마지막 관리자 보호
- [x] Git URL 등록, 관리자 승인/반려/일시정지, 소유권 확인
- [x] 시간별 예약과 수동 백그라운드 실행, 프로젝트 중복 잠금
- [x] 전체 이력의 안전한 배치, 실패 재시도·이슈 중복 방지·진행 지점
- [x] GitHub/GitLab API adapter, Ollama/LiteLLM adapter와 실패 fixture 테스트
- [x] OpenAI Responses 직접 adapter·전용 키 격리·완료/거절/크기/리디렉션 fixture
- [x] 커밋/index 비밀정보 검사와 CI 연결, 실제 값 비출력·링크 우회 방지 회귀
- [x] 내부 이슈함·상태 변경·배정 대체 사유·감사 기록
- [x] CSRF·출력 escaping·CSP·로그인 제한·비밀정보 로그 차단
- [x] Git 작성자 origin/email 관리자 매핑·배정 근거, 실제 PostgreSQL 동작 검증
- [x] 복수 비공개 호스트 credential 구성·격리 fixture·회전 절차
- [x] 큰 merge 그룹의 여러 배치 처리·저장 SHA 재사용·명시적 안전 checkpoint 회귀 검증
- [x] 실행/커밋 기록 페이지 처리·안전한 원본 커밋 링크·실제 HTTP 권한 검증
- [x] 프로젝트/사용자 검색·페이지, 이슈25건 요약/권한 상세, 필터 유지·예약 SQL 조회 상한
- [ ] 바이너리/rename/mode-only·큰 소스 변경 처리와 검토 제외 범위 표시
- [ ] 대형 저장소 이력 부하·API 예산 검증, force-push 복구 절차/UI
- [ ] 운영에 맞는 queue 지속성·일정 지연/복구 정책·공정성

## 검증

- [x] 실제 PostgreSQL migration·배치 재시도·SQL 실패 롤백·advisory lock
- [x] 실제 Tomcat HTTP/JSP 로그인·CSRF·소유권·비동기 수동 리뷰 저장
- [x] 인코딩된 로그인 URL 제한 우회 회귀, 동시 마지막 관리자 보호
- [x] 공개 GitHub 전체 이력·재개 smoke
- [x] 공식 공개 GitLab fixture pinned 이력·루트 diff·재개 smoke (비공개/설치형 검증과 별개)
- [x] 설치된 Ollama gemma3:1b JSON 프로토콜 smoke
- [ ] 데스크톱·모바일 시각 확인, 키보드 사용성
- [x] 가입·승인·등록·페이지/상세·상태 저장 주요 흐름의 실제 WAR/모바일 검증
- [ ] 실제 설치형 GitLab·비공개 GitHub·LiteLLM 연결
- [ ] 실제 OpenAI 계정/모델 인증·응답 호환·비용·품질 검증 (유료 호출 미실행)
- [ ] 운영 모델 품질 합격 (Gemma 오탐·설명 미달, Llama8B 시간 초과·근거 미달)
- [ ] 장시간/부하·API rate limit·프로세스/DB/네트워크 장애 복구 실증
- [x] 운영 Maven 의존성 OSV·공급자 공지 확인, Tomcat 수정 후 재검사
- [ ] JDK/OS/DB/AI 서버·배포 이미지 보안 점검 및 잔여 위험 결정
- [x] 필수 PostgreSQL 검증을 강제하는 CI 구성·정적 검증
- [ ] 실제 원격 CI 실행

## 운영과 인계

- [x] 기본 설치·환경변수·실행·검증 문서, Maven checksum 고정
- [x] 격리 PostgreSQL 백업/복원 데이터·시퀀스 복구 테스트
- [ ] 운영 백업 권한·암호화·보존/개인정보 삭제 정책
- [ ] HTTPS·신뢰 프록시·DB 최소권한·secrets·다중 인스턴스 로그인 제한 검증
- [ ] 실패/지연 알림·진행률·health/metrics·로그 보존
- [ ] 배포 패키지/버전·릴리스 노트·마이그레이션 업그레이드/롤백 확인
- [ ] 사용자 인수 테스트·운영 배포 별도 승인
