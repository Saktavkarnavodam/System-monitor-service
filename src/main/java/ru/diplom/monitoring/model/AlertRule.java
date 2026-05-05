package ru.diplom.monitoring.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "alert_rules", indexes = {
        @Index(name = "idx_alert_rules_owner", columnList = "owner_id"),
        @Index(name = "idx_alert_rules_enabled_metric", columnList = "enabled, metric_name")
})
public class AlertRule {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "metric_name", nullable = false, length = 128)
    private String metricName;

    @Column(name = "node_id", length = 36)
    private String nodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_op", nullable = false, length = 8)
    private Condition condition = Condition.GT;

    @Column(nullable = false)
    private double threshold;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds = 30;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AlertSeverity severity = AlertSeverity.WARNING;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(length = 500)
    private String description;

    public AlertRule() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public Condition getCondition() { return condition; }
    public void setCondition(Condition condition) { this.condition = condition; }

    public double getThreshold() { return threshold; }
    public void setThreshold(double threshold) { this.threshold = threshold; }

    public int getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(int durationSeconds) { this.durationSeconds = durationSeconds; }

    public AlertSeverity getSeverity() { return severity; }
    public void setSeverity(AlertSeverity severity) { this.severity = severity; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
