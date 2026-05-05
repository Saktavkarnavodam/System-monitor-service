package ru.diplom.monitoring.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import ru.diplom.monitoring.model.Alert;
import ru.diplom.monitoring.model.AlertSeverity;
import ru.diplom.monitoring.model.AlertStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class AlertRepositoryTest {

    @Autowired
    private AlertRepository repo;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
    }

    private Alert make(String ownerId, String ruleId, String nodeId, AlertStatus status, Instant firedAt) {
        Alert a = new Alert();
        a.setId(UUID.randomUUID().toString());
        a.setOwnerId(ownerId);
        a.setRuleId(ruleId);
        a.setRuleName("rule-" + ruleId);
        a.setNodeId(nodeId);
        a.setMetricName("cpu.usage");
        a.setValue(99.0);
        a.setThreshold(80.0);
        a.setSeverity(AlertSeverity.WARNING);
        a.setStatus(status);
        a.setFiredAt(firedAt);
        return repo.save(a);
    }

    @Test
    void findByStatus_returnsMatching() {
        make("o1", "r1", "n1", AlertStatus.FIRING, Instant.now());
        make("o1", "r2", "n1", AlertStatus.RESOLVED, Instant.now());
        assertThat(repo.findByStatus(AlertStatus.FIRING)).hasSize(1);
        assertThat(repo.findByStatus(AlertStatus.RESOLVED)).hasSize(1);
    }

    @Test
    void findByOwnerIdAndStatus_filtersByBoth() {
        make("o1", "r1", "n1", AlertStatus.FIRING, Instant.now());
        make("o2", "r1", "n2", AlertStatus.FIRING, Instant.now());
        assertThat(repo.findByOwnerIdAndStatus("o1", AlertStatus.FIRING)).hasSize(1);
        assertThat(repo.findByOwnerIdAndStatus("o2", AlertStatus.FIRING)).hasSize(1);
    }

    @Test
    void findFirstByRuleIdAndNodeIdAndStatus_findsFiring() {
        make("o1", "r1", "n1", AlertStatus.FIRING, Instant.now());
        Optional<Alert> found = repo.findFirstByRuleIdAndNodeIdAndStatus("r1", "n1", AlertStatus.FIRING);
        assertThat(found).isPresent();
        assertThat(found.get().getRuleId()).isEqualTo("r1");
    }

    @Test
    void findFirstByRuleIdAndNodeIdAndStatus_handlesNullNodeId() {
        make("o1", "r1", null, AlertStatus.FIRING, Instant.now());
        Optional<Alert> found = repo.findFirstByRuleIdAndNodeIdAndStatus("r1", null, AlertStatus.FIRING);
        assertThat(found).isPresent();
    }

    @Test
    void findAllOrderByFiredAtDesc_sortedDesc() {
        Instant base = Instant.parse("2026-04-26T10:00:00Z");
        make("o1", "r1", "n1", AlertStatus.RESOLVED, base);
        make("o1", "r2", "n2", AlertStatus.RESOLVED, base.plusSeconds(60));
        make("o1", "r3", "n3", AlertStatus.RESOLVED, base.plusSeconds(120));
        List<Alert> all = repo.findAllOrderByFiredAtDesc(PageRequest.of(0, 10));
        assertThat(all).hasSize(3);
        assertThat(all.get(0).getFiredAt()).isEqualTo(base.plusSeconds(120));
        assertThat(all.get(2).getFiredAt()).isEqualTo(base);
    }
}
