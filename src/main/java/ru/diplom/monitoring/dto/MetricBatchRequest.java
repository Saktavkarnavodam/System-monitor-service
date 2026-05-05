package ru.diplom.monitoring.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@Schema(description = "Пакетная отправка метрик от одного узла")
public class MetricBatchRequest {

    @Schema(description = "Идентификатор узла, от которого отправлены метрики")
    private String nodeId;

    @Valid
    @NotEmpty
    private List<MetricIngestRequest> metrics;

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public List<MetricIngestRequest> getMetrics() { return metrics; }
    public void setMetrics(List<MetricIngestRequest> metrics) { this.metrics = metrics; }
}
