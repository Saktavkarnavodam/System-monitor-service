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
 *  - retention raw-метрик (короткое окно);
 *  - downsampling raw → metrics_5m (Thanos Compactor-аналог);
 *  - retention downsampled (длинное окно).
 */
@Component
public class HeartbeatMonitor {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatMonitor.class);

    private final NodeService nodeService;
    private final AlertService alertService;
    private final MetricService metricService;
    private final DownsamplingService downsamplingService;

    /**
     * Сколько хранить raw-метрики (часы). По умолч. 24 ч — этого достаточно,
     * чтобы успел отработать downsampler. Графики за период длиннее
     * рисуются по metrics_5m.
     */
    @Value("${app.metrics.retention-hours:24}")
    private long rawRetentionHours;

    /** Сколько хранить downsampled-точки (часы). По умолч. 30 дней. */
    @Value("${app.metrics.downsample-retention-hours:720}")
    private long downsampleRetentionHours;

    public HeartbeatMonitor(NodeService nodeService,
                            AlertService alertService,
                            MetricService metricService,
                            DownsamplingService downsamplingService) {
        this.nodeService = nodeService;
        this.alertService = alertService;
        this.metricService = metricService;
        this.downsamplingService = downsamplingService;
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
     * Чистит raw-метрики старше {@code rawRetentionHours}. Запускается раз в час.
     * DELETE идёт по индексу idx_metrics_ts — быстрый сегментный delete.
     */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 60_000L)
    public void retentionRaw() {
        try {
            Instant cutoff = Instant.now().minus(Duration.ofHours(rawRetentionHours));
            int deleted = metricService.deleteOlderThan(cutoff);
            if (deleted > 0) {
                log.info("Retention raw: удалено {} метрик старше {}", deleted, cutoff);
            }
        } catch (Exception e) {
            log.warn("Raw retention failed: {}", e.getMessage());
        }
    }

    /**
     * Аналог Thanos Compactor. Каждые 5 минут сворачивает «зрелые» raw-точки
     * в 5-минутные bucket'ы в таблице metrics_5m.
     */
    @Scheduled(fixedDelay = 300_000L, initialDelay = 60_000L)
    public void downsample() {
        try {
            downsamplingService.run();
        } catch (Exception e) {
            log.warn("Downsampling failed: {}", e.getMessage());
        }
    }

    /**
     * Retention для downsampled-таблицы — раз в сутки, по индексу idx_m5_bucket.
     */
    @Scheduled(fixedDelay = 86_400_000L, initialDelay = 120_000L)
    public void retentionDownsampled() {
        try {
            Instant cutoff = Instant.now().minus(Duration.ofHours(downsampleRetentionHours));
            int deleted = downsamplingService.deleteOlderThan(cutoff);
            if (deleted > 0) {
                log.info("Retention 5m: удалено {} bucket'ов старше {}", deleted, cutoff);
            }
        } catch (Exception e) {
            log.warn("Downsample retention failed: {}", e.getMessage());
        }
    }
}
