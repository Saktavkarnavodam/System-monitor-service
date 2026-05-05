package ru.diplom.monitoring.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.diplom.monitoring.model.Alert;
import ru.diplom.monitoring.model.AlertStatus;

import java.util.List;
import java.util.Optional;

public interface AlertRepository extends JpaRepository<Alert, String> {

    List<Alert> findByStatus(AlertStatus status);

    List<Alert> findByOwnerIdAndStatus(String ownerId, AlertStatus status);

    @Query("SELECT a FROM Alert a ORDER BY a.firedAt DESC")
    List<Alert> findAllOrderByFiredAtDesc(Pageable pageable);

    @Query("SELECT a FROM Alert a WHERE a.ownerId = :ownerId ORDER BY a.firedAt DESC")
    List<Alert> findByOwnerIdOrderByFiredAtDesc(@Param("ownerId") String ownerId, Pageable pageable);

    /**
     * Находит висящий FIRING-алерт для пары (правило, узел). Параметр nodeId может
     * быть null (для «глобальных» правил без привязки к узлу) — обрабатываем явно
     * через IS NULL, потому что Spring Data derived methods трактуют null как «= NULL»
     * и не находят таких записей.
     */
    @Query("SELECT a FROM Alert a WHERE a.ruleId = :ruleId AND a.status = :status " +
            "AND ((:nodeId IS NULL AND a.nodeId IS NULL) OR a.nodeId = :nodeId)")
    Optional<Alert> findFirstByRuleIdAndNodeIdAndStatus(@Param("ruleId") String ruleId,
                                                       @Param("nodeId") String nodeId,
                                                       @Param("status") AlertStatus status);

    List<Alert> findByRuleIdAndStatus(String ruleId, AlertStatus status);

    @Modifying
    @Query("DELETE FROM Alert a WHERE a.nodeId = :nodeId")
    int deleteByNodeId(@Param("nodeId") String nodeId);
}
