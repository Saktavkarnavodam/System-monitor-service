package ru.diplom.monitoring.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import ru.diplom.monitoring.dto.MetricAnalysis;
import ru.diplom.monitoring.dto.MetricIngestRequest;
import ru.diplom.monitoring.dto.RegisterNodeRequest;
import ru.diplom.monitoring.model.MetricType;
import ru.diplom.monitoring.model.Node;
import ru.diplom.monitoring.repository.MetricRepository;
import ru.diplom.monitoring.repository.NodeRepository;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Тесты корреляционного анализа. Чтобы убедиться, что:
 * - Pearson correlation корректно считается (положительная / отрицательная / отсутствует);
 * - z-score выявляет аномалии;
 * - топ-N сортировка работает по |corr|;
 * - человекочитаемое резюме формируется.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AnalysisServiceTest {

    @Autowired private AnalysisService analysisService;
    @Autowired private MetricService metricService;
    @Autowired private NodeService nodeService;
    @Autowired private MetricRepository metricRepo;
    @Autowired private NodeRepository nodeRepo;

    private final String owner = "owner-x";
    private String nodeId;

    @BeforeEach
    void cleanup() {
        metricRepo.deleteAll();
        nodeRepo.deleteAll();
        RegisterNodeRequest req = new RegisterNodeRequest();
        req.setName("test-node");
        Node n = nodeService.register(owner, req);
        nodeId = n.getId();
    }

    private void ingest(String name, double value, Instant ts) {
        MetricIngestRequest r = new MetricIngestRequest();
        r.setName(name);
        r.setValue(value);
        r.setType(MetricType.GAUGE);
        r.setTimestamp(ts);
        metricService.ingest(nodeId, owner, r);
    }

    // =====================================================================
    // Чистая математика: pearson() и helpers — статические методы
    // =====================================================================

    @Test
    void pearson_perfectPositiveCorrelation_returnsOne() {
        double[] x = {1, 2, 3, 4, 5};
        double[] y = {2, 4, 6, 8, 10};
        assertThat(AnalysisService.pearson(x, y)).isCloseTo(1.0, offset(1e-6));
    }

    @Test
    void pearson_perfectNegativeCorrelation_returnsMinusOne() {
        double[] x = {1, 2, 3, 4, 5};
        double[] y = {10, 8, 6, 4, 2};
        assertThat(AnalysisService.pearson(x, y)).isCloseTo(-1.0, offset(1e-6));
    }

    @Test
    void pearson_constantSeries_returnsNaN() {
        double[] x = {1, 2, 3, 4, 5};
        double[] y = {7, 7, 7, 7, 7};
        assertThat(AnalysisService.pearson(x, y)).isNaN();
    }

    @Test
    void pearson_uncorrelated_isCloseToZero() {
        double[] x = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        double[] y = {3, 1, 4, 1, 5, 9, 2, 6, 5, 3};   // π-like noise
        double r = AnalysisService.pearson(x, y);
        assertThat(Math.abs(r)).isLessThan(0.6);
    }

    @Test
    void pearson_tooFewSamples_returnsNaN() {
        double[] x = {1, 2};
        double[] y = {3, 4};
        assertThat(AnalysisService.pearson(x, y)).isNaN();
    }

    // =====================================================================
    // Полный анализ через сервис — с записью в БД
    // =====================================================================

    @Test
    void analyze_detectsStrongPositiveCorrelation() {
        // Симулируем: cpu растёт, memory растёт ровно так же.
        // Должны увидеть корреляцию r ~ 1.
        Instant base = Instant.now().minusSeconds(600);
        for (int i = 0; i < 20; i++) {
            Instant t = base.plusSeconds(i * 30L);
            ingest("cpu.usage",     10 + i * 4, t);    // 10, 14, 18, ...
            ingest("memory.percent", 30 + i * 2, t);   // 30, 32, 34, ...
            ingest("disk.percent",  50, t);            // константа — должна отсеяться
        }

        MetricAnalysis r = analysisService.analyze(owner, nodeId, "cpu.usage",
                Instant.now(), 600, 30, 5);

        assertThat(r.getTarget()).isNotNull();
        assertThat(r.getTarget().getName()).isEqualTo("cpu.usage");
        assertThat(r.getTarget().getSampleCount()).isGreaterThanOrEqualTo(15);

        // memory.percent двигалась идеально с CPU
        MetricAnalysis.CorrelatedMetric mem = r.getCorrelations().stream()
                .filter(c -> "memory.percent".equals(c.getName()))
                .findFirst().orElseThrow();
        assertThat(mem.getCorrelation()).isGreaterThan(0.95);
        assertThat(mem.getStrength()).isEqualTo("STRONG");
        assertThat(mem.getDirection()).isEqualTo("POSITIVE");

        // disk.percent — константа, корреляция NaN, не должна попасть в top
        boolean diskInTop = r.getCorrelations().stream().anyMatch(c -> "disk.percent".equals(c.getName()));
        assertThat(diskInTop).isFalse();
    }

    @Test
    void analyze_detectsNegativeCorrelation() {
        Instant base = Instant.now().minusSeconds(600);
        for (int i = 0; i < 20; i++) {
            Instant t = base.plusSeconds(i * 30L);
            ingest("requests.served", 100 + i * 5, t);      // растёт
            ingest("requests.queue",  500 - i * 20, t);     // падает
        }

        MetricAnalysis r = analysisService.analyze(owner, nodeId, "requests.served",
                Instant.now(), 600, 30, 5);

        MetricAnalysis.CorrelatedMetric q = r.getCorrelations().stream()
                .filter(c -> "requests.queue".equals(c.getName()))
                .findFirst().orElseThrow();
        assertThat(q.getCorrelation()).isLessThan(-0.95);
        assertThat(q.getDirection()).isEqualTo("NEGATIVE");
    }

    @Test
    void analyze_zScoreFlagsAnomaly() {
        // 19 нормальных значений около 50, потом одна аномальная точка 95
        Instant base = Instant.now().minusSeconds(600);
        for (int i = 0; i < 19; i++) {
            ingest("cpu.usage", 50 + Math.sin(i) * 2, base.plusSeconds(i * 30L));
        }
        // последняя точка — выброс
        ingest("cpu.usage", 95.0, Instant.now().minusSeconds(5));

        MetricAnalysis r = analysisService.analyze(owner, nodeId, "cpu.usage",
                Instant.now(), 600, 30, 5);

        assertThat(r.getTarget().isAnomaly()).isTrue();
        assertThat(Math.abs(r.getTarget().getZScore())).isGreaterThan(2.0);
    }

    @Test
    void analyze_normalValueIsNotFlaggedAsAnomaly() {
        Instant base = Instant.now().minusSeconds(600);
        for (int i = 0; i < 20; i++) {
            ingest("cpu.usage", 50 + Math.sin(i) * 3, base.plusSeconds(i * 30L));
        }

        MetricAnalysis r = analysisService.analyze(owner, nodeId, "cpu.usage",
                Instant.now(), 600, 30, 5);

        assertThat(r.getTarget().isAnomaly()).isFalse();
        assertThat(Math.abs(r.getTarget().getZScore())).isLessThan(2.0);
    }

    @Test
    void analyze_topNLimit_sortsByAbsCorrelationDesc() {
        Instant base = Instant.now().minusSeconds(600);
        for (int i = 0; i < 20; i++) {
            Instant t = base.plusSeconds(i * 30L);
            ingest("cpu.usage",    i * 1.0, t);                      // целевая, идеально растёт
            ingest("perfect.pos",  i * 2.0, t);                      // r ~ 1
            ingest("perfect.neg",  100 - i * 2.0, t);                // r ~ -1
            ingest("weak.noisy",   50 + (i % 3 == 0 ? 5 : -3), t);   // слабая
        }

        MetricAnalysis r = analysisService.analyze(owner, nodeId, "cpu.usage",
                Instant.now(), 600, 30, 2);

        // topN = 2 → должны вернуться perfect.pos и perfect.neg (|r| ~ 1)
        assertThat(r.getCorrelations()).hasSize(2);
        List<String> names = r.getCorrelations().stream()
                .map(MetricAnalysis.CorrelatedMetric::getName).toList();
        assertThat(names).containsExactlyInAnyOrder("perfect.pos", "perfect.neg");

        // и они должны быть отсортированы по |r| (а не name)
        for (int i = 1; i < r.getCorrelations().size(); i++) {
            double prev = Math.abs(r.getCorrelations().get(i - 1).getCorrelation());
            double curr = Math.abs(r.getCorrelations().get(i).getCorrelation());
            assertThat(prev).isGreaterThanOrEqualTo(curr);
        }
    }

    @Test
    void analyze_insufficientDataReturnsHelpfulMessage() {
        // только 1 точка — анализ невозможен
        ingest("cpu.usage", 50.0, Instant.now());

        MetricAnalysis r = analysisService.analyze(owner, nodeId, "cpu.usage",
                Instant.now(), 600, 30, 5);

        assertThat(r.getCorrelations()).isEmpty();
        assertThat(r.getSummary()).contains("Недостаточно данных");
    }

    @Test
    void analyze_summaryContainsTargetName() {
        Instant base = Instant.now().minusSeconds(600);
        for (int i = 0; i < 20; i++) {
            ingest("cpu.usage", 50 + Math.sin(i), base.plusSeconds(i * 30L));
        }

        MetricAnalysis r = analysisService.analyze(owner, nodeId, "cpu.usage",
                Instant.now(), 600, 30, 5);

        assertThat(r.getSummary()).contains("cpu.usage");
    }

    @Test
    void analyze_respectsMultiTenancy() {
        // owner-y создаёт свой узел и заливает в него метрики
        RegisterNodeRequest other = new RegisterNodeRequest();
        other.setName("other-node");
        Node otherNode = nodeService.register("owner-y", other);

        Instant base = Instant.now().minusSeconds(600);
        for (int i = 0; i < 20; i++) {
            // метрики в чужой узел
            MetricIngestRequest mi = new MetricIngestRequest();
            mi.setName("cpu.usage");
            mi.setValue(99);
            mi.setType(MetricType.GAUGE);
            mi.setTimestamp(base.plusSeconds(i * 30L));
            metricService.ingest(otherNode.getId(), "owner-y", mi);
        }

        // владелец owner-x запрашивает анализ метрики, которой у него нет
        MetricAnalysis r = analysisService.analyze(owner, nodeId, "cpu.usage",
                Instant.now(), 600, 30, 5);

        // ничего не нашли (chusой owner отсёкся фильтром по ownerId)
        assertThat(r.getTarget().getSampleCount()).isZero();
        assertThat(r.getCorrelations()).isEmpty();
    }
}
