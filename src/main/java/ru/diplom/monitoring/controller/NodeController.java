package ru.diplom.monitoring.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.diplom.monitoring.dto.NodePatchRequest;
import ru.diplom.monitoring.dto.NodeProbeReport;
import ru.diplom.monitoring.dto.NodeProbeResult;
import ru.diplom.monitoring.dto.RegisterNodeRequest;
import ru.diplom.monitoring.exception.NotFoundException;
import ru.diplom.monitoring.model.Metric;
import ru.diplom.monitoring.model.Node;
import ru.diplom.monitoring.model.NodeStatus;
import ru.diplom.monitoring.model.User;
import ru.diplom.monitoring.security.CurrentUser;
import ru.diplom.monitoring.service.AlertService;
import ru.diplom.monitoring.service.DownsamplingService;
import ru.diplom.monitoring.service.MetricService;
import ru.diplom.monitoring.service.NodeService;

import java.util.Collection;
import java.util.List;

@RestController
@RequestMapping("/api/v1/nodes")
@Tag(name = "Nodes", description = "Управление узлами распределённой системы (видны только узлы текущего пользователя)")
@SecurityRequirement(name = "bearerAuth")
public class NodeController {

    private final NodeService nodeService;
    private final MetricService metricService;
    private final AlertService alertService;
    private final DownsamplingService downsamplingService;
    private final CurrentUser currentUser;

    public NodeController(NodeService nodeService,
                          MetricService metricService,
                          AlertService alertService,
                          DownsamplingService downsamplingService,
                          CurrentUser currentUser) {
        this.nodeService = nodeService;
        this.metricService = metricService;
        this.alertService = alertService;
        this.downsamplingService = downsamplingService;
        this.currentUser = currentUser;
    }

    @PostMapping
    @Operation(summary = "Зарегистрировать новый узел (привязывается к текущему пользователю)")
    public Node register(@Valid @RequestBody RegisterNodeRequest req) {
        User u = currentUser.require();
        return nodeService.register(u.getId(), req);
    }

    @GetMapping
    @Operation(summary = "Получить список узлов текущего пользователя")
    public Collection<Node> list(@RequestParam(required = false) NodeStatus status) {
        User u = currentUser.require();
        return status == null
                ? nodeService.list(u.getId(), u.isAdmin())
                : nodeService.listByStatus(status, u.getId(), u.isAdmin());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Получить узел по идентификатору (только если принадлежит текущему пользователю)")
    public Node get(@PathVariable String id) {
        User u = currentUser.require();
        return nodeService.find(id, u.getId(), u.isAdmin())
                .orElseThrow(() -> new NotFoundException("Узел не найден: " + id));
    }

    @PostMapping("/{id}/heartbeat")
    @Operation(summary = "Heartbeat от узла")
    public Node heartbeat(@PathVariable String id) {
        User u = currentUser.require();
        return nodeService.heartbeat(id, u.getId(), u.isAdmin())
                .orElseThrow(() -> new NotFoundException("Узел не найден: " + id));
    }

    @GetMapping("/{id}/metrics/latest")
    @Operation(summary = "Последние значения всех метрик узла")
    public List<Metric> latestMetrics(@PathVariable String id) {
        User u = currentUser.require();
        nodeService.find(id, u.getId(), u.isAdmin())
                .orElseThrow(() -> new NotFoundException("Узел не найден: " + id));
        return metricService.latestPerMetric(id);
    }

    @PostMapping("/{id}/probe")
    @Operation(summary = "TCP-probe узла (legacy) — отличает «упал агент» от «упал узел»",
            description = "Открывает TCP-сокет к host:port узла с таймаутом 3 сек. " +
                    "Возвращает heartbeatAgeSeconds + reachable. Используется UI до перехода на " +
                    "/probe-services. Оставлено для обратной совместимости.")
    public NodeProbeResult probe(@PathVariable String id) {
        User u = currentUser.require();
        NodeProbeResult r = nodeService.probe(id, u.getId(), u.isAdmin());
        if (!r.isProbed() && "Узел не найден".equals(r.getMessage())) {
            throw new NotFoundException("Узел не найден: " + id);
        }
        return r;
    }

    @PostMapping("/{id}/probe-services")
    @Operation(summary = "TCP-probe всех сервисов узла (Variant B)",
            description = "Прозванивает каждый сервис из списка node.services отдельным TCP-handshake. " +
                    "Возвращает heartbeatAgeSeconds + массив результатов по сервисам.")
    public NodeProbeReport probeServices(@PathVariable String id) {
        User u = currentUser.require();
        // Сначала проверяем, что узел вообще есть и доступен пользователю
        nodeService.find(id, u.getId(), u.isAdmin())
                .orElseThrow(() -> new NotFoundException("Узел не найден: " + id));
        return nodeService.probeAll(id, u.getId(), u.isAdmin());
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Обновить host и/или список сервисов узла",
            description = "PATCH-семантика: поля, переданные как null, не меняются. " +
                    "services=[] очищает список сервисов.")
    public Node patch(@PathVariable String id, @RequestBody NodePatchRequest req) {
        User u = currentUser.require();
        return nodeService.patch(id, u.getId(), u.isAdmin(), req.getHost(), req.getServices())
                .orElseThrow(() -> new NotFoundException("Узел не найден: " + id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Снять узел с регистрации")
    public ResponseEntity<Void> deregister(@PathVariable String id) {
        User u = currentUser.require();
        if (!nodeService.deregister(id, u.getId(), u.isAdmin())) {
            throw new NotFoundException("Узел не найден: " + id);
        }
        metricService.purgeNode(id);
        downsamplingService.purgeNode(id);
        alertService.purgeForNode(id);
        return ResponseEntity.noContent().build();
    }
}
