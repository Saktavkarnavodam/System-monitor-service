package ru.diplom.monitoring.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import ru.diplom.monitoring.model.Metric;
import ru.diplom.monitoring.model.MetricType;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты для {@link MetricRepository} — главного хранилища time-series данных.
 * Использует {@code @DataJpaTest} с in-memory H2 (профиль test).
 */
@DataJpaTest
@ActiveProfiles("test")
class MetricRepositoryTest {

    @Autowired
    private MetricRepository repo;

    private final String ownerA = "owner-a";
    private final String ownerB = "owner-b";
    private final String nodeA = "node-a";
    private final String nodeB = "node-b";

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        Instant base = Instant.parse("2026-04-26T10:00:00Z");
        for (int i = 0; i < 10; i++) {
            saveMetric(ownerA, nodeA, "cpu.usage", 10.0 + i, base.plusSeconds(i * 10L));
        }
        for (int i = 0; i < 5; i++) {
            saveMetric(ownerA, nodeA, "memory.used", 100.0 + i, base.plusSeconds(i * 10L));
        }
        for (int i = 0; i < 7; i++) {
            saveMetric(ownerB, nodeB, "cpu.usage", 50.0 + i, base.plusSeconds(i * 10L));
        }
    }

    private Metric saveMetric(String ownerId, String nodeId, String name, double value, Instant ts) {
        Metric m = new Metric();
        m.setOwnerId(ownerId);
        m.setNodeId(nodeId);
        m.setName(name);
        m.setValue(value);
        m.setType(MetricType.GAUGE);
        m.setTimestamp(ts);
        Map<String, String> tags = new HashMap<>();
        tags.put("env", "test");
        m.setTags(tags);
        return repo.save(m);
    }

    @Test
    void search_byOwner_returnsOnlyOwnerMetrics() {
        List<Metric> result = repo.search(ownerA, null, null, null, null, PageRequest.of(0, 100));
        assertThat(result).hasSize(15);
        assertThat(result).allMatch(m -> m.getOwnerId().equals(ownerA));
    }

    @Test
    void search_byOwnerAndName_filtersByName() {
        List<Metric> result = repo.search(ownerA, null, "cpu.usage", null, null, PageRequest.of(0, 100));
        assertThat(result).hasSize(10);
        assertThat(result).allMatch(m -> m.getName().equals("cpu.usage"));
    }

    @Test
    void search_byNodeAndTimeWindow_filtersBoth() {
        Instant from = Instant.parse("2026-04-26T10:00:30Z");
        Instant to = Instant.parse("2026-04-26T10:01:00Z");
        List<Metric> result = repo.search(null, nodeA, "cpu.usage", from, to, PageRequest.of(0, 100));
        // 30s, 40s, 50s, 60s -> 4 точки
        assertThat(result).hasSize(4);
    }

    @Test
    void search_orderByTimestampDesc_returnsLatestFirst() {
        List<Metric> result = repo.search(ownerA, nodeA, "cpu.usage", null, null, PageRequest.of(0, 100));
        for (int i = 1; i < result.size(); i++) {
            assertThat(result.get(i - 1).getTimestamp())
                    .isAfterOrEqualTo(result.get(i).getTimestamp());
        }
    }

    @Test
    void findLatestPerNameForNode_returnsLatestPerMetric() {
        List<Metric> latest = repo.findLatestPerNameForNode(nodeA);
        assertThat(latest).hasSize(2);
        // максимальные значения = 10 + 9 для cpu.usage и 100 + 4 для memory.used
        assertThat(latest)
                .extracting(Metric::getName, Metric::getValue)
                .containsExactlyInAnyOrder(
                        org.assertj.core.api.Assertions.tuple("cpu.usage", 19.0),
                        org.assertj.core.api.Assertions.tuple("memory.used", 104.0)
                );
    }

    @Test
    void findDistinctNamesByOwner_returnsUnique() {
        List<String> names = repo.findDistinctNamesByOwner(ownerA);
        assertThat(names).containsExactly("cpu.usage", "memory.used");
    }

    @Test
    void countByOwnerId_countsCorrectly() {
        assertThat(repo.countByOwnerId(ownerA)).isEqualTo(15L);
        assertThat(repo.countByOwnerId(ownerB)).isEqualTo(7L);
    }

    @Test
    void deleteOlderThan_removesOnlyOldMetrics() {
        Instant cutoff = Instant.parse("2026-04-26T10:00:30Z");
        int deleted = repo.deleteOlderThan(cutoff);
        // удаляются метрики со временем строго < cutoff
        // 10 cpu owner-a (по 0-90 сек), 5 memory owner-a (0-40 сек), 7 cpu owner-b (0-60 сек)
        // < 30 сек: cpu owner-a 0,10,20 (3); memory owner-a 0,10,20 (3); cpu owner-b 0,10,20 (3) = 9
        assertThat(deleted).isEqualTo(9);
        assertThat(repo.count()).isEqualTo(13L);
    }

    @Test
    void deleteByNodeId_removesOnlyNodeMetrics() {
        int deleted = repo.deleteByNodeId(nodeA);
        assertThat(deleted).isEqualTo(15);
        assertThat(repo.count()).isEqualTo(7L);
    }

    @Test
    void multiTenancy_ownersAreIsolated() {
        List<Metric> aMetrics = repo.search(ownerA, null, "cpu.usage", null, null, PageRequest.of(0, 100));
        List<Metric> bMetrics = repo.search(ownerB, null, "cpu.usage", null, null, PageRequest.of(0, 100));
        assertThat(aMetrics).allMatch(m -> m.getOwnerId().equals(ownerA));
        assertThat(bMetrics).allMatch(m -> m.getOwnerId().equals(ownerB));
        assertThat(aMetrics).hasSize(10);
        assertThat(bMetrics).hasSize(7);
    }
}
