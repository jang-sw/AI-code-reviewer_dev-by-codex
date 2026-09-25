package com.aicreviewer.git;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable tree evidence only; these records make no claim that AI inspected file content. */
public record ManualReviewFile(String filePath, String oldObjectSha, String newObjectSha,
        String oldMode, String newMode, String reasonCode) {
    public static final int MAX_FILES = 1000;
    public static final Set<String> REASONS = Set.of("SOURCE_DIFF_UNAVAILABLE", "GIT_DIFF_BUDGET", "AI_INPUT_LIMIT");

    public ManualReviewFile {
        if (filePath == null || filePath.isBlank() || filePath.length() > 1024 || filePath.startsWith("/")
                || filePath.contains("\\") || filePath.contains("\"") || filePath.contains(" b/")
                || filePath.chars().anyMatch(Character::isISOControl)
                || List.of(filePath.split("/", -1)).stream().anyMatch(p -> p.equals("..") || p.isEmpty())
                || !validSide(oldObjectSha, oldMode) || !validSide(newObjectSha, newMode)
                || (oldObjectSha == null && newObjectSha == null)
                || (Objects.equals(oldObjectSha, newObjectSha) && Objects.equals(oldMode, newMode))
                || reasonCode == null || !REASONS.contains(reasonCode)) {
            throw new IllegalArgumentException("Invalid manual review evidence");
        }
    }

    private static boolean validSide(String sha, String mode) {
        return sha == null ? mode == null : sha.matches("[0-9a-f]{40}|[0-9a-f]{64}")
                && mode != null && Set.of("100644", "100755", "120000", "160000").contains(mode);
    }

    public String reasonDescription() {
        return switch (reasonCode) {
            case "SOURCE_DIFF_UNAVAILABLE" -> "Git에서 일부 파일의 검토 가능한 본문 diff를 제공하지 않았습니다.";
            case "GIT_DIFF_BUDGET" -> "커밋 변경량이 Git diff 수집 한도를 초과했습니다.";
            case "AI_INPUT_LIMIT" -> "커밋이 파일 분할 후에도 AI 입력 또는 호출 수 한도를 초과했습니다.";
            default -> throw new IllegalStateException("Unknown manual review reason");
        };
    }
}
