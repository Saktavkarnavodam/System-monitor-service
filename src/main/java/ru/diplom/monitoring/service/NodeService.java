package ru.diplom.monitoring.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.diplom.monitoring.dto.NodeProbeReport;
import ru.diplom.monitoring.dto.NodeProbeResult;
import ru.diplom.monitoring.dto.RegisterNodeRequest;
import ru.diplom.monitoring.model.Node;
import ru.diplom.monitoring.model.NodeStatus;
import ru.diplom.monitoring.model.ServiceTarget;
import ru.diplom.monitoring.repository.NodeRepository;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class NodeService {

    private static final Logger log = LoggerFactory.getLogger(NodeService.class);

    private final NodeRepository repo;

    private final Duration heartbeatTimeout = Duration.ofSeconds(30);
    private final Duration heartbeatDegraded = Duration.ofSeconds(15);

    /** Таймаут TCP-handshake при probe (мс). 3 сек — компромисс между точностью и UX. */
    private static final int PROBE_TIMEOUT_MS = 3000;

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

    /**
     * TCP-probe узла: пытаемся открыть сокет к {@code host:port} с коротким
     * таймаутом и сравниваем с heartbeat-возрастом. Результат позволяет UI
     * отличить «упал агент» (TCP отвечает, heartbeat устарел) от
     * «упал узел» (TCP не отвечает).
     *
     * <p>Никаких данных не пишется в БД — это разовый диагностический запрос.
     * Возвращает {@link NodeProbeResult#unconfigured} если порт не задан.
     *
     * <p><b>Security note:</b> probe — это outbound TCP-connect к адресу,
     * который пользователь сам указал при регистрации узла. SSRF-риск минимален,
     * т.к. пользователь и так знает этот адрес и может опросить его сам. Если
     * приложение когда-нибудь будет открыто публично — стоит ограничить
     * probe-частоту и блокировать loopback/RFC1918 для не-admin'ов.
     */
    @Transactional(readOnly = true)
    public NodeProbeResult probe(String id, String requesterId, boolean isAdmin) {
        Optional<Node> opt = repo.findById(id);
        if (opt.isEmpty()) {
            return NodeProbeResult.unconfigured("Узел не найден", null);
        }
        Node node = opt.get();
        if (!isAdmin && !node.getOwnerId().equals(requesterId)) {
            return NodeProbeResult.unconfigured("Нет доступа к этому узлу", null);
        }
        Long hbAge = (node.getLastHeartbeat() == null) ? null
                : Duration.between(node.getLastHeartbeat(), Instant.now()).toSeconds();

        if (node.getHost() == null || node.getHost().isBlank()) {
            return NodeProbeResult.unconfigured("Host не задан — probe невозможен", hbAge);
        }
        if (node.getPort() == null || node.getPort() <= 0) {
            return NodeProbeResult.unconfigured("Port не задан — probe невозможен", hbAge);
        }

        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(node.getHost(), node.getPort()), PROBE_TIMEOUT_MS);
            long latency = System.currentTimeMillis() - start;
            return NodeProbeResult.ok(latency, hbAge);
        } catch (IOException e) {
            log.debug("Probe {} ({}:{}) failed: {}",
                    node.getName(), node.getHost(), node.getPort(), e.getMessage());
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return NodeProbeResult.unreachable(msg, hbAge);
        }
    }

    /**
     * Probe всех сервисов узла (Variant B): для каждого {@link ServiceTarget}
     * — отдельный TCP-handshake. Используется новым UI «карточки узла»
     * со списком сервисов. Если у узла нет ни одного сервиса — возвращается
     * пустой список (UI покажет только heartbeat-вердикт).
     */
    @Transactional(readOnly = true)
    public NodeProbeReport probeAll(String id, String requesterId, boolean isAdmin) {
        NodeProbeReport report = new NodeProbeReport();
        Optional<Node> opt = repo.findById(id);
        if (opt.isEmpty()) return report;
        Node node = opt.get();
        if (!isAdmin && !node.getOwnerId().equals(requesterId)) return report;

        report.setHeartbeatAgeSeconds(node.getLastHeartbeat() == null ? null
                : Duration.between(node.getLastHeartbeat(), Instant.now()).toSeconds());

        List<NodeProbeReport.ServiceProbe> probes = new ArrayList<>();
        String host = node.getHost();
        if (host != null && !host.isBlank()) {
            for (ServiceTarget svc : node.getServices()) {
                if (svc == null || svc.getPort() <= 0) continue;
                probes.add(probeOne(host, svc));
            }
        }
        report.setServices(probes);
        return report;
    }

    private NodeProbeReport.ServiceProbe probeOne(String host, ServiceTarget svc) {
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, svc.getPort()), PROBE_TIMEOUT_MS);
            return NodeProbeReport.ServiceProbe.ok(svc.getName(), svc.getPort(),
                    System.currentTimeMillis() - start);
        } catch (IOException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return NodeProbeReport.ServiceProbe.fail(svc.getName(), svc.getPort(), msg);
        }
    }

    /**
     * Частичное обновление узла из UI: host + список сервисов. Поля {@code null}
     * не трогаем (это PATCH-семантика). Возвращает обновлённый узел или empty,
     * если узла нет или нет доступа.
     */
    public Optional<Node> patch(String id, String requesterId, boolean isAdmin,
                                String host, List<ServiceTarget> services) {
        Optional<Node> opt = repo.findById(id);
        if (opt.isEmpty()) return Optional.empty();
        Node node = opt.get();
        if (!isAdmin && !node.getOwnerId().equals(requesterId)) return Optional.empty();
        if (host != null) {
            node.setHost(host.isBlank() ? null : host.trim());
        }
        if (services != null) {
            // Нормализуем: убираем пустые имена и невалидные порты
            List<ServiceTarget> clean = new ArrayList<>();
            for (ServiceTarget s : services) {
                if (s == null) continue;
                if (s.getPort() <= 0 || s.getPort() > 65535) continue;
                String n = s.getName() == null ? "" : s.getName().trim();
                if (n.isBlank()) n = "svc-" + s.getPort();
                if (n.length() > 64) n = n.substring(0, 64);
                clean.add(new ServiceTarget(n, s.getPort()));
            }
            node.setServices(clean);
        }
        return Optional.of(repo.save(node));
    }
}
