package ru.diplom.monitoring.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.diplom.monitoring.dto.DashboardOverview;
import ru.diplom.monitoring.model.Alert;
import ru.diplom.monitoring.model.AlertSeverity;
import ru.diplom.monitoring.model.Node;
import ru.diplom.monitoring.model.NodeStatus;
import ru.diplom.monitoring.model.User;
import ru.diplom.monitoring.security.CurrentUser;
import ru.diplom.monitoring.service.AlertService;
import ru.diplom.monitoring.service.MetricService;
import ru.diplom.monitoring.service.NodeService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "Сводные данные для текущего пользователя")
@SecurityRequirement(name = "bearerAuth")
public class DashboardController {

    private final NodeService nodeService;
    private final MetricService metricService;
    private final AlertService alertService;
    private final CurrentUser currentUser;

    public DashboardController(NodeService nodeService, MetricService metricService,
                               AlertService alertService, CurrentUser currentUser) {
        this.nodeService = nodeService;
        this.metricService = metricService;
        this.alertService = alertService;
        this.currentUser = currentUser;
    }

    @GetMapping("/overview")
    @Operation(summary = "Сводная информация о состоянии распределённой системы (только узлы пользователя)")
    public DashboardOverview overview() {
        User u = currentUser.require();
        DashboardOverview o = new DashboardOverview();
        o.setGeneratedAt(Instant.now());

        List<Node> nodes = new ArrayList<>(nodeService.list(u.getId(), u.isAdmin()));
        o.setNodes(nodes);
        o.setTotalNodes(nodes.size());

        EnumMap<NodeStatus, Long> statusCounts = new EnumMap<>(NodeStatus.class);
        for (NodeStatus s : NodeStatus.values()) statusCounts.put(s, 0L);
        for (Node n : nodes) statusCounts.merge(n.getStatus(), 1L, Long::sum);
        o.setNodeStatusCounts(toStringMap(statusCounts));

        List<Alert> active = new ArrayList<>(alertService.listActive(u.getId(), u.isAdmin()));
        o.setActiveAlerts(active.size());

        EnumMap<AlertSeverity, Long> sev = new EnumMap<>(AlertSeverity.class);
        for (AlertSeverity s : AlertSeverity.values()) sev.put(s, 0L);
        for (Alert a : active) sev.merge(a.getSeverity(), 1L, Long::sum);
        o.setAlertSeverityCounts(toStringMap(sev));

        o.setRecentAlerts(alertService.history(u.getId(), u.isAdmin(), 10));
        o.setTotalMetricsStored(metricService.storedCountFor(u.isAdmin() ? null : u.getId()));
        return o;
    }

    private <K extends Enum<K>> Map<String, Long> toStringMap(EnumMap<K, Long> in) {
        java.util.LinkedHashMap<String, Long> out = new java.util.LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k.name(), v));
        return out;
    }
}
