#!/usr/bin/env bash
set -euo pipefail

# Read-only verification; no application startup, service changes, DB access or archive extraction.
fail() { printf '%s\n' "$1" >&2; exit 1; }
[[ $# -eq 2 ]] || fail 'Usage: bash verify-artifact.sh /absolute/path/ai-code-reviewer.war EXPECTED_SHA256'
artifact="$1"
expected="$2"
[[ "$artifact" == /* && -f "$artifact" && ! -L "$artifact" ]] || fail 'Expected an absolute path to a regular WAR file.'
[[ "$expected" =~ ^[0-9a-fA-F]{64}$ ]] || fail 'Expected a SHA-256 digest from the approved build record.'
command -v sha256sum >/dev/null || fail 'sha256sum is required.'
command -v unzip >/dev/null || fail 'unzip is required.'
actual="$(sha256sum -- "$artifact")"
actual="${actual%% *}"
[[ "${actual,,}" == "${expected,,}" ]] || fail 'WAR checksum mismatch; do not install.'
entries="$(unzip -Z1 "$artifact")" || fail 'WAR archive cannot be read.'
for required in META-INF/MANIFEST.MF org/springframework/boot/loader/launch/WarLauncher.class WEB-INF/classes/com/aicreviewer/AiCodeReviewerApplication.class WEB-INF/jsp/login.jsp; do
    grep -Fxq -- "$required" <<< "$entries" || fail 'Required executable WAR content is missing.'
done
manifest="$(unzip -p "$artifact" META-INF/MANIFEST.MF | tr -d '\r')"
grep -Fxq 'Main-Class: org.springframework.boot.loader.launch.WarLauncher' <<< "$manifest" || fail 'WAR launcher does not match the deployment contract.'
grep -Fxq 'Spring-Boot-Version: 4.0.8' <<< "$manifest" || fail 'WAR Spring Boot version does not match the deployment contract.'
printf '%s\n' 'WAR checksum and deployment structure verified. This does not start or validate the service.'
