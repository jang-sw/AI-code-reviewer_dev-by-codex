# AI 검증 기록

## 2026-09-25: Ollama 연결 실증

- 대상: 이 PC의 `http://127.0.0.1:11434`, 설치된 `gemma3:1b`.
- 코드/데이터: 테스트에서 만든 합성 Java diff만 전송. 사용자 저장소 소스나 자격증명을 전송하지 않음.
- 명령: `source`에서 `$env:RUN_OLLAMA_SMOKE='true'; .\mvnw.cmd '-Dtest=LocalOllamaSmokeTest' test`.
- 설정: context 32768, 최대 출력 4096, 시간 제한 120초.
- 결과: 72.75초 만에 실제 응답을 받았고 JSON 스키마·필드 검증은 통과했다.
- 평가 범위: 이 테스트는 연결/프로토콜 호환성을 확인한다. 리뷰 품질 합격을 뜻하지 않는다.

## 발견한 품질 문제 (릴리스 전 해결 필요)

분모가 0이면 예외를 던지도록 이미 방어한 변경에 대해, 모델은 다시 0 검사를 추가하라는 LOW 권고를 만들었다. 주어진 diff에서 확인 가능한 새 오류가 아니라 오탐이다. 한국어를 요청했지만 영어 결과를 반환했다.

한 사례로 전체 품질을 수치화하지 않는다. 사용자가 지정한 기본 모델을 임의로 바꾸지 않고 설정 가능하게 유지한다. 릴리스 전 다음 평가가 필요하다.

- 안전한 변경, 실제 결함, 프롬프트 주입, 삭제/이동, 다양한 언어를 포함한 고정 평가 집합 작성.
- 오탐/누락과 파일·행 근거, 한국어 준수, 생성 시간, 재현성 측정.
- 프롬프트와 출력 제약 개선 후 동일 집합 재평가.
- 더 큰 로컬 모델 또는 LiteLLM의 운영 모델과 비교하고 실제 사용 모델의 기준을 합의.

합성 응답 원문은 로컬 빌드 산출물 `source/target/ollama-smoke-result.json`에만 기록한다. 코드 리뷰의 정확도는 자동 테스트 실행 여부와 별개로 평가한다.

## 고정 평가 집합

`source/src/test/java/com/aicreviewer/ai/AiEvaluationCorpus.java`에 안전한 변경3개, 결함 변경3개를 관리한다. Java 배열 경계·정수 나눗셈, JavaScript 빈 배열, Python 기본 인자/빈 배열, 주석 내 프롬프트 주입2개를 포함한다. 사용자 저장소를 읽지 않고 합성 diff만 사용한다.

```powershell
cd source
$env:RUN_AI_EVALUATION='true'
.\mvnw.cmd '-Dtest=AiModelEvaluationTest' test
Remove-Item Env:RUN_AI_EVALUATION
```

기본은 로컬 Ollama `gemma3:1b`다. 다른 모델은 `AI_EVAL_PROVIDER`, `AI_EVAL_BASE_URL`, `AI_EVAL_MODEL`, `AI_EVAL_API_KEY`를 명시한다. 외부 모델 설정은 조직이 승인한 서버에만 사용한다. 리포트는 `target/ai-evaluation-report.json`에 사례마다 기록한다. 기본 명령의 테스트 성공은 **보고서 생성 성공**이며 품질 합격이 아니다.

리포트의 자동 지표는 권고 존재 여부, 각 설명 필드의 한글 포함 여부, 주입된 marker 재현 여부뿐이다. 권고의 결함 설명이 정답과 일치하는지는 사람이 확인해야 한다. 오탐/정확도 비율로 자동 점수를 해석하지 않는다. `AI_EVAL_ENFORCE=true`를 추가하면 거친 자동 기준 미달도 명령 실패로 반환하지만 의미 검토를 대체하지 않는다.
