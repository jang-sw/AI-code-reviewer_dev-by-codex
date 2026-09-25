package com.aicreviewer.identity;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class AccountInput {
    private AccountInput() { }

    static String username(String value) {
        String result = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!result.matches("[a-z0-9][a-z0-9._-]{2,79}")) {
            throw new IllegalArgumentException("아이디는 영문 소문자, 숫자, 점, 밑줄, 하이픈으로 3~80자 입력해 주세요.");
        }
        return result;
    }

    static String gitUsername(String value) {
        String result = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!result.matches("[a-z0-9][a-z0-9._-]{0,99}")) {
            throw new IllegalArgumentException("Git 계정은 @ 없이 영문, 숫자, 점, 밑줄, 하이픈으로 1~100자 입력해 주세요.");
        }
        return result;
    }

    static String password(String value) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) < 12
                || value.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("비밀번호는 12자 이상, UTF-8 기준 72바이트 이하로 입력해 주세요.");
        }
        return value;
    }

    static String role(String value) {
        if (!"ADMIN".equals(value) && !"USER".equals(value)) {
            throw new IllegalArgumentException("사용자 권한이 올바르지 않습니다.");
        }
        return value;
    }
}
