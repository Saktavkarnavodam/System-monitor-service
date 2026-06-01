package ru.diplom.monitoring.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.diplom.monitoring.model.Metric;
import ru.diplom.monitoring.model.MetricDownsample;
import ru.diplom.monitoring.repository.MetricDownsampleRepository;
import ru.diplom.monitoring.repository.MetricRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Сворачивает raw-метрики в 5-минутные bucket'ы — аналог Thanos Compactor.
 *
 * <p>Алгоритм работы:
 * <ol>
 *   <li>Берём «зрелое» окно: {@code [from = now - lookback; to = now - maturity)} —
 *       свежие точки (моложе maturity) не трогаем, чтобы дождаться запоздавших.</li>
 *   <li>Находим все пары (nodeId, name) с точками в окне.</li>
 *   <li>Для каждой пары: смотрим последний уже свёрнутый bucket в metrics_5m
 *       и обрабатываем только bucket'ы после него (чтобы не делать дублирующую работу).</li>
 *   <li>Группируем raw-точки по 5-минутным bucket'ам, считаем
 *       (count, sum, min, max, last) и сохраняем в metrics_5m.</li>
 * </ol>
 *
 * <p>Bucket'ы выровнены по 5-минутной сетке относительно UTC-эпохи —
 * аналогично тому, как это делает {@code date_trunc('5 minutes', ...)} в Postgres.
 *
 * <p>Идемпотентность: при повторном запуске на тех же данных результат
 * не меняется — записи апдейтятся через look-up по unique-индексу
 * {@code (node_id, name, bucket_start)}.
 *
 * <p>Каждая серия сворачивается в отдельной транзакции через {@link TransactionTemplate}:
 * ошибка одной серии не откатывает весь прогон.
 */
@Service
public class DownsamplingService {

    private static final Logger log = LoggerFactory.getLogger(DownsamplingService.class);

    static final long BUCKET_SECONDS = 300L; // 5 минут
    private static final long BUCKET_MS = BUCKET_SECONDS * 1_000L;

    private final MetricRepository metricRepo;
    private final MetricDownsampleRepository downRepo;
    private final TransactionTemplate txTemplate;

    @Value("${app.metrics.downsample-lookback-hours:24}")
    private long lookbackHours;

    @Value("${app.metrics.downsample-maturity-minutes:10}")
    private long maturityMinutes;

    public DownsamplingService(MetricRepository metricRepo,
                               MetricDownsampleRepository downRepo,
                               PlatformTransactionManager txManager) {
        this.metricRepo = metricRepo;
        this.downRepo = downRepo;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * Один проход downsampling'а. Возвращает количество сохранённых/обновлённых bucket'ов.
     */
    public int run() {
        Instant now = Instant.now();
        Instant from = now.minus(Duration.ofHours(lookbackHours));
        Instant to = now.minus(Duration.ofMinutes(maturityMinutes));
        if (!to.isAfter(from)) return 0;

        List<Object[]> series = txTemplate.execute(s ->
                metricRepo.findDistinctNodeAndNameWithDataIn(from, to));
        if (series == null || series.isEmpty()) return 0;

        int totalBuckets = 0;
        for (Object[] row : series) {
            String nodeId = (String) row[0];
            String name = (String) row[1];
            try {
                Integer saved = txTemplate.execute(s -> downsampleSeries(nodeId, name, from, to));
                if (saved != null) totalBuckets += saved;
            } catch (Exception e) {
                log.warn("Downsample series failed: node={} name={}: {}", nodeId, name, e.getMessage());
            }
        }
        if (totalBuckets > 0) {
            log.info("Downsampling: серий={} bucket'ов сохранено={}", series.size(), totalBuckets);
        }
        return totalBuckets;
    }

    private int downsampleSeries(String nodeId, String name, Instant from, Instant to) {
        Instant lastBucket = downRepo.findMaxBucketStart(nodeId, name);
        Instant effectiveFrom = (lastBucket != null && lastBucket.isAfter(from))
                ? lastBucket.plusSeconds(BUCKET_SECONDS)
                : from;
        Instant alignedFrom = Instant.ofEpochMilli(floor(effectiveFrom.toEpochMilli()));
        Instant alignedTo = Instant.ofEpochMilli(floor(to.toEpochMilli()));
        if (!alignedTo.isAfter(alignedFrom)) return 0;

        List<Metric> points = metricRepo.findForBucketing(nodeId, name, alignedFrom, alignedTo);
        if (points.isEmpty()) return 0;

        Map<Long, Bucket> buckets = new HashMap<>();
        String ownerId = null;
        for (Metric m : points) {
            ownerId = m.getOwnerId();
            long bucketStart = floor(m.getTimestamp().toEpochMilli());
            Bucket b = buckets.computeIfAbsent(bucketStart, k -> new Bucket());
            b.add(m.getValue());
        }
        int saved = 0;
        for (Map.Entry<Long, Bucket> e : buckets.entrySet()) {
            Instant bucketStart = Instant.ofEpochMilli(e.getKey());
            Bucket b = e.getValue();
            Optional<MetricDownsample> existing =
                    downRepo.findFirstByNodeIdAndNameAndBucketStart(nodeId, name, bucketStart);
            MetricDownsample d = existing.orElseGet(MetricDownsample::new);
            d.setNodeId(nodeId);
            d.setOwnerId(ownerId);
            d.setName(name);
            d.setBucketStart(bucketStart);
            d.setCount(b.count);
            d.setSum(b.sum);
            d.setMin(b.min);
            d.setMax(b.max);
            d.setLast(b.last);
            downRepo.save(d);
            saved++;
        }
        return saved;
    }

    public void purgeNode(String nodeId) {
        txTemplate.executeWithoutResult(s -> downRepo.deleteByNodeId(nodeId));
    }

    public int deleteOlderThan(Instant cutoff) {
        Integer n = txTemplate.execute(s -> downRepo.deleteOlderThan(cutoff));
        return n == null ? 0 : n;
    }

    private static long floor(long epochMs) {
        return (epochMs / BUCKET_MS) * BUCKET_MS;
    }

    private static final class Bucket {
        long count;
        double sum;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double last;

        void add(double v) {
            if (count == 0) {
                min = v;
                max = v;
            } else {
                if (v < min) min = v;
                if (v > max) max = v;
            }
            sum += v;
            last = v;
            count++;
        }
    }
}
