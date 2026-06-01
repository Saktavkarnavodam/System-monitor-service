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

    /**
     * Невостребованные токены пользователя — выпущены, но ни одного запроса
     * с ними не приходило. Используется для авто-отзыва при выпуске нового:
     * если визард прокликали 5 раз без выполнения команды на узле, после
     * пятого нажатия предыдущие 4 «висящих» отзываются автоматически.
     */
    List<AgentToken> findByOwnerIdAndLastUsedAtIsNullAndRevokedFalse(String ownerId);
}
