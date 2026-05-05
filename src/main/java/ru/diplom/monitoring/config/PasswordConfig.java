package ru.diplom.monitoring.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Отдельная конфигурация для PasswordEncoder. Вынесена из SecurityConfig,
 * чтобы избежать циклической зависимости:
 * SecurityConfig → JwtAuthFilter → UserService → PasswordEncoder ← SecurityConfig.
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
