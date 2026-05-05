package ru.diplom.monitoring.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, ObjectMapper objectMapper) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(c -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // публичные ресурсы
                        .requestMatchers(
                                "/", "/index.html", "/favicon.ico",
                                "/css/**", "/js/**", "/static/**",
                                "/api/v1/auth/register", "/api/v1/auth/login",
                                "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                "/actuator/health", "/actuator/info",
                                "/h2-console/**",
                                // /install/agent.{py,ps1,sh} — раздача скриптов
                                // агентов; авторизуется уже сам агент по токену
                                "/install/**"
                        ).permitAll()
                        // всё остальное — только авторизованным
                        .anyRequest().authenticated()
                )
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, res, ex) -> writeJsonError(res, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized", ex.getMessage()))
                        .accessDeniedHandler((req, res, ex) -> writeJsonError(res, HttpServletResponse.SC_FORBIDDEN, "Forbidden", ex.getMessage()))
                )
                // H2-console использует фреймы для своего UI — разрешаем same-origin
                .headers(h -> h.frameOptions(f -> f.sameOrigin()))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private void writeJsonError(HttpServletResponse res, int status, String error, String message) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status);
        body.put("error", error);
        body.put("message", message);
        objectMapper.writeValue(res.getOutputStream(), body);
    }
}
