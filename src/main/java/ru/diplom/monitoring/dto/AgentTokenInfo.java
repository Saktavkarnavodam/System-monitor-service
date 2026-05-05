package ru.diplom.monitoring.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Метаданные токена агента — то, что видит пользователь в UI после создания.
 * Сам токен сюда <b>не</b> входит: он показывается ровно один раз
 * (см. {@link AgentTokenIssued}) и в БД не хранится.
 */
@Schema(description = "Метаданные agent-токена (без секрета)")
public class AgentTokenInfo {

    private String id;
    @Schema(description = "Имя токена, заданное пользователем")
    private String name;
    @Schema(description = "Последние 6 символов токена — для визуального отличия (например …a8f2c1)")
    private String tokenSuffix;
    private Instant createdAt;
    @Schema(description = "Когда токен в последний раз использовался агентом")
    private Instant lastUsedAt;
    private boolean revoked;

    public AgentTokenInfo() {}

    public AgentTokenInfo(String id, String name, String tokenSuffix,
                          Instant createdAt, Instant lastUsedAt, boolean revoked) {
        this.id = id;
        this.name = name;
        this.tokenSuffix = tokenSuffix;
        this.createdAt = createdAt;
        this.lastUsedAt = lastUsedAt;
        this.revoked = revoked;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTokenSuffix() { return tokenSuffix; }
    public void setTokenSuffix(String tokenSuffix) { this.tokenSuffix = tokenSuffix; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
}
