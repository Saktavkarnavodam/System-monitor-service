package ru.diplom.monitoring.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.diplom.monitoring.dto.MetricIngestRequest;
import ru.diplom.monitoring.dto.MetricSummary;
import ru.diplom.monitoring.dto.TimeSeriesPoint;
import ru.diplom.monitoring.model.Metric;
import ru.diplom.monitoring.model.MetricType;
import ru.diplom.monitoring.repository.MetricRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Бизнес-логика работы с метриками. Все операции делегируются в БД через
 * {@link MetricRepository} — это даёт нам индексы (см. javadoc к {@link Metric}),
 * транзакционность и возможность retention.
 */
@Service
@Transactional
public class MetricService {

    private final MetricRepository repo;
    /** Счётчик принятых через ingest точек (живёт в рамках процесса; для статистики). */
    private final AtomicLong totalIngested = new AtomicLong(0);

    public MetricService(MetricRepository repo) {
        this.repo = repo;
    }

    public Metric ingest(String nodeId, String ownerId, MetricIngestRequest req) {
        Metric m = new Metric();
        m.setNodeId(nodeId);
        m.setOwnerId(ownerId);
        m.setName(req.getName());
        m.setValue(req.getValue());
        m.setType(req.getType() == null ? MetricType.GAUGE : req.getType());
        m.setUnit(req.getUnit());
        m.setTimestamp(req.getTimestamp() == null ? Instant.now() : req.getTimestamp());
        m.setTags(req.getTags());
        Metric saved = repo.save(m);
        totalIngested.incrementAndGet();
        return saved;
    }

    /**
     * Запрос сырых метрик. Все параметры опциональны, фильтр строится в SQL,
     * чтобы попасть в композитные индексы:
     * (owner_id, name, timestamp) или (node_id, name, timestamp).
     *
     * @param ownerId    null для администратора (без фильтра по владельцу)
     */
    @Transactional(readOnly = true)
    public List<Metric> query(String ownerId, String nodeId, String metricName,
                              Instant from, Instant to, int limit) {
        Pageable pageable = (limit > 0)
                ? PageRequest.of(0, limit)
                : PageRequest.of(0, 10_000); // safety cap
        return repo.search(ownerId, nodeId, metricName, from, to, pageable);
    }

    /**
     * Сводная статистика за окно (min/max/avg/p50/p95/p99/last).
     * Для портативности (H2/Postgres) и точности перцентилей агрегацию
     * считаем в Java над выборкой по индексу.
     */
    @Transactional(readOnly = true)
    public MetricSummary summarize(String ownerId, String nodeId, String metricName, Duration window) {
        Instant to = Instant.now();
        Instant from = to.minus(window);
        List<Metric> metrics = query(ownerId, nodeId, metricName, from, to, 0);

        MetricSummary s = new MetricSummary();
        s.setNodeId(nodeId);
        s.setMetricName(metricName);
        s.setWindowStart(from);
        s.setWindowEnd(to);
        s.setCount(metrics.size());

        if (metrics.isEmpty()) return s;

        double[] values = metrics.stream().mapToDouble(Metric::getValue).toArray();
        double sum = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            sum += v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        s.setSum(sum);
        s.setMin(min);
        s.setMax(max);
        s.setAvg(sum / values.length);
        // запрос отсортирован DESC по timestamp -> первый элемент это последний по времени
        s.setLast(metrics.get(0).getValue());

        double[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        s.setP50(percentile(sorted, 50));
        s.setP95(percentile(sorted, 95));
        s.setP99(percentile(sorted, 99));
        return s;
    }

    /**
     * Time-series агрегация по бакетам для графиков. Возвращает массив точек
     * (timestamp, count, min, max, avg, last) — каждый bucket содержит
     * статистику за {@code bucketSeconds} секунд.
     *
     * <p>Запрос в БД делается один раз (по композитному индексу), бакетинг
     * выполняется в Java одним проходом — это и портативно (H2/Postgres
     * по-разному эмулируют {@code date_trunc}), и быстро: O(N) при отсортированном
     * по индексу выводе.
     */
    @Transactional(readOnly = true)
    public List<TimeSeriesPoint> timeseries(String ownerId, String nodeId, String metricName,
                                            Instant from, Instant to, int bucketSeconds) {
        if (bucketSeconds <= 0) bucketSeconds = 60;
        if (from == null) from = Instant.now().minus(Duration.ofHours(1));
        if (to == null) to = Instant.now();

        // забираем все точки окна; репозиторий вернёт DESC по timestamp
        List<Metric> rows = repo.search(ownerId, nodeId, metricName, from, to,
                PageRequest.of(0, 100_000));
        if (rows.isEmpty()) return Collections.emptyList();

        // отсортируем ASC, чтобы single-pass agg был корректен
        rows.sort(Comparator.comparing(Metric::getTimestamp));

        long bucketMs = bucketSeconds * 1000L;
        long fromMs = from.toEpochMilli();
        // нормализуем начало bucket'а до bucketMs (как делает date_trunc, но без БД)
        long alignedFromMs = (fromMs / bucketMs) * bucketMs;

        List<TimeSeriesPoint> out = new ArrayList<>();
        long currentBucketStart = -1;
        long count = 0;
        double sum = 0, min = 0, max = 0, last = 0;

        for (Metric m : rows) {
            long ts = m.getTimestamp().toEpochMilli();
            long bucketStart = alignedFromMs + ((ts - alignedFromMs) / bucketMs) * bucketMs;
            if (currentBucketStart == -1) {
                currentBucketStart = bucketStart;
                count = 0; sum = 0; min = m.getValue(); max = m.getValue(); last = m.getValue();
            }
            if (bucketStart != currentBucketStart) {
                // финализируем предыдущий
                out.add(new TimeSeriesPoint(Instant.ofEpochMilli(currentBucketStart),
                        count, min, max, count == 0 ? 0 : sum / count, last));
                currentBucketStart = bucketStart;
                count = 0; sum = 0; min = m.getValue(); max = m.getValue(); last = m.getValue();
            }
            double v = m.getValue();
            sum += v;
            if (v < min) min = v;
            if (v > max) max = v;
            last = v; // т.к. идём по возрастанию timestamp
            count++;
        }
        // финальный bucket
        if (currentBucketStart != -1) {
            out.add(new TimeSeriesPoint(Instant.ofEpochMilli(currentBucketStart),
                    count, min, max, count == 0 ? 0 : sum / count, last));
        }
        return out;
    }

    private double percentile(double[] sorted, double p) {
        if (sorted.length == 0) return 0;
        double rank = (p / 100.0) * (sorted.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) return sorted[lo];
        double weight = rank - lo;
        return sorted[lo] * (1 - weight) + sorted[hi] * weight;
    }

    @Transactional(readOnly = true)
    public List<String> knownMetricNamesFor(String ownerId) {
        if (ownerId == null) return repo.findDistinctNames();
        return repo.findDistinctNamesByOwner(ownerId);
    }

    /**
     * Последние значения каждой метрики на конкретном узле.
     * Реализовано через {@code MAX(id) GROUP BY name} в репозитории
     * (id auto-increment ⇒ max(id) = последняя запись).
     */
    @Transactional(readOnly = true)
    public List<Metric> latestPerMetric(String nodeId) {
        return repo.findLatestPerNameForNode(nodeId);
    }

    public long totalIngested() {
        return totalIngested.get();
    }

    @Transactional(readOnly = true)
    public long storedCount() {
        return repo.count();
    }

    @Transactional(readOnly = true)
    public long storedCountFor(String ownerId) {
        if (ownerId == null) return repo.count();
        return repo.countByOwnerId(ownerId);
    }

    /** Полное удаление метрик узла (вызывается при deregister). */
    public void purgeNode(String nodeId) {
        repo.deleteByNodeId(nodeId);
    }

    /**
     * Retention: удалить точки старше {@code cutoff}. Использует
     * индекс {@code idx_metrics_ts}.
     */
    public int deleteOlderThan(Instant cutoff) {
        return repo.deleteOlderThan(cutoff);
    }
}
