# OpenAI 직접 연결

OpenAI 직접 연결은 `AI_PROVIDER=openai`로 선택한다. 기존 Ollama와 LiteLLM 설정은 유지한다. 애플리케이션은 Java HTTP 클라이언트로 공식 `https://api.openai.com/v1/responses`에 요청하며 별도 SDK는 추가하지 않는다.

## 서버 설정

| 설정 | OpenAI 직접 연결에서의 의미 |
| --- | --- |
| `AI_PROVIDER` | `openai`로 지정 |
| `OPENAI_MODEL` | 필수. 계정에서 사용할 수 있고 Responses API와 strict Structured Outputs를 지원하는 모델 ID를 명시 |
| `OPENAI_API_KEY` | 필수. 서버 프로세스 환경 변수 또는 배포 비밀 관리 기능으로 주입 |
| `AI_TIMEOUT_SECONDS` | 전체 응답 수신 제한. 기본120초, 허용1~1800초 |
| `AI_CONTEXT_TOKENS` | 입력 UTF-8 바이트를 보수적 토큰 상한으로 계산하는 로컬 예산. 기본32768, 허용2048~1048576 |
| `AI_MAX_OUTPUT_TOKENS` | Responses `max_output_tokens`. 기본4096, 최소256이며 context 예산보다 작아야 함 |

유료 모델을 자동 선택하지 않는다. `OPENAI_MODEL`이나 `OPENAI_API_KEY`가 비어 있으면 시작 시 설정 오류를 낸다. context 예산은 서버 측 사전 검사이며 모델의 실제 context 크기와 출력 한도를 늘리지 않는다. 운영자는 선택한 모델의 지원 범위에 맞게 두 예산을 설정해야 한다.

OpenAI 모드에서는 `AI_BASE_URL`, `AI_MODEL`, `AI_API_KEY`를 사용하지 않는다. `AI_BASE_URL`을 변경해도 OpenAI 키의 목적지를 바꿀 수 없다. Ollama/LiteLLM 모드는 기존 세 설정을 사용하고 `OPENAI_API_KEY`를 전송하지 않는다. LiteLLM을 통해 OpenAI 모델을 사용하려면 기존 LiteLLM 서버의 별도 공급자 설정을 사용한다.

키를 소스, 문서, 예제 설정, 명령 기록, 브라우저 폼, DB에 넣지 않는다. 커밋된 설정에는 `OPENAI_MODEL`의 환경 변수 참조만 있고, 키는 서버에서 읽는다. `.env*`, `source/application-local.properties`, `.local/`은 Git 제외 대상이지만 **Spring Boot가 `.env`를 자동으로 읽지는 않는다**. 로컬 설정이 필요하면 제외된 파일에도 실제 키 대신 환경 변수 참조를 사용하고, 환경 변수 목록이나 값을 로그로 출력하지 않는다. 이미 커밋한 비밀은 `.gitignore` 추가로 제거되지 않는다.

## 요청과 결과 처리

- 입력은 시스템 지침과 커밋 SHA·메시지·전체 diff의 JSON이다. Git 인증 토큰과 작성자 계정·이메일은 요청에 포함하지 않는다. diff 자체에 포함된 내용은 자동으로 비식별화하지 않는다.
- Responses의 `text.format`에 `type=json_schema`, `strict=true`를 사용한다. 필수 필드, 자료형, severity enum, nullable line, `additionalProperties=false`를 지정한다. 모델별 JSON Schema 제약 차이를 피하기 위해 문자열 길이·배열 수·숫자 범위는 요청 schema에서 생략하고 로컬 검증기로 강제한다. Ollama/LiteLLM의 기존 schema는 유지한다. [공식 Structured Outputs 안내](https://developers.openai.com/api/docs/guides/structured-outputs)
- `store:false`, `stream:false`, `truncation:disabled`, 빈 도구 목록과 `tool_choice:none`을 명시한다. `temperature`, conversation, previous response ID는 전송하지 않는다. [Responses 요청 참조](https://developers.openai.com/api/reference/cli/resources/responses/methods/create), [Responses 전환 안내](https://developers.openai.com/api/docs/guides/migrate-to-responses)
- 완료된 response 안의 완료된 assistant 메시지 하나, `output_text` 하나만 리뷰로 허용한다. 정상 형식의 reasoning metadata는 버리고 저장·표시하지 않는다. refusal, 오류, 불완전 응답, 도구 호출, 알 수 없는 출력, 여러 메시지/본문, 잘못된 JSON은 성공 처리하지 않는다.
- 기존 검증기로 필드 수·길이·최대100개 finding·severity·커밋 내 경로·새 파일의 표시된 diff hunk 안의 줄 번호를 확인한다. 삭제 파일의 줄 번호는 null이어야 한다. 잘못된 응답 때문에 리뷰 커서를 진행시키지 않는다.
- HTTP 리디렉션을 따르지 않는다. 응답 크기와 본문 수신 시간까지 제한하며 오류에는 HTTP 상태 또는 일반적인 사유만 남긴다. HTTP 응답 본문과 API 키를 오류 메시지에 붙이지 않는다. HTTP 오류의 자동 재시도는 없으며 프로젝트의 후속 실행에서 기존 검토 기록을 기준으로 재개한다.

## 데이터 전송과 보관

OpenAI 모드를 선택하면 커밋 메시지와 소스 diff가 OpenAI API로 전송된다. 로컬 Ollama와 외부 전송 범위가 다르므로 운영 환경에서 허용된 저장소에 사용한다.

`store:false`는 이 애플리케이션이 response 저장·재조회 기능을 요청하지 않는다는 뜻이다. **모든 로그의 즉시 삭제나 Zero Data Retention을 보장하지 않는다.** 기본 abuse monitoring 보관과 별도 승인된 데이터 제어 정책은 계정·기능에 따라 다르므로 공식 데이터 정책을 확인한다. [OpenAI 데이터 제어 안내](https://developers.openai.com/api/docs/guides/your-data)

## 검증 범위

`OpenAiReviewClientTest`는 loopback HTTP fixture로 요청 schema, 키 분리, 고정 운영 URL, 응답 envelope, 거절·잘림·오류·도구 요청 거부, diff 경로/줄 검증, timeout/크기 제한, 리디렉션 차단과 오류 본문 비노출을 확인한다. 테스트 주입 경로는 같은 패키지의 코드에서만 호출할 수 있고 `http://127.0.0.1:<port>/v1/responses`만 허용한다. 운영 설정에서 이 경로를 활성화할 수 없다.

실제 OpenAI 키를 조회하거나 유료 요청을 보내는 테스트는 추가하지 않았다. 기존 선택 실행 모델 평가는 Ollama/LiteLLM용이며 직접 OpenAI 모드를 켜는 유료 평가 경로로 사용하지 않는다. 계정별 인증·모델 접근·실제 응답 호환성·비용·리뷰 품질은 실제 API 검증 전까지 미확인이다. 공식 문서 확인일:2026-09-26.
