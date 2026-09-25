#!/usr/bin/env bash
set -euo pipefail

# This script targets only the two disposable databases used by the CI service.
: "${TEST_DATABASE_URL:?Set the isolated PostgreSQL integration URL}"
: "${TEST_IDENTITY_DATABASE_URL:?Set the isolated identity PostgreSQL URL}"
: "${TEST_DATABASE_USERNAME:?Set the test database username}"
: "${TEST_DATABASE_PASSWORD:?Set the test database password}"
if [[ ! "$TEST_DATABASE_URL" =~ ^jdbc:postgresql://(127\.0\.0\.1|localhost):([0-9]{1,5})/reviewer_integration$ ]]; then
  printf '%s\n' 'Refusing a nonlocal or incorrectly named integration database.' >&2
  exit 1
fi
test_host="${BASH_REMATCH[1]}"
test_port="${BASH_REMATCH[2]}"
if (( 10#$test_port < 1 || 10#$test_port > 65535 )) ||
   [[ "$TEST_IDENTITY_DATABASE_URL" != "jdbc:postgresql://${test_host}:${test_port}/identity_security" ]] ||
   [[ "$TEST_DATABASE_USERNAME" != 'reviewer_test' ]]; then
  printf '%s\n' 'Both isolated databases must share the same loopback server and test account.' >&2
  exit 1
fi

# External smoke/evaluation and larger offline load tests are deliberate opt-ins outside this CI entrypoint.
export RUN_GITHUB_SMOKE=false RUN_GITLAB_SMOKE=false RUN_OLLAMA_SMOKE=false RUN_AI_EVALUATION=false RUN_GIT_LOAD_SMOKE=false
workspace="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$workspace/source"
./mvnw -B -ntp clean verify
python3 "$workspace/scripts/verify-test-reports.py" "$workspace/source/target/surefire-reports"
