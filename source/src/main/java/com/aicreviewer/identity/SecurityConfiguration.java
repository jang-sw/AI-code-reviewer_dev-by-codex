package com.aicreviewer.identity;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.HeaderWriterFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AccountUserDetailsService users, LoginAttemptLimiter limiter,
                                           SignupAttemptLimiter signupLimiter) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/login", "/signup", "/error", "/assets/**", "/css/**", "/js/**", "/favicon.ico").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .formLogin(login -> login.loginPage("/login").successHandler((request, response, authentication) -> {
                            limiter.succeeded(authentication.getName());
                            response.sendRedirect(request.getContextPath() + "/");
                        })
                        .failureUrl("/login?error").permitAll())
                .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true).deleteCookies("JSESSIONID"))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .headers(headers -> {
                    headers.addObjectPostProcessor(new ObjectPostProcessor<HeaderWriterFilter>() {
                            @Override public <O extends HeaderWriterFilter> O postProcess(O filter) {
                                // JSP forwards can commit before deferred headers are written.
                                filter.setShouldWriteHeadersEagerly(true);
                                return filter;
                            }
                        });
                    headers.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"))
                            .frameOptions(frame -> frame.deny());
                })
                .addFilterAfter(new AccountSessionFilter(users), AnonymousAuthenticationFilter.class)
                .addFilterBefore(new LoginThrottleFilter(limiter), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new SignupThrottleFilter(signupLimiter), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
