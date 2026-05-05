package ru.diplom.monitoring.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Value("${server.port:8080}")
    private String serverPort;

    @Bean
    public OpenAPI monitoringOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Distributed Systems Monitoring API")
                        .description("Система мониторинга производительности распределённых систем. " +
                                "Каждый пользователь видит только свои узлы. " +
                                "Используйте /api/v1/auth/login для получения JWT-токена, затем нажмите 'Authorize' в Swagger UI.")
                        .version("0.2.0")
                        .contact(new Contact().name("Diploma Project"))
                        .license(new License().name("MIT")))
                .servers(List.of(
                        new Server().url("http://localhost:" + serverPort).description("Local")
                ))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT-токен из /api/v1/auth/login")));
    }
}
