package com.urlshortener.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Basic Auth, one in-memory admin user, no sessions (STATELESS) — the
 * smallest amount of security infrastructure that satisfies "only an admin
 * can create/update/view metadata/view stats." Deliberately not JWT: token
 * issuance, expiry, and secret management add real complexity this session
 * has no way to verify without a local build/run, whereas Basic Auth is
 * built into Spring Security and testable with curl/Postman out of the box.
 *
 * GET /api/v1/{shortCode} (redirect, RedirectController) stays public: a URL
 * shortener's whole purpose is for arbitrary visitors to follow the link
 * without an account. Everything under /api/v1/urls/** requires ROLE_ADMIN.
 */
@Configuration
public class SecurityConfig {

    private final String adminUsername;
    private final String adminPassword;

    public SecurityConfig(
        @Value("${security.admin.username}") String adminUsername,
        @Value("${security.admin.password}") String adminPassword) {
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public InMemoryUserDetailsManager userDetailsService(PasswordEncoder passwordEncoder) {
        UserDetails admin = User.withUsername(adminUsername)
            .password(passwordEncoder.encode(adminPassword))
            .roles("ADMIN")
            .build();
        return new InMemoryUserDetailsManager(admin);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                // Order matters: authorizeHttpRequests matches top-to-bottom and stops at
                // the first hit, not by specificity. /api/v1/urls/** is listed first so it
                // always wins over the broader GET /api/v1/* rule below for any path under
                // /urls — including a hypothetical future GET /api/v1/urls (e.g. "list all")
                // that would otherwise collide with the single-segment redirect pattern.
                .requestMatchers("/api/v1/urls/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/*").permitAll()
                .requestMatchers("/actuator/**", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/error")
                .permitAll()
                .anyRequest().authenticated()
            )
            .httpBasic(Customizer.withDefaults());

        return http.build();
    }
}
