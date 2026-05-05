package ru.diplom.monitoring.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import ru.diplom.monitoring.dto.MetricIngestRequest;
import ru.diplom.monitoring.dto.MetricSummary;
import ru.diplom.monitoring.dto.TimeSeriesPoint;
import ru.diplom.monitoring.model.Metric;
import ru.diplom.monitoring.model.MetricType;
import ru.diplom.monitoring.repository.MetricRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты MetricService — проверяют, что бизнес-логика
 * (агрегации, фильтры, time-series бакеты) корректна на реальной БД.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MetricServiceTest {

    @Autowired
    private MetricService service;

    @Autowired
    private MetricRepository repo;

    private final String ownerA = "owner-a";
    private final String ownerB = "owner-b";
    private final String nodeA = "node-a";
    private final String nodeB = "node-b";

    @BeforeEach
    void cleanup() {
        repo.deleteAll();
    }

    private MetricIngestRequest req(String name, double value, Instant ts) {
        MetricIngestRequest r = new MetricIngestRequest();
        r.setName(name);
        r.setValue(value);
        r.setType(MetricType.GAUGE);
        r.setUnit("percent");
        r.setTimestamp(ts);
        return r;
    }

    @Test
    void ingest_storesMetric() {
        Metric m = service.ingest(nodeA, ownerA, req("cpu.usage", 50.0, Instant.now()));
        assertThat(m.getId()).isNotNull();
        assertThat(repo.count()).isEqualTo(1L);
    }

    @Test
    void query_filtersByOwner() {
        service.ingest(nodeA, ownerA, req("cpu.usage", 10, Instant.now()));
        service.ingest(nodeB, ownerB, req("cpu.usage", 20, Instant.now()));
        List<Metric> a = service.query(ownerA, null, null, null, null, 100);
        assertThat(a).hasSize(1).allMatch(m -> m.getOwnerId().equals(ownerA));
    }

    @Test
    void query_adminSeesAll_whenOwnerIsNull() {
        service.ingest(nodeA, ownerA, req("cpu.usage", 10, Instant.now()));
        service.ingest(nodeB, ownerB, req("cpu.usage", 20, Instant.now()));
        List<Metric> all = service.query(null, null, null, null, null, 100);
        assertThat(all).hasSize(2);
    }

    @Test
    void summarize_computesPercentilesAndStats() {
        Instant now = Instant.now();
        for (int i = 1; i <= 10; i++) {
            service.ingest(nodeA, ownerA, req("cpu.usage", i, now.minusSeconds(10 - i)));
        }
        MetricSummary s = service.summarize(ownerA, nodeA, "cpu.usage", Duration.ofMinutes(1));
        assertThat(s.getCount()).isEqualTo(10);
        assertThat(s.getMin()).isEqualTo(1.0);
        assertThat(s.getMax()).isEqualTo(10.0);
        assertThat(s.getAvg()).isEqualTo(5.5);
        assertThat(s.getP50()).isEqualTo(5.5); // среднее 5 и 6
        assertThat(s.getP95()).isGreaterThan(9.0);
    }

    @Test
    void summarize_emptyWindow_returnsZeroCount() {
        MetricSummary s = service.summarize(ownerA, nodeA, "missing", Duration.ofMinutes(1));
        assertThat(s.getCount()).isEqualTo(0);
        assertThat(s.getMin()).isEqualTo(0.0);
    }

    @Test
    void timeseries_bucketsCorrectly() {
        Instant base = Instant.parse("2026-04-26T10:00:00Z");
        // 6 точек с шагом 30 секунд → при бакете 60с должно быть 3 бакета по 2 точки
        for (int i = 0; i < 6; i++) {
            service.ingest(nodeA, ownerA, req("cpu.usage", 10.0 * i, base.plusSeconds(i * 30L)));
        }
        Instant from = base;
        Instant to = base.plusSeconds(180);
        List<TimeSeriesPoint> pts = service.timeseries(ownerA, nodeA, "cpu.usage", from, to, 60);
        assertThat(pts).hasSize(3);
        // первый бакет: значения 0 и 10 → avg=5, min=0, max=10, count=2, last=10
        TimeSeriesPoint b0 = pts.get(0);
        assertThat(b0.getCount()).isEqualTo(2);
        assertThat(b0.getMin()).isEqualTo(0.0);
        assertThat(b0.getMax()).isEqualTo(10.0);
        assertThat(b0.getAvg()).isEqualTo(5.0);
        assertThat(b0.getLast()).isEqualTo(10.0);
        // второй бакет: 20 и 30
        TimeSeriesPoint b1 = pts.get(1);
        assertThat(b1.getCount()).isEqualTo(2);
        assertThat(b1.getAvg()).isEqualTo(25.0);
    }

    @Test
    void timeseries_emptyResult() {
        List<TimeSeriesPoint> pts = service.timeseries(ownerA, nodeA, "missing",
                Instant.now().minusSeconds(60), Instant.now(), 60);
        assertThat(pts).isEmpty();
    }

    @Test
    void latestPerMetric_returnsLatestForEachName() {
        Instant now = Instant.now();
        service.ingest(nodeA, ownerA, req("cpu.usage", 1, now.minusSeconds(10)));
        service.ingest(nodeA, ownerA, req("cpu.usage", 2, now.minusSeconds(5)));
        service.ingest(nodeA, ownerA, req("cpu.usage", 3, now));
        service.ingest(nodeA, ownerA, req("memory.used", 100, now));
        List<Metric> latest = service.latestPerMetric(nodeA);
        assertThat(latest).hasSize(2);
        assertThat(latest)
                .filteredOn(m -> m.getName().equals("cpu.usage"))
                .singleElement()
                .extracting(Metric::getValue)
                .isEqualTo(3.0);
    }

    @Test
    void deleteOlderThan_removesOldMetrics() {
        Instant base = Instant.parse("2026-04-26T10:00:00Z");
        for (int i = 0; i < 10; i++) {
            service.ingest(nodeA, ownerA, req("cpu.usage", i, base.plusSeconds(i * 60L)));
        }
        Instant cutoff = base.plusSeconds(300);
        int deleted = service.deleteOlderThan(cutoff);
        assertThat(deleted).isEqualTo(5);
        assertThat(repo.count()).isEqualTo(5L);
    }

    @Test
    void purgeNode_removesOnlyNodeMetrics() {
        service.ingest(nodeA, ownerA, req("cpu.usage", 1, Instant.now()));
        service.ingest(nodeB, ownerA, req("cpu.usage", 2, Instant.now()));
        service.purgeNode(nodeA);
        assertThat(repo.count()).isEqualTo(1L);
    }
}
