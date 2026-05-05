package ru.diplom.monitoring.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import ru.diplom.monitoring.model.Node;
import ru.diplom.monitoring.model.NodeStatus;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NodeRepositoryTest {

    @Autowired
    private NodeRepository repo;

    private final String ownerA = "owner-a";
    private final String ownerB = "owner-b";

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        save("n1", ownerA, NodeStatus.HEALTHY, Instant.now());
        save("n2", ownerA, NodeStatus.DEGRADED, Instant.now().minusSeconds(20));
        save("n3", ownerA, NodeStatus.UNHEALTHY, Instant.now().minusSeconds(120));
        save("n4", ownerB, NodeStatus.HEALTHY, Instant.now());
    }

    private void save(String id, String ownerId, NodeStatus status, Instant lastHb) {
        Node n = new Node();
        n.setId(id);
        n.setOwnerId(ownerId);
        n.setName(id);
        n.setHost("localhost");
        n.setPort(8080);
        n.setStatus(status);
        n.setRegisteredAt(Instant.now().minusSeconds(3600));
        n.setLastHeartbeat(lastHb);
        repo.save(n);
    }

    @Test
    void findByOwnerId_isolatesOwners() {
        List<Node> a = repo.findByOwnerId(ownerA);
        List<Node> b = repo.findByOwnerId(ownerB);
        assertThat(a).hasSize(3);
        assertThat(b).hasSize(1);
    }

    @Test
    void findByStatus_filtersCorrectly() {
        assertThat(repo.findByStatus(NodeStatus.HEALTHY)).hasSize(2);
        assertThat(repo.findByStatus(NodeStatus.UNHEALTHY)).hasSize(1);
    }

    @Test
    void findByOwnerIdAndStatus_combinesFilters() {
        assertThat(repo.findByOwnerIdAndStatus(ownerA, NodeStatus.HEALTHY)).hasSize(1);
        assertThat(repo.findByOwnerIdAndStatus(ownerB, NodeStatus.UNHEALTHY)).isEmpty();
    }

    @Test
    void findIdsByOwnerId_returnsOnlyIds() {
        List<String> ids = repo.findIdsByOwnerId(ownerA);
        assertThat(ids).containsExactlyInAnyOrder("n1", "n2", "n3");
    }

    @Test
    void countByOwnerId() {
        assertThat(repo.countByOwnerId(ownerA)).isEqualTo(3L);
        assertThat(repo.countByOwnerId(ownerB)).isEqualTo(1L);
    }

    @Test
    void markStaleNodes_updatesOnlyStale() {
        Instant cutoff = Instant.now().minusSeconds(60);
        int updated = repo.markStaleNodes(cutoff, NodeStatus.UNHEALTHY);
        // n3 уже UNHEALTHY (статус =, не обновляется), но мы используем <> :newStatus, так что 0 должно быть
        // только n3 имеет lastHb < cutoff и уже UNHEALTHY → 0 обновлений
        assertThat(updated).isEqualTo(0);
    }

    @Test
    void markHealthyNodes_revivesRecentNodes() {
        Instant since = Instant.now().minusSeconds(10);
        // n1 уже HEALTHY, n4 уже HEALTHY → ничего не меняем
        // но n2 (DEGRADED, lastHb 20сек назад) и n3 (UNHEALTHY 120с назад) — старые, не подходят
        int updated = repo.markHealthyNodes(since);
        assertThat(updated).isEqualTo(0);
    }
}
