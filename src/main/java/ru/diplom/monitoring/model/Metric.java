package ru.diplom.monitoring.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import ru.diplom.monitoring.persistence.MapToJsonConverter;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Time-series точка наблюдения. Это самая «горячая» таблица — содержит большую
 * часть данных, и именно её индексы определяют скорость отрисовки графиков.
 *
 * <p>Используются три композитных индекса:
 * <ul>
 *   <li><b>idx_metrics_owner_name_ts</b> — основной индекс для запросов на дашборд:
 *       <i>«покажи график метрики X пользователя Y за последний час»</i>.
 *       Композитный, потому что WHERE содержит owner_id = ? AND name = ? AND timestamp BETWEEN ? AND ?.
 *       B-tree разрешает все три условия одним lookup-ом, плюс ORDER BY timestamp
 *       получается «бесплатно» — индекс уже отсортирован.</li>
 *   <li><b>idx_metrics_node_name_ts</b> — то же, но когда фильтр по конкретному узлу
 *       (latestPerMetric, узловой график).</li>
 *   <li><b>idx_metrics_ts</b> — для retention-задачи (удаление старых метрик
 *       по timestamp &lt; cutoff) и общих time-window сканов.</li>
 * </ul>
 *
 * Строгое следование порядку колонок в индексе важно: эквивалентный фильтр
 * сначала «срезает» по самому селективному столбцу (owner_id), затем по name,
 * и только потом range по timestamp.
 */
@Entity
@Table(name = "metrics", indexes = {
        @Index(name = "idx_metrics_owner_name_ts", columnList = "owner_id, name, timestamp"),
        @Index(name = "idx_metrics_node_name_ts", columnList = "node_id, name, timestamp"),
        @Index(name = "idx_metrics_ts", columnList = "timestamp")
})
public class Metric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "node_id", nullable = false, length = 36)
    private String nodeId;

    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "metric_value", nullable = false)
    private double value;

    @Column(nullable = false)
    private Instant timestamp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MetricType type = MetricType.GAUGE;

    @Column(length = 32)
    private String unit;

    @Column(name = "tags_json", columnDefinition = "TEXT")
    @Convert(converter = MapToJsonConverter.class)
    private Map<String, String> tags = new HashMap<>();

    public Metric() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public double getValue() { return value; }
    public void setValue(double value) { this.value = value; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public MetricType getType() { return type; }
    public void setType(MetricType type) { this.type = type; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags == null ? new HashMap<>() : tags; }
}
