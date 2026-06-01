package ru.diplom.monitoring.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.diplom.monitoring.model.Metric;

import java.time.Instant;
import java.util.List;

public interface MetricRepository extends JpaRepository<Metric, Long> {

    /**
     * Универсальный запрос по метрикам с любыми комбинациями фильтров.
     * Каждый параметр опционален — для пропуска передайте null.
     *
     * <p>План выполнения:
     * <ul>
     *   <li>если переданы owner_id + name + временное окно — используется
     *       индекс <code>idx_metrics_owner_name_ts</code>;</li>
     *   <li>если node_id + name + окно — используется
     *       <code>idx_metrics_node_name_ts</code>;</li>
     *   <li>если только timestamp — используется <code>idx_metrics_ts</code>.</li>
     * </ul>
     */
    @Query("SELECT m FROM Metric m WHERE " +
            "(:ownerId IS NULL OR m.ownerId = :ownerId) AND " +
            "(:nodeId IS NULL OR m.nodeId = :nodeId) AND " +
            "(:name IS NULL OR m.name = :name) AND " +
            "(:from IS NULL OR m.timestamp >= :from) AND " +
            "(:to IS NULL OR m.timestamp <= :to) " +
            "ORDER BY m.timestamp DESC")
    List<Metric> search(@Param("ownerId") String ownerId,
                        @Param("nodeId") String nodeId,
                        @Param("name") String name,
                        @Param("from") Instant from,
                        @Param("to") Instant to,
                        Pageable pageable);

    @Query("SELECT DISTINCT m.name FROM Metric m WHERE m.ownerId = :ownerId ORDER BY m.name")
    List<String> findDistinctNamesByOwner(@Param("ownerId") String ownerId);

    @Query("SELECT DISTINCT m.name FROM Metric m ORDER BY m.name")
    List<String> findDistinctNames();

    /**
     * Последние значения каждой метрики для конкретного узла.
     * Реализуется через max(id) — id auto-increment, поэтому max(id) = последняя
     * вставленная строка по группе. Работает портабельно (без window-функций).
     */
    @Query("SELECT m FROM Metric m WHERE m.id IN (" +
            "  SELECT MAX(m2.id) FROM Metric m2 WHERE m2.nodeId = :nodeId GROUP BY m2.name" +
            ")")
    List<Metric> findLatestPerNameForNode(@Param("nodeId") String nodeId);

    long countByOwnerId(String ownerId);

    @Modifying
    @Query("DELETE FROM Metric m WHERE m.nodeId = :nodeId")
    int deleteByNodeId(@Param("nodeId") String nodeId);

    /**
     * Retention: удаление точек старше cutoff.
     * Использует индекс idx_metrics_ts.
     */
    @Modifying
    @Query("DELETE FROM Metric m WHERE m.timestamp < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);

    /**
     * Различные пары (nodeId, name), у которых есть raw-точки в окне [from, to).
     * Используется downsampler'ом: «найди все серии, у которых появилось,
     * что свернуть в bucket'ы за последний период».
     * Возвращает {@code Object[2]} — {nodeId, name}.
     */
    @Query("SELECT DISTINCT m.nodeId, m.name FROM Metric m WHERE " +
            "m.timestamp >= :from AND m.timestamp < :to")
    List<Object[]> findDistinctNodeAndNameWithDataIn(@Param("from") Instant from,
                                                     @Param("to") Instant to);

    /**
     * Все точки для (node, name) в окне [from, to), отсортированные по timestamp ASC.
     * Используется downsampler'ом для бакетинга.
     */
    @Query("SELECT m FROM Metric m WHERE m.nodeId = :nodeId AND m.name = :name AND " +
            "m.timestamp >= :from AND m.timestamp < :to " +
            "ORDER BY m.timestamp ASC")
    List<Metric> findForBucketing(@Param("nodeId") String nodeId,
                                  @Param("name") String name,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to);
}
