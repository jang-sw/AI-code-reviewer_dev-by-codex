package com.aicreviewer.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Only fixed navigation identifiers reach the view; the request path is never echoed. */
@ControllerAdvice
public class NavigationModel {
    private final boolean scheduledReviewEnabled;
    private final boolean reviewWorkerEnabled;

    public NavigationModel(@Value("${app.review.enabled:true}") boolean scheduledReviewEnabled,
                           @Value("${app.review.worker-enabled:true}") boolean reviewWorkerEnabled) {
        this.scheduledReviewEnabled = scheduledReviewEnabled;
        this.reviewWorkerEnabled = reviewWorkerEnabled;
    }

    @ModelAttribute("scheduledReviewEnabled")
    public boolean scheduledReviewEnabled() { return scheduledReviewEnabled; }

    @ModelAttribute("reviewWorkerEnabled")
    public boolean reviewWorkerEnabled() { return reviewWorkerEnabled; }

    @ModelAttribute("activeSection")
    public String activeSection(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/admin/")) return "admin";
        if (path.startsWith("/projects") || path.startsWith("/reviews")) return "projects";
        if (path.startsWith("/issues")) return "issues";
        if (path.startsWith("/account/")) return "account";
        return "/".equals(path) ? "home" : "";
    }
}
