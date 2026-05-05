package ru.diplom.monitoring.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.diplom.monitoring.model.Node;
import ru.diplom.monitoring.model.NodeStatus;

import java.time.Instant;
import java.util.List;

public interface NodeRepository extends JpaRepository<Node, String> {

    List<Node> findByOwnerId(String ownerId);

    List<Node> findByOwnerIdAndStatus(String ownerId, NodeStatus status);

    List<Node> findByStatus(NodeStatus status);

    @Query("SELECT n.id FROM Node n WHERE n.ownerId = :ownerId")
    List<String> findIdsByOwnerId(@Param("ownerId") String ownerId);

    long countByOwnerId(String ownerId);

    /**
     * Bulk-обновление статусов по правилам heartbeat.
     * Один SQL вместо load+update в цикле.
     */
    @Modifying
    @Query("UPDATE Node n SET n.status = :newStatus " +
            "WHERE n.lastHeartbeat IS NOT NULL " +
            "  AND n.lastHeartbeat < :before " +
            "  AND n.status <> :newStatus")
    int markStaleNodes(@Param("before") Instant before, @Param("newStatus") NodeStatus newStatus);

    @Modifying
    @Query("UPDATE Node n SET n.status = ru.diplom.monitoring.model.NodeStatus.HEALTHY " +
            "WHERE n.lastHeartbeat IS NOT NULL " +
            "  AND n.lastHeartbeat >= :since " +
            "  AND n.status <> ru.diplom.monitoring.model.NodeStatus.HEALTHY")
    int markHealthyNodes(@Param("since") Instant since);
}
