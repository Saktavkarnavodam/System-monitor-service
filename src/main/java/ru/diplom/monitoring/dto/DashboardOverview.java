package ru.diplom.monitoring.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.diplom.monitoring.model.Alert;
import ru.diplom.monitoring.model.Node;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Schema(description = "Сводный отчёт о состоянии распределённой системы")
public class DashboardOverview {
    private Instant generatedAt;
    private int totalNodes;
    private Map<String, Long> nodeStatusCounts;
    private int activeAlerts;
    private Map<String, Long> alertSeverityCounts;
    private long totalMetricsStored;
    private List<Node> nodes;
    private List<Alert> recentAlerts;

    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }

    public int getTotalNodes() { return totalNodes; }
    public void setTotalNodes(int totalNodes) { this.totalNodes = totalNodes; }

    public Map<String, Long> getNodeStatusCounts() { return nodeStatusCounts; }
    public void setNodeStatusCounts(Map<String, Long> nodeStatusCounts) { this.nodeStatusCounts = nodeStatusCounts; }

    public int getActiveAlerts() { return activeAlerts; }
    public void setActiveAlerts(int activeAlerts) { this.activeAlerts = activeAlerts; }

    public Map<String, Long> getAlertSeverityCounts() { return alertSeverityCounts; }
    public void setAlertSeverityCounts(Map<String, Long> alertSeverityCounts) { this.alertSeverityCounts = alertSeverityCounts; }

    public long getTotalMetricsStored() { return totalMetricsStored; }
    public void setTotalMetricsStored(long totalMetricsStored) { this.totalMetricsStored = totalMetricsStored; }

    public List<Node> getNodes() { return nodes; }
    public void setNodes(List<Node> nodes) { this.nodes = nodes; }

    public List<Alert> getRecentAlerts() { return recentAlerts; }
    public void setRecentAlerts(List<Alert> recentAlerts) { this.recentAlerts = recentAlerts; }
}
