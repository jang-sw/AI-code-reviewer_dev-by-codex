# 로컬 LiteLLM → Ollama 검증

## 범위와 상태

2026-10-07 전용 WSL Ubuntu에서 **격리 설치·인증·실제 로컬 모델 연결을 검증했다.** 합성6사례의 구조화 응답은6/6, 자동 품질 검사는0/6으로 운영 모델은 승인하지 않았다. 이 구성은 로컬 합성 평가용이며 운영 노출·유료 API·실제 저장소 소스를 사용하지 않는다. 실제 실행의 구성·실패와 보완·한계는 마지막 결과에 기록한다.

기존 `AiReviewClient`의 LiteLLM 요청과 `AiModelEvaluationTest`를 그대로 사용한다. 프록시가 기동하거나 JSON을 반환했다는 사실과 코드 리뷰의 정확성은 별개다. [AI 평가 기준](AI-EVALUATION.md)의 자동 사전 검사와 사람의 의미 검토를 구분한다.

## 버전과 공급망 확인

고정 버전은 **`litellm[proxy]==1.104.0`**이다. 조회 당시 PyPI의 최신 정식 버전이고 Python `>=3.10,<3.15`를 요구한다. Ubuntu24.04의 Python3.12에 맞는다. RC/dev 버전이나 버전 미지정 설치를 사용하지 않는다. [공식 PyPI 메타데이터](https://pypi.org/pypi/litellm/1.104.0/json)

Ubuntu x86_64용 파일과 SHA-256은 다음과 같다. 다른 플랫폼의 wheel에 이 해시를 적용하지 않는다.

```text
litellm-1.104.0-cp310-abi3-manylinux_2_28_x86_64.whl
b0a690d18b0d25f907d08329027ca57b03f0173a4824b89b8f91528490d10bde
```

공식 사고 공지에서 악성 배포로 확인한 **1.82.7과 1.82.8은 사용하지 않는다.** 새 venv는 기존 Python 환경의 패키지를 재사용하지 않는다. [공식 2026-03 사고 공지](https://docs.litellm.ai/blog/security-update-march-2026)

2026-10-07에 저장소의 공개 보안 공지 17개를 읽었다. 1.104.0은 그 공지들의 명시적 영향 범위 밖이다. 특히 9월 관리자 권한 상승은 1.104.0rc2에서, 10월 공지된 `vertex_ai_credentials` 파일 읽기는 1.95.0에서 수정되었다. 이는 새 취약점이나 설치되는 모든 전이 의존성이 안전하다는 증명이 아니다. 설치 전에 공지와 최종 의존성 목록을 다시 확인한다. [전체 공지 API](https://api.github.com/repos/BerriAI/litellm/security-advisories?per_page=100), [권한 상승 공지](https://github.com/BerriAI/litellm/security/advisories/GHSA-7hp6-4w63-5g45), [파일 읽기 공지](https://github.com/BerriAI/litellm/security/advisories/GHSA-g5ff-637f-6q2m)

공식 CLI 설치 extra인 `proxy`를 사용하고, 아래 DB 없는 인증 오류를 보완하기 위해 **`prisma==0.15.0`을 함께 고정**한다. `extra-proxy`와 `proxy-runtime` 전체는 추가하지 않는다. `proxy` 자체에도 HTTP 서버, 클라우드 SDK 및 기타 의존성이 포함되어 있으므로 이를 Ollama 전용의 작은 패키지라고 보지 않는다. 별도 DB·Redis·Prisma client 생성·query engine 실행은 구성하지 않는다. [CLI 설치 문서](https://docs.litellm.ai/docs/proxy/quick_start)

1.104.0은 DB 없는 master-key 구성에서도 인증 예외를 분류할 때 선택 의존성인 `prisma`를 import한다. `proxy` extra만 설치하면 키 누락 요청이 원래의401 대신500으로 끝나는 문제가 있다. Prisma Python 패키지의 예외 클래스만 이용하는 이번 보완에는 `prisma generate`, schema migration, DB 접속이나 엔진 다운로드가 필요하지 않다. 동일 문제가 [공식 저장소 이슈38978](https://github.com/BerriAI/litellm/issues/38978)에 보고되어 있으며, [수정 PR38983](https://github.com/BerriAI/litellm/pull/38983)과 [PR39244](https://github.com/BerriAI/litellm/pull/39244)는 2026-10-07 확인 시 아직 병합되지 않았다. [1.104.0 예외 분류 코드](https://github.com/BerriAI/litellm/blob/v1.104.0/litellm/proxy/db/exception_handler.py#L130), [Prisma0.15.0 초기화 코드](https://github.com/RobertCraigie/prisma-client-py/blob/v0.15.0/src/prisma/__init__.py)

Prisma의 플랫폼 공통 wheel과 공식 SHA-256도 설치 전에 확인한다. [공식 PyPI 메타데이터](https://pypi.org/pypi/prisma/0.15.0/json)

```text
prisma-0.15.0-py3-none-any.whl
de949cc94d3d91243615f22ff64490aa6e2d7cb81aabffce53d92bd3977c09a4
```

Prisma Client Python은 유지보수가 종료되어 2025-04-15 저장소가 archive되었다. 이 의존성 추가는 **격리된 로컬 호환성 검증의 임시 보완**이며 운영 배포·장기 유지보수 승인이 아니다. 운영 채택 전에는 LiteLLM의 선택 import 수정이 포함된 정식 릴리스와 의존성 상태를 다시 검증해야 한다. [Prisma 공식 유지보수 안내](https://github.com/RobertCraigie/prisma-client-py)

새 venv의 bootstrap pip도 그대로 사용하지 않고 **`pip==26.2.1`**로 먼저 고정한다. 2026-10-07 PyPI의 최신 정식 버전이며 Python3.10 이상을 지원한다. 이 업데이트는 전용 venv에만 적용한다. [pip 공식 PyPI 메타데이터](https://pypi.org/pypi/pip/26.2.1/json)

```text
pip-26.2.1-py3-none-any.whl
71138adf1f4ca900cdb7d289c21b7494329f2332b6d85f0e1c42108c0384ed3e
```

## 설치 순서

검증 담당자가 Linux native 파일시스템의 기존 checkout에서 수행한다. Windows `.local`, Python venv, Maven `target`을 공유하지 않는다. 일반 Linux 사용자로 새 전용 디렉터리와 venv를 만들고, 시스템 Python에 설치하거나 `sudo pip`를 사용하지 않는다.

아래는 **검토 후 실행할 명령 예시**다. `.local/litellm-validation-1.104.0`이 이미 있으면 덮어쓰지 말고 이전 소유 프로세스·산출물을 확인한 뒤 별도 새 경로를 사용한다. 다운로드에는 공식 PyPI만 사용하고, 의존성의 source build가 필요하면 자동 우회하지 않고 원인을 확인한다.

```bash
set -euo pipefail
umask 077
test ! -e .local/litellm-validation-1.104.0
mkdir .local/litellm-validation-1.104.0
python3 -m venv .local/litellm-validation-1.104.0/venv
.local/litellm-validation-1.104.0/venv/bin/python -m pip --isolated download \
  --no-deps --only-binary=:all: --index-url https://pypi.org/simple \
  --dest .local/litellm-validation-1.104.0/bootstrap 'pip==26.2.1'
printf '%s  %s\n' \
  71138adf1f4ca900cdb7d289c21b7494329f2332b6d85f0e1c42108c0384ed3e \
  .local/litellm-validation-1.104.0/bootstrap/pip-26.2.1-py3-none-any.whl \
  | sha256sum --check --status
.local/litellm-validation-1.104.0/venv/bin/python -m pip --isolated install \
  --no-index --no-deps \
  --report .local/litellm-validation-1.104.0/pip-install-report.json \
  .local/litellm-validation-1.104.0/bootstrap/pip-26.2.1-py3-none-any.whl
.local/litellm-validation-1.104.0/venv/bin/python -m pip --isolated download \
  --only-binary=:all: --index-url https://pypi.org/simple \
  --dest .local/litellm-validation-1.104.0/wheels \
  'litellm[proxy]==1.104.0' 'prisma==0.15.0'
```

다운로드된 LiteLLM과 Prisma wheel 각각을 위 공식 SHA-256과 비교한다. 하나라도 불일치하면 설치하지 않는다. 모든 wheel의 파일명·SHA-256 목록도 `.local`에 기록하고, 검증한 동일 wheelhouse에서만 설치한다. 두 wheel의 해시를 확인했다고 모든 의존성에 `--require-hashes`가 적용되었다고 보고하지 않는다. 완전한 해시 고정 설치는 모든 전이 의존성까지 버전과 해시를 지정해야 한다. [pip 보안 설치 지침](https://pip.pypa.io/en/stable/topics/secure-installs/)

```bash
set -euo pipefail
printf '%s  %s\n' \
  b0a690d18b0d25f907d08329027ca57b03f0173a4824b89b8f91528490d10bde \
  .local/litellm-validation-1.104.0/wheels/litellm-1.104.0-cp310-abi3-manylinux_2_28_x86_64.whl \
  | sha256sum --check --status
printf '%s  %s\n' \
  de949cc94d3d91243615f22ff64490aa6e2d7cb81aabffce53d92bd3977c09a4 \
  .local/litellm-validation-1.104.0/wheels/prisma-0.15.0-py3-none-any.whl \
  | sha256sum --check --status
sha256sum .local/litellm-validation-1.104.0/bootstrap/*.whl \
  .local/litellm-validation-1.104.0/wheels/*.whl \
  > .local/litellm-validation-1.104.0/wheels.sha256
.local/litellm-validation-1.104.0/venv/bin/python -m pip --isolated install \
  --no-index --find-links .local/litellm-validation-1.104.0/wheels \
  --report .local/litellm-validation-1.104.0/install-report.json \
  'litellm[proxy]==1.104.0' 'prisma==0.15.0'
.local/litellm-validation-1.104.0/venv/bin/python -m pip check
.local/litellm-validation-1.104.0/venv/bin/python -m pip list --format=json \
  > .local/litellm-validation-1.104.0/packages.json
```

각 명령의 성공을 확인한 다음 단계로 진행한다. `pip check`는 의존성 충돌 검사이며 보안 취약점 검사와 다르다. 설치 보고서·wheel hash·패키지 목록은 로컬 증거로 보관하고 환경변수 전체나 비밀 설정은 출력하지 않는다.

## 프록시 구성과 실행

[설정 예시](../deploy/litellm/local-ollama.yaml.example)는 `local-review-gemma` 하나를 `ollama_chat/gemma3:1b`로 연결한다. 해당 공개 모델이 이미 설치되어 있는지 먼저 확인한다. 다른 설치 모델을 평가하려면 로컬 복사본의 별칭과 `model`만 명시적으로 변경하고 결과에 실제 공개 모델명을 기록한다. 모델 자동 다운로드·클라우드 fallback은 하지 않는다.

`ollama_chat`은 `/api/chat`을 사용하며, LiteLLM은 `response_format.json_schema.schema`를 Ollama의 `format`으로 전달한다. 앱의 strict schema, 파일·행 검증은 계속 앱에서 수행한다. `num_ctx: 8192`와 평가의 `AI_EVAL_CONTEXT_TOKENS=8192`를 맞춘다. 이 값은 모델의 실제 처리 능력·품질을 보장하지 않는다. [LiteLLM Ollama 지원](https://docs.litellm.ai/docs/providers/ollama), [1.104.0 변환 코드](https://github.com/BerriAI/litellm/blob/v1.104.0/litellm/llms/ollama/chat/transformation.py), [Ollama structured outputs](https://docs.ollama.com/capabilities/structured-outputs)

`api_base`는 `OLLAMA_API_BASE` 환경변수에서 읽는다. 먼저 **프록시를 실행할 동일 WSL 사용자/네트워크에서** 보통의 주소인 `http://127.0.0.1:11434`에 접근할 수 있는지 확인한다. Windows Ollama가 존재한다고 WSL loopback에서도 연결된다고 가정하지 않는다. 연결되지 않으면 그 단계에서 중단하고 전용 로컬 연결 방식을 확인한다. 이 검증을 위해 Ollama를 `0.0.0.0`에 노출하거나 일반 방화벽 허용 규칙을 추가하지 않는다. 검증 담당자가 임시 loopback bridge를 사용하면 실제 주소·경로 제한·소유 프로세스 정리와 그 한계를 별도로 기록한다. 이것을 Linux 독립 Ollama 설치 검증으로 계산하지 않는다.

시작 프로세스에는 허용한 환경변수와 아래에서 생성한 임시 프록시 키만 전달한다. 클라우드 API 키, Git 토큰, DB 자격증명, HTTP 프록시 환경변수를 상속하지 않는다. 깨끗한 작업 디렉터리에서 `LITELLM_MODE=PRODUCTION`으로 `.env` 자동 로딩을 막고, `LITELLM_LOCAL_MODEL_COST_MAP=True`로 가격표의 원격 fetch를 막는다. 후자는 모든 네트워크 접근을 차단하는 기능은 아니다. [운영 설정 문서](https://docs.litellm.ai/docs/proxy/prod), [1.104.0 cost-map 코드](https://github.com/BerriAI/litellm/blob/v1.104.0/litellm/litellm_core_utils/get_model_cost_map.py)

강한 **임시 프록시 master key가 필수**다. 검증 담당자는 `0700`인 소유 runtime 디렉터리에서 Python `secrets` 등으로 충분한 엔트로피의 새 키를 생성하고, 독점 생성한 `0600` 파일에 기록한다. 값은 터미널·명령행 인자·보고서·저장소에 출력하지 않는다. 실행기가 파일을 읽어 프록시의 `LITELLM_MASTER_KEY`와 평가 프로세스의 `AI_EVAL_API_KEY`에 같은 값을 주입한다. 저장소 템플릿에는 환경변수 참조만 남긴다. 키 없는 시작이나 약한 키가 거부되면 보호 기능을 끄는 옵션으로 우회하지 않는다.

```text
LITELLM_MODE=PRODUCTION
LITELLM_LOCAL_MODEL_COST_MAP=True
LITELLM_LOG=ERROR
OLLAMA_API_BASE=http://127.0.0.1:11434
```

절대 경로로 다음 인자를 전달하고, 실행기가 자신이 시작한 PID와 로그 파일만 관리한다.

```text
<venv>/bin/litellm --config <local-config.yaml> --host 127.0.0.1 --port 14000 --num_workers 1 --request_timeout 120
```

명령 플래그는 [1.104.0 CLI 소스](https://github.com/BerriAI/litellm/blob/v1.104.0/litellm/proxy/proxy_cli.py)에서 확인했다. 이 버전의 `--telemetry`는 deprecated no-op이므로 실효적인 차단 설정으로 쓰지 않는다. 공식 self-host 정책은 telemetry를 실행하지 않는다고 명시한다. 템플릿에는 외부 관측 callback을 설정하지 않고 본문 로깅과 spend/error DB 로그를 끈다. 상세 디버그나 원문 응답 로그를 켜지 않는다. 이 구성에서 프로세스의 모든 오류 로그가 비밀을 자동 제거한다고 보장하지 않으므로 합성 입력만 사용한다. [고정 버전 보안 정책](https://github.com/BerriAI/litellm/blob/v1.104.0/security.md), [본문 로깅 설정](https://docs.litellm.ai/docs/proxy/logging)

프록시 주소는 loopback에만 바인딩하고 클라이언트는 임시 프록시 키로 인증한다. 이 로컬 검증 구성을 다른 사용자·네트워크에 노출할 운영 구성으로 사용하지 않는다. `allow_client_side_credentials: false`와 고정 모델 목록을 유지한다. upstream Ollama와 유료 모델의 키는 필요하지 않다. [요청 라우팅 보안 공지](https://github.com/BerriAI/litellm/security/advisories/GHSA-3cv6-jpf6-8222)

## 기존 합성 평가 연결

먼저 `AI_EVAL_DRY_RUN=true`로 아래 설정과 합성 사례를 검증한다. 이것은 프록시 호출 성공을 뜻하지 않는다. 이후 같은 설정에서 `AI_EVAL_DRY_RUN=false`로 `AiModelEvaluationTest`만 실행한다. 최대 사례 수를 먼저 1로 제한해 응답·시간·스키마를 확인한 뒤 최대6건을 평가한다. 모델 호출을 포함하는 Maven 실행은 중앙 검증 담당자가 수행한다.

```text
RUN_AI_EVALUATION=true
AI_EVAL_PROVIDER=litellm
AI_EVAL_BASE_URL=http://127.0.0.1:14000/v1
AI_EVAL_MODEL=local-review-gemma
AI_EVAL_CONTEXT_TOKENS=8192
AI_EVAL_MAX_OUTPUT_TOKENS=1024
AI_EVAL_TIMEOUT_SECONDS=120
AI_EVAL_MAX_CASES=1
AI_EVAL_CASE_IDS=array-off-by-one
AI_EVAL_ENFORCE=false
```

단일 사례에는 `AI_EVAL_CASE_IDS`를 반드시 함께 지정한다. 전체6건은 `AI_EVAL_MAX_CASES=6`으로 바꾸고 `AI_EVAL_CASE_IDS` 환경변수를 제거하여 실행한다. 빈 문자열로 남기지 않는다. `AI_EVAL_API_KEY`는 위 목록에 값을 붙여 저장하지 않고 임시 파일에서 실행기가 주입한다. `source`에서 실행할 선택 시험은 `./mvnw -B -ntp -Dtest=AiModelEvaluationTest test`다. 다른 외부 smoke 플래그는 끄고 기존 scheduler/worker 프로세스가 이 프록시를 자동 호출하지 않게 한다. 결과 `source/target/ai-evaluation-report.json`은 다음 실행 전에 `.local`의 별도 이름으로 보관한다.

판정은 다음 순서로 한다.

1. pip·LiteLLM·Prisma의 고정 버전·wheel 해시·최종 의존성 목록·loopback 바인딩을 확인한다. `/v1/models`에서 정상 키200, 키 누락401, 잘못된 키400을 확인한다. 마지막400은 DB 없는 master-key 구성의 명시적 거부이며 성공으로 간주하지 않는다. 키 누락500이 계속되면 호환성 검증 실패로 남긴다. 인증 거부 응답의 원문이나 제출한 키는 보고서에 기록하지 않는다.
2. 실제 alias route, 단일 `choices`, `finish_reason=stop`, strict JSON/파일·행 검증 통과 여부를 확인한다. timeout·길이 한도·스키마 오류는 그대로 실패로 기록한다.
3. 6개 합성 사례를 실행해 자동 사전 검사 결과를 기록하고, 한국어·근거·오탐/누락·주입 저항을 사람이 검토한다. `qualityApproval=NOT_ASSESSED`는 승인 완료가 아니다.
4. 시작한 프록시 PID만 정상 종료하고 listener 종료를 확인한다. 평가 프로세스도 끝난 뒤 이번 실행이 생성한 임시 키 파일만 삭제하고 키를 주입한 프로세스 환경을 폐기한다. 삭제 실패는 정리 미완료로 기록하며 기존 파일을 대신 삭제하지 않는다. 기존 Ollama 프로세스·모델·다른 서버는 중지하지 않는다.

## 실제 결과

- 전용 WSL `ai-reviewer-validation`의 일반 사용자로 Linux native `.local/session14-litellm-runtime/venv`에 설치했다. 부족했던 Ubuntu `python3.12-venv`만 추가했으며 시스템 Python 패키지나 기존 Windows Ollama 설정은 바꾸지 않았다. 최종 버전은 LiteLLM1.104.0/Prisma0.15.0/pip26.2.1,118패키지다. 세 고정 wheel의 공식 SHA256과 `pip check`를 통과했다. 설치 보고서·모든 다운로드 wheel의 해시·최종 패키지 목록을 같은 로컬 디렉터리에 보존한다.
- 공식 공개 보안 공지17개와 pinned 코드를 확인했다. 초기115개 설치 wheel의 OSV 일치0건 이후, venv의 bootstrap pip까지 포함한118개 재검사에서 pip24.0 관련12개 ID가 확인됐다. 해당 venv의 pip만26.2.1로 보완했고 **최종118개 OSV 일치0건**을 확인했다. 이는2026-10-07 조회 시점의 공개 DB 일치 결과이며 유지보수 종료된 Prisma와 OS/모델 환경까지 안전하다는 증명은 아니다. `osv-summary.json`, `osv-final-summary.json`, `osv-verified-summary.json`을 구분해 보존한다.
- 최초 keyless 시작은 LiteLLM의 안전 검사로 거부됐다. 강한 임시 키를0700 디렉터리의0600 파일에 생성해 환경으로만 전달했다. 기본 proxy extra에서는 무인증 요청이 Prisma import 누락으로500이 되어 원인을 확인했다. Prisma0.15.0을 추가한 뒤 `/v1/models`의 정상키200/키없음401/잘못된키400을 확인했다. DB·client 생성·엔진 실행 없이 통과했으며 실패했던 검사 기록도 남겼다. `auth-check3-summary.json`은 보완 후 결과다. 이 무DB 로컬 구성을 운영 프록시 승인으로 확대하지 않는다.
- LiteLLM은 WSL `127.0.0.1:14000`에만 바인딩했다. WSL에서 Windows Ollama의 loopback에 직접 연결되지 않아, WSL `127.0.0.1:14001`의 임시 bridge가 Windows Python으로 고정 `127.0.0.1:11434`의 제한된 Ollama API 경로만 전달했다. `/api/show`1회와 `/api/chat`7회가 모두200이었다. 방화벽 허용·외부 bind·모델 다운로드 없이 기존 Windows Ollama0.35.1의 `gemma3:1b`를 사용했다. 이는 Linux 독립 Ollama 설치 검증이 아니다.
- 실제 모델 digest는 `8648f39daa8fbf5b18c7b4e6a8fb4990c692751d49917417b8842ca5758e7ffc`다. 별칭 `local-review-gemma`, context8192/output1024/timeout120초로 단일 사례 dry-run→실제1회→전체6회를 수행했다. 첫 실호출43.668초, 후속6개는각1.500~3.830초였으며 동시 부하를 통제한 처리량 측정은 아니다. 앱의 JSON/파일/행 검증은7회 모두 통과했고 전체6개 자동 품질 검사는0/6이었다. `evaluation-{dry,single,all}.json`에 원래 `qualityApproval=NOT_ASSESSED`와 `humanSemanticReview=PENDING`을 유지한다.
- 실제 응답은 정상 나눗셈 방어 코드와 빈 배열 처리를 결함으로 보고하고, 배열 반복 경계·Python 공유 기본 리스트·빈 배열 접근 결함은 놓쳤다. 자연어는 영어였고 근거 없는 설명도 있었다. 주입 marker가 없다는 것만으로 주입 방어가 합격한 것은 아니다. 따라서 **연결은 검증했지만 이 모델/설정은 운영 품질에 미달**한다.
- 키가 로그·평가 보고서에 없는지 검사한 뒤 이번 임시 키 파일들을 삭제했다. 소유 프록시/bridge를 중지하고14000/14001 닫힘을 확인했다. 설치한 venv와 비밀 없는 설정·증거는 재사용을 위해 남기며 다음 실행에서 새 키를 만들어야 한다. 운영 서버·모델 선정·품질·실제 용량·인증/보안 수명주기는 남아 있다.

이 자료와 `deploy/litellm` 템플릿은 소스 저장소의 로컬 검증 자료다. 기존 Linux 애플리케이션 후보26파일에는 포함하지 않으며, LiteLLM을 운영 서비스로 자동 설치하거나 공개하지 않는다.
