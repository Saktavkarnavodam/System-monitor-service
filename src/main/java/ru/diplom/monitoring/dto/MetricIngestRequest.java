package ru.diplom.monitoring.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import ru.diplom.monitoring.model.MetricType;

import java.time.Instant;
import java.util.Map;

@Schema(description = "Единичная метрика для отправки на сервер мониторинга")
public class MetricIngestRequest {

    @NotBlank
    @Schema(example = "cpu.usage", description = "Имя метрики")
    private String name;

    @Schema(example = "73.5", description = "Значение метрики")
    private double value;

    @Schema(example = "GAUGE")
    private MetricType type;

    @Schema(example = "percent")
    private String unit;

    @Schema(description = "Временная метка (ISO-8601). Если не указана — используется серверное время")
    private Instant timestamp;

    @Schema(description = "Произвольные теги метрики")
    private Map<String, String> tags;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public double getValue() { return value; }
    public void setValue(double value) { this.value = value; }

    public MetricType getType() { return type; }
    public void setType(MetricType type) { this.type = type; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
