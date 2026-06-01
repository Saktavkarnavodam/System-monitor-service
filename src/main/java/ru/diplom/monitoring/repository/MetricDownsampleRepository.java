package ru.diplom.monitoring.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.diplom.monitoring.model.MetricDownsample;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MetricDownsampleRepository extends JpaRepository<MetricDownsample, Long> {

    Optional<MetricDownsample> findFirstByNodeIdAndNameAndBucketStart(String nodeId, String name, Instant bucketStart);

    /**
     * Самый «свежий» bucket по комбинации (node, name) — это водяной знак
     * для downsampling'а: всё после этого bucket'а ещё не обработано.
     */
    @Query("SELECT MAX(d.bucketStart) FROM MetricDownsample d WHERE d.nodeId = :nodeId AND d.name = :name")
    Instant findMaxBucketStart(@Param("nodeId") String nodeId, @Param("name") String name);

    /**
     * Чтение downsampled-серий для графиков за длинные окна.
     * Использует idx_m5_owner_name_bucket или idx_m5_node_name_bucket.
     */
    @Query("SELECT d FROM MetricDownsample d WHERE " +
            "(:ownerId IS NULL OR d.ownerId = :ownerId) AND " +
            "(:nodeId IS NULL OR d.nodeId = :nodeId) AND " +
            "(:name IS NULL OR d.name = :name) AND " +
            "(:from IS NULL OR d.bucketStart >= :from) AND " +
            "(:to IS NULL OR d.bucketStart < :to) " +
            "ORDER BY d.bucketStart ASC")
    List<MetricDownsample> search(@Param("ownerId") String ownerId,
                                  @Param("nodeId") String nodeId,
                                  @Param("name") String name,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to,
                                  Pageable pageable);

    @Modifying
    @Query("DELETE FROM MetricDownsample d WHERE d.nodeId = :nodeId")
    int deleteByNodeId(@Param("nodeId") String nodeId);

    @Modifying
    @Query("DELETE FROM MetricDownsample d WHERE d.bucketStart < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
