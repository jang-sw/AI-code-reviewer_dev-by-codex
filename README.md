# AI Code Reviewer

GitHub·설치형 GitLab 저장소 주소를 등록하고, 승인된 프로젝트의 전체 커밋 이력과 새 변경을 매시간 AI로 리뷰하는 웹 애플리케이션입니다. Ollama, LiteLLM, OpenAI API 직접 연결을 지원하며, 수정 권고를 내부 사용자 이슈함에 배정합니다. 사용자가 회원가입과 프로젝트 등록을 신청하고 관리자가 승인합니다.

**현재 상태: 개발 중. 운영 릴리스 전 필수 검증과 기능 보완이 남아 있습니다.**

- [요구사항과 작업 방식](docs/REQUIREMENTS.md)
- [구조·실행·검증](PROJECT.md)
- [현재 진행 상태와 다음 작업](WORK.md)
- [설정·운영 가이드](docs/OPERATIONS.md)
- [AI 실제 검증 결과](docs/AI-EVALUATION.md)
- [OpenAI 연결과 키 관리](docs/OPENAI-INTEGRATION.md)
- [커밋 전 비밀정보 검사](docs/SECRET-HYGIENE.md)

Java 25가 필요합니다. Maven은 wrapper로 내려받습니다.

```powershell
cd source
.\mvnw.cmd verify
```

이 명령은 기본 자동 테스트와 WAR 패키징을 수행합니다. 실제 PostgreSQL·외부 서비스 검증은 별도 opt-in이며 기본 실행에서 건너뛴 항목은 통과로 간주하지 않습니다.

Windows에서 설치된 PostgreSQL 17을 사용해 운영 DB와 분리한 전체 DB 검증:

```powershell
.\scripts\test-postgres.ps1
```

실행 전 전용 PostgreSQL DB와 초기 관리자 설정이 필요합니다. 비밀번호·토큰의 기본값은 제공하지 않습니다. [운영 가이드](docs/OPERATIONS.md)를 참고하세요.
