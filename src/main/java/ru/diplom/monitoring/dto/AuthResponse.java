package ru.diplom.monitoring.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.diplom.monitoring.model.Role;

@Schema(description = "Ответ с JWT-токеном после регистрации/входа")
public class AuthResponse {
    private String token;
    private String userId;
    private String username;
    private Role role;
    private long expiresInSeconds;

    public AuthResponse() {}

    public AuthResponse(String token, String userId, String username, Role role, long expiresInSeconds) {
        this.token = token;
        this.userId = userId;
        this.username = username;
        this.role = role;
        this.expiresInSeconds = expiresInSeconds;
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public long getExpiresInSeconds() { return expiresInSeconds; }
    public void setExpiresInSeconds(long expiresInSeconds) { this.expiresInSeconds = expiresInSeconds; }
}
