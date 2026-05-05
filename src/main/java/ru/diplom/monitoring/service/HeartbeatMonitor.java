package ru.diplom.monitoring.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Фоновые задачи поддержания состояния системы:
 *  - обновление статусов узлов по heartbeat-таймаутам;
 *  - оценка алертов;
 *  - retention метрик: удаление старых time-series точек, чтобы БД не пухла.
 */
@Component
public class HeartbeatMonitor {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatMonitor.class);

    private final NodeService nodeService;
    private final AlertService alertService;
    private final MetricService metricService;

    /** Сколько хранить метрики (часы). Конфигурится через app.metrics.retention-hours. */
    @Value("${app.metrics.retention-hours:168}")
    private long retentionHours;

    public HeartbeatMonitor(NodeService nodeService, AlertService alertService, MetricService metricService) {
        this.nodeService = nodeService;
        this.alertService = alertService;
        this.metricService = metricService;
    }

    @Scheduled(fixedDelay = 5000L)
    public void refreshNodeStatuses() {
        try {
            nodeService.refreshStatuses();
        } catch (Exception e) {
            log.warn("Node status refresh failed: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 10000L)
    public void evaluateAlerts() {
        try {
            alertService.evaluate();
        } catch (Exception e) {
            log.warn("Alert evaluation failed: {}", e.getMessage());
        }
    }

    /**
     * Чистит метрики старше retentionHours. Запускается раз в час.
     * DELETE по индексу idx_metrics_ts — быстрый сегментный delete.
     */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 60_000L)
    public void retentionMetrics() {
        try {
            Instant cutoff = Instant.now().minus(Duration.ofHours(retentionHours));
            int deleted = metricService.deleteOlderThan(cutoff);
            if (deleted > 0) {
                log.info("Retention: удалено {} метрик старше {}", deleted, cutoff);
            }
        } catch (Exception e) {
            log.warn("Metric retention failed: {}", e.getMessage());
        }
    }
}
