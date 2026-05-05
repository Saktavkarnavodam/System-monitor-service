package ru.diplom.monitoring.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Запрос на регистрацию или вход")
public class AuthRequest {

    @NotBlank
    @Size(min = 3, max = 32)
    @Schema(example = "alice", description = "Уникальное имя пользователя")
    private String username;

    @NotBlank
    @Size(min = 6, max = 100)
    @Schema(example = "supersecret", description = "Пароль (мин. 6 символов)")
    private String password;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
