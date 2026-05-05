package ru.diplom.monitoring.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Долгоживущий API-ключ для агента мониторинга.
 *
 * <p>В отличие от JWT (который выдаётся при входе и протухает за сутки), этот токен:
 * <ul>
 *   <li>не имеет TTL — живёт пока его не отозвали;</li>
 *   <li>в БД хранится только SHA-256 от него — даже админ БД не сможет
 *       восстановить исходное значение;</li>
 *   <li>имеет читаемый префикс (последние 6 символов) для отображения в UI,
 *       чтобы пользователь мог отличить «токен ноутбука» от «токена прода»
 *       без необходимости видеть полный секрет.</li>
 * </ul>
 *
 * <p>Один и тот же токен может использоваться несколькими агентами на
 * разных машинах одного владельца — это удобно: создал токен «продакшн»,
 * вкатил один и тот же install-скрипт на 10 серверов.
 */
@Entity
@Table(name = "agent_tokens", indexes = {
        @Index(name = "idx_agent_tokens_hash", columnList = "token_hash", unique = true),
        @Index(name = "idx_agent_tokens_owner", columnList = "owner_id")
})
public class AgentToken {

    @Id
    @Column(length = 36)
    private String id;

    /** ID владельца — агент действует от его имени. */
    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;

    /** Человекочитаемое имя токена («ноутбук», «прод-кластер», «домашний-сервер»). */
    @Column(nullable = false, length = 64)
    private String name;

    /** SHA-256 от полного токена. Сравнение в auth-фильтре идёт по нему. */
    @JsonIgnore
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /**
     * Последние 6 символов исходного токена — отображаются в UI как
     * {@code …a8f2c1}, чтобы пользователь мог отличить токены друг от друга.
     */
    @Column(name = "token_suffix", nullable = false, length = 8)
    private String tokenSuffix;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Обновляется при каждом успешном вызове через этот токен. */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(nullable = false)
    private boolean revoked = false;

    public AgentToken() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public String getTokenSuffix() { return tokenSuffix; }
    public void setTokenSuffix(String tokenSuffix) { this.tokenSuffix = tokenSuffix; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
}
