package com.aicreviewer.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

class GitAuthorMappingControllerTest {
    private final GitAuthorMappingService mappings = mock(GitAuthorMappingService.class);
    private final UserAccountService users = mock(UserAccountService.class);
    private final GitAuthorMappingController controller = new GitAuthorMappingController(mappings, users);

    @Test
    void unexpectedArgumentFailureDoesNotBecomeAnEditableSuccessOrRetainSubmittedValues() {
        var failure = new IllegalArgumentException("Synthetic unrelated failure");
        when(mappings.create("admin", 2, "https://gitlab.example.com", "dev@example.com")).thenThrow(failure);
        var model = new ExtendedModelMap();
        var redirect = new RedirectAttributesModelMap();

        assertThatThrownBy(() -> controller.create(() -> "admin", "2", "https://gitlab.example.com", "dev@example.com",
                0, "", redirect, model, new MockHttpServletResponse())).isSameAs(failure);

        assertThat(model).isEmpty();
        assertThat(redirect.getFlashAttributes()).isEmpty();
        verify(mappings, never()).activeUserOption(anyString(), anyLong());
    }

    @Test
    void currentAdministratorIsCheckedBeforeInvalidContextOrInputCanBeReflected() {
        when(users.requireAdmin("former-admin")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        var model = new ExtendedModelMap();
        var redirect = new RedirectAttributesModelMap();

        assertThatThrownBy(() -> controller.create(() -> "former-admin", "invalid", "invalid", "invalid",
                10001, "", redirect, model, new MockHttpServletResponse()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        verifyNoInteractions(mappings);
        assertThat(model).isEmpty();
        assertThat(redirect.getFlashAttributes()).isEmpty();
    }

    @Test
    void losingAdministratorAccessWhileCreateFailsCannotLoadTheErrorForm() {
        when(mappings.create("admin", 2, "https://gitlab.example.com", "incorrect"))
                .thenThrow(new GitAuthorMappingValidationException(HttpStatus.BAD_REQUEST, "authorEmail", "전체 이메일을 확인해 주세요."));
        when(mappings.list("admin", 1)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        var model = new ExtendedModelMap();
        var redirect = new RedirectAttributesModelMap();

        assertThatThrownBy(() -> controller.create(() -> "admin", "2", "https://gitlab.example.com", "incorrect",
                1, "alice", redirect, model, new MockHttpServletResponse()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(model).isEmpty();
        assertThat(redirect.getFlashAttributes()).isEmpty();
        verify(mappings, never()).activeUserOption(anyString(), anyLong());
    }
}
