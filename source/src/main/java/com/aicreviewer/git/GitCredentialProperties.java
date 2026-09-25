package com.aicreviewer.git;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Deployment-owned credentials. Never include bound values in diagnostic representations. */
@Component
@ConfigurationProperties(prefix = "app.git")
public class GitCredentialProperties {
    private List<Credential> credentials = new ArrayList<>();

    public List<Credential> getCredentials() { return credentials; }
    public void setCredentials(List<Credential> credentials) { this.credentials = credentials; }
    @Override public String toString() { return "GitCredentialProperties[redacted]"; }

    public static class Credential {
        private String origin;
        private String token;

        public String getOrigin() { return origin; }
        public void setOrigin(String origin) { this.origin = origin; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        @Override public String toString() { return "Credential[redacted]"; }
    }
}
