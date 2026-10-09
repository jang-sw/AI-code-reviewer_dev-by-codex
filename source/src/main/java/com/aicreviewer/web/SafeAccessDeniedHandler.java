package com.aicreviewer.web;

import java.io.IOException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;

/** Pass only a fixed request-local classification to the container error page. */
public final class SafeAccessDeniedHandler implements AccessDeniedHandler {
    public static final String CSRF_FAILURE = SafeAccessDeniedHandler.class.getName() + ".csrfFailure";
    private final AccessDeniedHandler fallback = new AccessDeniedHandlerImpl();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException, ServletException {
        if (exception instanceof CsrfException) {
            request.setAttribute(CSRF_FAILURE, Boolean.TRUE);
            response.setHeader("Cache-Control", "no-store");
            // Never copy the submitted token or exception message into the error dispatch.
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        fallback.handle(request, response, exception);
    }
}
