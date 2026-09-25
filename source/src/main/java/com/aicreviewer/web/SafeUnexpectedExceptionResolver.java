package com.aicreviewer.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/** Last MVC resolver: keep unexpected exception messages and SQL parameters out of container logs. */
@Component
public final class SafeUnexpectedExceptionResolver implements HandlerExceptionResolver, Ordered {
    private static final Logger LOG = LoggerFactory.getLogger(SafeUnexpectedExceptionResolver.class);

    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception exception) {
        // Earlier MVC resolvers retain their validation/status behavior. Security exceptions must
        // still reach Spring Security, and JVM Errors wrapped by DispatcherServlet must propagate.
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = exception; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof Error || cause instanceof AccessDeniedException
                    || cause instanceof AuthenticationException || cause instanceof ResponseStatusException) return null;
        }
        String reference = UUID.randomUUID().toString();
        // Never pass exception/cause, request URI/parameters, username, SQL or provider details.
        LOG.error("Unexpected web request failure: type={} reference={}", exception.getClass().getSimpleName(), reference);
        if (response.isCommitted()) return new ModelAndView();
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        response.setHeader("Cache-Control", "no-store");
        var view = new ModelAndView("request-error");
        view.setStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        view.addObject("statusCode", HttpStatus.INTERNAL_SERVER_ERROR.value());
        view.addObject("pageTitle", "요청 처리 안내");
        view.addObject("errorTitle", "요청을 처리하지 못했습니다");
        return view;
    }
}
