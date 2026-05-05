package ru.diplom.monitoring.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.diplom.monitoring.dto.MetricAnalysis;
import ru.diplom.monitoring.dto.MetricBatchRequest;
import ru.diplom.monitoring.dto.MetricIngestRequest;
import ru.diplom.monitoring.dto.MetricSummary;
import ru.diplom.monitoring.dto.TimeSeriesPoint;
import ru.diplom.monitoring.exception.NotFoundException;
import ru.diplom.monitoring.model.Metric;
import ru.diplom.monitoring.model.Node;
import ru.diplom.monitoring.model.User;
import ru.diplom.monitoring.security.CurrentUser;
import ru.diplom.monitoring.service.AnalysisService;
import ru.diplom.monitoring.service.MetricService;
import ru.diplom.monitoring.service.NodeService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/metrics")
@Tag(name = "Metrics", description = "Приём, хранение и аналитика метрик (только для своих узлов)")
@SecurityRequirement(name = "bearerAuth")
public class MetricController {

    private final MetricService metricService;
    private final NodeService nodeService;
    private final AnalysisService analysisService;
    private final CurrentUser currentUser;

    public MetricController(MetricService metricService,
                            NodeService nodeService,
                            AnalysisService analysisService,
                            CurrentUser currentUser) {
        this.metricService = metricService;
        this.nodeService = nodeService;
        this.analysisService = analysisService;
        this.currentUser = currentUser;
    }

    @PostMapping("/nodes/{nodeId}")
    @Operation(summary = "Отправить одну метрику от узла")
    public Metric ingest(@PathVariable String nodeId, @Valid @RequestBody MetricIngestRequest req) {
        User u = currentUser.require();
        Node node = nodeService.find(nodeId, u.getId(), u.isAdmin())
                .orElseThrow(() -> new NotFoundException("Узел не найден: " + nodeId));
        nodeService.heartbeat(nodeId, u.getId(), u.isAdmin());
        return metricService.ingest(nodeId, node.getOwnerId(), req);
    }

    @PostMapping("/batch")
    @Operation(summary = "Пакетная отправка метрик от узла")
    public Map<String, Object> ingestBatch(@Valid @RequestBody MetricBatchRequest req) {
        User u = currentUser.require();
        String nodeId = req.getNodeId();
        Node node = nodeService.find(nodeId, u.getId(), u.isAdmin())
                .orElseThrow(() -> new NotFoundException("Узел не найден: " + nodeId));
        nodeService.heartbeat(nodeId, u.getId(), u.isAdmin());
        int accepted = 0;
        for (MetricIngestRequest m : req.getMetrics()) {
            metricService.ingest(nodeId, node.getOwnerId(), m);
            accepted++;
        }
        return Map.of("accepted", accepted, "nodeId", nodeId);
    }

    @GetMapping
    @Operation(summary = "Запрос сырых значений метрик (только своих узлов)")
    public List<Metric> query(
            @RequestParam(required = false) String nodeId,
            @RequestParam(required = false) String name,
            @Parameter(description = "ISO-8601 начало окна (например 2026-04-21T10:00:00Z)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "500") int limit) {
        User u = currentUser.require();
        if (nodeId != null) {
            nodeService.find(nodeId, u.getId(), u.isAdmin())
                    .orElseThrow(() -> new NotFoundException("Узел не найден: " + nodeId));
        }
        String ownerFilter = u.isAdmin() ? null : u.getId();
        return metricService.query(ownerFilter, nodeId, name, from, to, limit);
    }

    @GetMapping("/summary")
    @Operation(summary = "Сводная статистика по метрике: min/max/avg/p50/p95/p99")
    public MetricSummary summary(
            @RequestParam(required = false) String nodeId,
            @RequestParam String name,
            @Parameter(description = "Окно в секундах (по умолчанию 300)")
            @RequestParam(defaultValue = "300") long windowSeconds) {
        User u = currentUser.require();
        if (nodeId != null) {
            nodeService.find(nodeId, u.getId(), u.isAdmin())
                    .orElseThrow(() -> new NotFoundException("Узел не найден: " + nodeId));
        }
        String ownerFilter = u.isAdmin() ? null : u.getId();
        return metricService.summarize(ownerFilter, nodeId, name, Duration.ofSeconds(windowSeconds));
    }

    @GetMapping("/names")
    @Operation(summary = "Список известных имён метрик (для своих узлов)")
    public List<String> names() {
        User u = currentUser.require();
        return metricService.knownMetricNamesFor(u.isAdmin() ? null : u.getId());
    }

    @GetMapping("/timeseries")
    @Operation(summary = "Агрегированный временной ряд по бакетам — основной запрос для отрисовки графиков",
            description = "Возвращает массив (timestamp, count, min, max, avg, last) с шагом bucketSeconds. " +
                    "Запрос использует композитный индекс (owner_id|node_id, name, timestamp), " +
                    "что обеспечивает поиск окна одним B-tree-lookup-ом и сортировку «бесплатно».")
    public List<TimeSeriesPoint> timeseries(
            @RequestParam(required = false) String nodeId,
            @RequestParam String name,
            @Parameter(description = "ISO-8601 начало окна")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "ISO-8601 конец окна")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "Размер бакета в секундах (по умолчанию 60)")
            @RequestParam(defaultValue = "60") int bucketSeconds) {
        User u = currentUser.require();
        if (nodeId != null) {
            nodeService.find(nodeId, u.getId(), u.isAdmin())
                    .orElseThrow(() -> new NotFoundException("Узел не найден: " + nodeId));
        }
        String ownerFilter = u.isAdmin() ? null : u.getId();
        return metricService.timeseries(ownerFilter, nodeId, name, from, to, bucketSeconds);
    }

    @GetMapping("/analyze")
    @Operation(
            summary = "Корреляционный анализ причин — почему метрика изменилась",
            description = "Принимает узел и имя метрики; возвращает z-score целевой метрики " +
                    "(степень аномальности относительно окна) и список других метрик этого узла, " +
                    "ранжированный по коэффициенту корреляции Пирсона. " +
                    "Под капотом: один индексный range-scan по (node_id, timestamp), " +
                    "выравнивание всех серий на одну bucket-сетку, расчёт корреляции в Java O(N×M)."
    )
    public MetricAnalysis analyze(
            @RequestParam String nodeId,
            @RequestParam String name,
            @Parameter(description = "Момент анализа (ISO-8601). По умолчанию — сейчас.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at,
            @Parameter(description = "Длина окна анализа в секундах")
            @RequestParam(defaultValue = "600") int windowSeconds,
            @Parameter(description = "Размер bucket'а для выравнивания серий")
            @RequestParam(defaultValue = "30") int bucketSeconds,
            @Parameter(description = "Сколько коррелирующих метрик вернуть")
            @RequestParam(defaultValue = "5") int topN) {

        User u = currentUser.require();
        nodeService.find(nodeId, u.getId(), u.isAdmin())
                .orElseThrow(() -> new NotFoundException("Узел не найден: " + nodeId));
        String ownerFilter = u.isAdmin() ? null : u.getId();
        return analysisService.analyze(ownerFilter, nodeId, name, at, windowSeconds, bucketSeconds, topN);
    }
}
