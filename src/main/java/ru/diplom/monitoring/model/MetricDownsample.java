package ru.diplom.monitoring.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 5-минутная аггрегированная точка («downsampled» в терминологии Thanos).
 *
 * <p>Идея заимствована из <b>Thanos Compactor</b>: вместо того, чтобы хранить
 * raw-точки навсегда (или удалять их retention'ом), мы периодически
 * сворачиваем их в bucket'ы по 5 минут — оставляя count/min/max/avg/last.
 * Графики за длинные периоды (день, неделя) рисуются по этим bucket'ам:
 * объём данных снижается в 30×–300× (зависит от частоты раздачи), а
 * визуально график за длинное окно неотличим от сырого.
 *
 * <p>Стратегия двухуровневая:
 * <ul>
 *   <li><b>raw</b> ({@link Metric}) — все точки, retention короче (по умолч. 24 ч);</li>
 *   <li><b>5m</b> ({@code metrics_5m}) — сжатые bucket'ы, retention дольше (30 дней).</li>
 * </ul>
 *
 * Уникальный индекс {@code (node_id, name, bucket_start)} даёт upsert-семантику
 * через look-up: при повторной аггрегации того же bucket'а мы обновим строку,
 * а не вставим дубль.
 */
@Entity
@Table(name = "metrics_5m",
        indexes = {
                @Index(name = "idx_m5_owner_name_bucket", columnList = "owner_id, name, bucket_start"),
                @Index(name = "idx_m5_node_name_bucket", columnList = "node_id, name, bucket_start"),
                @Index(name = "idx_m5_bucket", columnList = "bucket_start")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_m5_node_name_bucket",
                        columnNames = {"node_id", "name", "bucket_start"})
        })
public class MetricDownsample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "node_id", nullable = false, length = 36)
    private String nodeId;

    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "bucket_start", nullable = false)
    private Instant bucketStart;

    @Column(name = "sample_count", nullable = false)
    private long count;

    @Column(name = "sum_value", nullable = false)
    private double sum;

    @Column(name = "min_value", nullable = false)
    private double min;

    @Column(name = "max_value", nullable = false)
    private double max;

    @Column(name = "last_value", nullable = false)
    private double last;

    public MetricDownsample() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Instant getBucketStart() { return bucketStart; }
    public void setBucketStart(Instant bucketStart) { this.bucketStart = bucketStart; }

    public long getCount() { return count; }
    public void setCount(long count) { this.count = count; }

    public double getSum() { return sum; }
    public void setSum(double sum) { this.sum = sum; }

    public double getMin() { return min; }
    public void setMin(double min) { this.min = min; }

    public double getMax() { return max; }
    public void setMax(double max) { this.max = max; }

    public double getLast() { return last; }
    public void setLast(double last) { this.last = last; }

    public double getAvg() {
        return count == 0 ? 0.0 : sum / count;
    }
}
