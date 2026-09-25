package com.aicreviewer.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class AdminBootstrap implements ApplicationRunner {
    private final UserAccountService users;
    private final String username;
    private final String password;
    private final String gitUsername;

    public AdminBootstrap(UserAccountService users,
                          @Value("${app.bootstrap.username:}") String username,
                          @Value("${app.bootstrap.password:}") String password,
                          @Value("${app.bootstrap.git-username:}") String gitUsername) {
        this.users = users;
        this.username = username;
        this.password = password;
        this.gitUsername = gitUsername;
    }

    @Override
    public void run(ApplicationArguments args) { users.bootstrap(username, password, gitUsername); }
}
