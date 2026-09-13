package com.urlshortener.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityConfigTest {

    private final SecurityConfig securityConfig = new SecurityConfig("admin", "s3cret");

    @Test
    void passwordEncoder_shouldEncodeAndMatch() {
        PasswordEncoder encoder = securityConfig.passwordEncoder();
        String encoded = encoder.encode("s3cret");

        assertTrue(encoder.matches("s3cret", encoded));
    }

    @Test
    void userDetailsService_shouldRegisterConfiguredAdminWithEncodedPasswordAndAdminRole() {
        PasswordEncoder encoder = securityConfig.passwordEncoder();
        InMemoryUserDetailsManager userDetailsService = securityConfig.userDetailsService(encoder);

        UserDetails admin = userDetailsService.loadUserByUsername("admin");

        assertEquals("admin", admin.getUsername());
        assertTrue(encoder.matches("s3cret", admin.getPassword()));
        assertTrue(admin.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
    }
}
