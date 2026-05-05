package ru.diplom.monitoring.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.diplom.monitoring.model.AgentToken;

import java.util.List;
import java.util.Optional;

public interface AgentTokenRepository extends JpaRepository<AgentToken, String> {

    /**
     * Поиск активного (не отозванного) токена по SHA-256-хешу. Используется
     * в auth-фильтре на каждый запрос с заголовком {@code Authorization: Bearer agt_…}.
     */
    Optional<AgentToken> findByTokenHashAndRevokedFalse(String tokenHash);

    /** Все токены пользователя (включая отозванные — для UI «история»). */
    List<AgentToken> findByOwnerIdOrderByCreatedAtDesc(String ownerId);
}
