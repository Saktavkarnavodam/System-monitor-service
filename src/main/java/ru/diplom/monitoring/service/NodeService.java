package ru.diplom.monitoring.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.diplom.monitoring.dto.RegisterNodeRequest;
import ru.diplom.monitoring.model.Node;
import ru.diplom.monitoring.model.NodeStatus;
import ru.diplom.monitoring.repository.NodeRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class NodeService {

    private final NodeRepository repo;

    private final Duration heartbeatTimeout = Duration.ofSeconds(30);
    private final Duration heartbeatDegraded = Duration.ofSeconds(15);

    public NodeService(NodeRepository repo) {
        this.repo = repo;
    }

    public Node register(String ownerId, RegisterNodeRequest req) {
        Node node = new Node();
        node.setId(UUID.randomUUID().toString());
        node.setOwnerId(ownerId);
        node.setName(req.getName());
        node.setHost(req.getHost());
        node.setPort(req.getPort());
        node.setType(req.getType());
        node.setTags(req.getTags());
        node.setRegisteredAt(Instant.now());
        node.setLastHeartbeat(Instant.now());
        node.setStatus(NodeStatus.HEALTHY);
        return repo.save(node);
    }

    @Transactional(readOnly = true)
    public Optional<Node> find(String id, String requesterId, boolean isAdmin) {
        return repo.findById(id).filter(n -> isAdmin || n.getOwnerId().equals(requesterId));
    }

    @Transactional(readOnly = true)
    public List<Node> list(String requesterId, boolean isAdmin) {
        return isAdmin ? repo.findAll() : repo.findByOwnerId(requesterId);
    }

    @Transactional(readOnly = true)
    public List<Node> listByStatus(NodeStatus status, String requesterId, boolean isAdmin) {
        return isAdmin ? repo.findByStatus(status) : repo.findByOwnerIdAndStatus(requesterId, status);
    }

    @Transactional(readOnly = true)
    public List<String> ownedNodeIds(String ownerId) {
        return repo.findIdsByOwnerId(ownerId);
    }

    public boolean deregister(String id, String requesterId, boolean isAdmin) {
        Optional<Node> n = repo.findById(id);
        if (n.isEmpty()) return false;
        if (!isAdmin && !n.get().getOwnerId().equals(requesterId)) return false;
        repo.deleteById(id);
        return true;
    }

    public Optional<Node> heartbeat(String id, String requesterId, boolean isAdmin) {
        Optional<Node> opt = repo.findById(id);
        if (opt.isEmpty()) return Optional.empty();
        Node node = opt.get();
        if (!isAdmin && !node.getOwnerId().equals(requesterId)) return Optional.empty();
        node.setLastHeartbeat(Instant.now());
        node.setStatus(NodeStatus.HEALTHY);
        return Optional.of(repo.save(node));
    }

    /**
     * Bulk-обновление статусов узлов на основе heartbeat-таймаутов.
     * Использует индекс idx_nodes_last_heartbeat — фильтр стоит на колонке.
     */
    public void refreshStatuses() {
        Instant now = Instant.now();
        // 1) узлы, не отвечавшие дольше heartbeatTimeout — UNHEALTHY
        repo.markStaleNodes(now.minus(heartbeatTimeout), NodeStatus.UNHEALTHY);
        // 2) узлы между degraded и timeout — DEGRADED
        // (помечаем все «не-HEALTHY с последним heartbeat между cutoffs» — упрощённо
        //  делаем второй UPDATE для DEGRADED: heartbeat позже timeout, но раньше degraded-границы)
        // В JPQL это делается простой эмуляцией:
        markDegraded(now.minus(heartbeatDegraded), now.minus(heartbeatTimeout));
        // 3) узлы со свежим heartbeat — HEALTHY (на случай восстановления)
        repo.markHealthyNodes(now.minus(heartbeatDegraded));
    }

    /** Помечает узлы между degraded-cutoff и unhealthy-cutoff как DEGRADED. */
    private void markDegraded(Instant degradedCutoff, Instant unhealthyCutoff) {
        List<Node> all = repo.findAll();
        for (Node n : all) {
            Instant hb = n.getLastHeartbeat();
            if (hb == null) continue;
            if (hb.isBefore(degradedCutoff) && !hb.isBefore(unhealthyCutoff)) {
                if (n.getStatus() != NodeStatus.DEGRADED) {
                    n.setStatus(NodeStatus.DEGRADED);
                    repo.save(n);
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public long countOwnedBy(String ownerId) {
        return repo.countByOwnerId(ownerId);
    }

    @Transactional(readOnly = true)
    public long countAll() {
        return repo.count();
    }
}
