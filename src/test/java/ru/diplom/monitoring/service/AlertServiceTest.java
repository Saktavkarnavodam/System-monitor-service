package ru.diplom.monitoring.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import ru.diplom.monitoring.dto.MetricIngestRequest;
import ru.diplom.monitoring.dto.RegisterNodeRequest;
import ru.diplom.monitoring.model.Alert;
import ru.diplom.monitoring.model.AlertRule;
import ru.diplom.monitoring.model.AlertSeverity;
import ru.diplom.monitoring.model.AlertStatus;
import ru.diplom.monitoring.model.Condition;
import ru.diplom.monitoring.model.MetricType;
import ru.diplom.monitoring.model.Node;
import ru.diplom.monitoring.repository.AlertRepository;
import ru.diplom.monitoring.repository.AlertRuleRepository;
import ru.diplom.monitoring.repository.MetricRepository;
import ru.diplom.monitoring.repository.NodeRepository;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AlertServiceTest {

    @Autowired private AlertService alertService;
    @Autowired private MetricService metricService;
    @Autowired private NodeService nodeService;
    @Autowired private AlertRepository alertRepo;
    @Autowired private AlertRuleRepository ruleRepo;
    @Autowired private MetricRepository metricRepo;
    @Autowired private NodeRepository nodeRepo;

    private final String ownerA = "owner-a";
    private String nodeId;

    @BeforeEach
    void cleanup() {
        alertRepo.deleteAll();
        ruleRepo.deleteAll();
        metricRepo.deleteAll();
        nodeRepo.deleteAll();
        RegisterNodeRequest r = new RegisterNodeRequest();
        r.setName("test-node");
        Node n = nodeService.register(ownerA, r);
        nodeId = n.getId();
    }

    private MetricIngestRequest req(double value, Instant ts) {
        MetricIngestRequest r = new MetricIngestRequest();
        r.setName("cpu.usage");
        r.setValue(value);
        r.setType(MetricType.GAUGE);
        r.setTimestamp(ts);
        return r;
    }

    private AlertRule rule() {
        AlertRule r = new AlertRule();
        r.setName("high-cpu");
        r.setMetricName("cpu.usage");
        r.setNodeId(nodeId);
        r.setCondition(Condition.GT);
        r.setThreshold(80.0);
        r.setDurationSeconds(60);
        r.setSeverity(AlertSeverity.CRITICAL);
        r.setEnabled(true);
        return r;
    }

    @Test
    void saveRule_assignsOwnerAndId() {
        AlertRule saved = alertService.saveRule(ownerA, rule());
        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getOwnerId()).isEqualTo(ownerA);
        assertThat(ruleRepo.count()).isEqualTo(1L);
    }

    @Test
    void saveRule_rejectsNotOwnedNode() {
        AlertRule r = rule();
        r.setNodeId("not-mine");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> alertService.saveRule(ownerA, r));
    }

    @Test
    void evaluate_firesAlertWhenAllSamplesViolate() {
        alertService.saveRule(ownerA, rule());
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            metricService.ingest(nodeId, ownerA, req(95.0 + i, now.minusSeconds(50 - i * 10L)));
        }

        alertService.evaluate();

        List<Alert> firing = alertRepo.findByStatus(AlertStatus.FIRING);
        assertThat(firing).hasSize(1);
        assertThat(firing.get(0).getMetricName()).isEqualTo("cpu.usage");
    }

    @Test
    void evaluate_doesNotFireIfAnyOk() {
        alertService.saveRule(ownerA, rule());
        Instant now = Instant.now();
        metricService.ingest(nodeId, ownerA, req(95.0, now.minusSeconds(40)));
        metricService.ingest(nodeId, ownerA, req(50.0, now.minusSeconds(20))); // нарушает условие "all"
        metricService.ingest(nodeId, ownerA, req(90.0, now));
        alertService.evaluate();
        assertThat(alertRepo.findByStatus(AlertStatus.FIRING)).isEmpty();
    }

    @Test
    void evaluate_resolvesWhenMetricRecovers() {
        alertService.saveRule(ownerA, rule());
        // 1) первый прогон: все точки нарушают порог -> FIRING
        Instant t0 = Instant.now();
        for (int i = 0; i < 3; i++) {
            metricService.ingest(nodeId, ownerA, req(99.0, t0.minusSeconds(30 - i * 5L)));
        }
        alertService.evaluate();
        assertThat(alertRepo.findByStatus(AlertStatus.FIRING)).hasSize(1);

        // 2) добавляем нормальное значение в текущее окно (now); rule.allMatch
        //    будет видеть 99,99,99,50 -> не все нарушают -> RESOLVE
        metricService.ingest(nodeId, ownerA, req(50.0, Instant.now()));
        alertService.evaluate();
        assertThat(alertRepo.findByStatus(AlertStatus.FIRING)).isEmpty();
        assertThat(alertRepo.findByStatus(AlertStatus.RESOLVED)).hasSize(1);
    }

    @Test
    void listRules_isolatedByOwner() {
        alertService.saveRule(ownerA, rule());
        AlertRule r2 = rule();
        r2.setNodeId(null); // global
        alertService.saveRule("owner-b", r2);
        assertThat(alertService.listRules(ownerA, false)).hasSize(1);
        assertThat(alertService.listRules("owner-b", false)).hasSize(1);
        assertThat(alertService.listRules(ownerA, true)).hasSize(2);
    }

    @Test
    void deleteRule_resolvesActiveAlerts() {
        AlertRule saved = alertService.saveRule(ownerA, rule());
        Instant now = Instant.now();
        for (int i = 0; i < 3; i++) {
            metricService.ingest(nodeId, ownerA, req(99.0, now.minusSeconds(30 - i * 5L)));
        }
        alertService.evaluate();
        assertThat(alertRepo.findByStatus(AlertStatus.FIRING)).hasSize(1);

        boolean deleted = alertService.deleteRule(saved.getId(), ownerA, false);
        assertThat(deleted).isTrue();
        assertThat(alertRepo.findByStatus(AlertStatus.FIRING)).isEmpty();
        assertThat(alertRepo.findByStatus(AlertStatus.RESOLVED)).hasSize(1);
    }
}
