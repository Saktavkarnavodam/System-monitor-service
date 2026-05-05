package ru.diplom.monitoring.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import ru.diplom.monitoring.dto.RegisterNodeRequest;
import ru.diplom.monitoring.model.Node;
import ru.diplom.monitoring.model.NodeStatus;
import ru.diplom.monitoring.repository.NodeRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NodeServiceTest {

    @Autowired
    private NodeService service;

    @Autowired
    private NodeRepository repo;

    @BeforeEach
    void cleanup() {
        repo.deleteAll();
    }

    private RegisterNodeRequest req(String name) {
        RegisterNodeRequest r = new RegisterNodeRequest();
        r.setName(name);
        r.setHost("localhost");
        r.setPort(9000);
        r.setType("worker");
        return r;
    }

    @Test
    void register_assignsOwnerAndDefaults() {
        Node n = service.register("user-1", req("worker-1"));
        assertThat(n.getId()).isNotBlank();
        assertThat(n.getOwnerId()).isEqualTo("user-1");
        assertThat(n.getStatus()).isEqualTo(NodeStatus.HEALTHY);
        assertThat(n.getRegisteredAt()).isNotNull();
        assertThat(n.getLastHeartbeat()).isNotNull();
    }

    @Test
    void list_filtersByOwner_unlessAdmin() {
        service.register("user-1", req("a"));
        service.register("user-1", req("b"));
        service.register("user-2", req("c"));

        assertThat(service.list("user-1", false)).hasSize(2);
        assertThat(service.list("user-2", false)).hasSize(1);
        assertThat(service.list("user-1", true)).hasSize(3); // admin видит всё
    }

    @Test
    void find_blocksOtherOwners() {
        Node n = service.register("user-1", req("a"));
        Optional<Node> own = service.find(n.getId(), "user-1", false);
        Optional<Node> stranger = service.find(n.getId(), "user-2", false);
        Optional<Node> admin = service.find(n.getId(), "user-2", true);
        assertThat(own).isPresent();
        assertThat(stranger).isEmpty();
        assertThat(admin).isPresent();
    }

    @Test
    void heartbeat_updatesStatusAndTimestamp() throws InterruptedException {
        Node n = service.register("user-1", req("a"));
        n.setStatus(NodeStatus.UNHEALTHY);
        repo.save(n);
        Thread.sleep(5);
        Optional<Node> updated = service.heartbeat(n.getId(), "user-1", false);
        assertThat(updated).isPresent();
        assertThat(updated.get().getStatus()).isEqualTo(NodeStatus.HEALTHY);
    }

    @Test
    void heartbeat_blocksOtherOwners() {
        Node n = service.register("user-1", req("a"));
        Optional<Node> blocked = service.heartbeat(n.getId(), "user-2", false);
        assertThat(blocked).isEmpty();
    }

    @Test
    void deregister_removesOnlyOwnNodes() {
        Node a = service.register("user-1", req("a"));
        Node b = service.register("user-2", req("b"));
        assertThat(service.deregister(a.getId(), "user-2", false)).isFalse();
        assertThat(service.deregister(b.getId(), "user-2", false)).isTrue();
        assertThat(repo.count()).isEqualTo(1L);
    }

    @Test
    void ownedNodeIds_returnsOnlyOwnerIds() {
        service.register("user-1", req("a"));
        service.register("user-1", req("b"));
        service.register("user-2", req("c"));
        List<String> ids = service.ownedNodeIds("user-1");
        assertThat(ids).hasSize(2);
    }
}
