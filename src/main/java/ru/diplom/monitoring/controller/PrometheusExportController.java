package ru.diplom.monitoring.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.diplom.monitoring.model.Metric;
import ru.diplom.monitoring.model.Node;
import ru.diplom.monitoring.model.User;
import ru.diplom.monitoring.security.CurrentUser;
import ru.diplom.monitoring.service.MetricService;
import ru.diplom.monitoring.service.NodeService;

import java.util.List;
import java.util.Map;

/**
 * Раздача пользовательских метрик в Prometheus text-формате.
 *
 * <p>Это «best practice» из мира мониторинга: <a
 * href="https://prometheus.io/docs/instrumenting/exposition_formats/">exposition formats</a>
 * стали стандартом де-факто — формат понимают Prometheus, Grafana, Datadog Agent, VictoriaMetrics,
 * VMagent, Telegraf, и все другие инструменты, читающие "/metrics".
 *
 * <p>Идея заимствована из <a href="https://github.com/prometheus/node_exporter">node_exporter</a>:
 * один HTTP-эндпоинт отдаёт все известные метрики в виде строк
 * {@code metric_name{label="value"} numeric_value [timestamp_ms]}.
 *
 * <p>Что мы экспортируем:
 * <ul>
 *   <li>Последние значения каждой пользовательской метрики (по всем его узлам)</li>
 *   <li>Лейблы: {@code node="…"} (имя узла), {@code node_id="…"} (UUID),
 *       плюс собственные tags метрики</li>
 *   <li>Имена приводим к Prometheus-конвенции: dots → underscores
 *       ({@code cpu.usage} → {@code cpu_usage}).</li>
 * </ul>
 *
 * <p>Авторизация — тот же Bearer JWT, что в остальных API.
 * В Prometheus задаётся через {@code authorization.credentials} в scrape-job:
 * <pre>{@code
 * scrape_configs:
 *   - job_name: distributed-monitoring
 *     static_configs:
 *       - targets: ['my-server:8080']
 *     metrics_path: /api/v1/metrics/prometheus
 *     authorization:
 *       type: Bearer
 *       credentials: <JWT-токен>
 * }</pre>
 */
@RestController
@RequestMapping("/api/v1/metrics")
@Tag(name = "Metrics", description = "Приём, хранение и аналитика метрик (только для своих узлов)")
@SecurityRequirement(name = "bearerAuth")
public class PrometheusExportController {

    private final MetricService metricService;
    private final NodeService nodeService;
    private final CurrentUser currentUser;

    public PrometheusExportController(MetricService metricService,
                                      NodeService nodeService,
                                      CurrentUser currentUser) {
        this.metricService = metricService;
        this.nodeService = nodeService;
        this.currentUser = currentUser;
    }

    @GetMapping(value = "/prometheus", produces = "text/plain;version=0.0.4;charset=utf-8")
    @Operation(summary = "Экспорт последних значений метрик в Prometheus text-формате",
            description = "Совместимо с Prometheus, Grafana, VictoriaMetrics и любым инструментом, " +
                    "понимающим /metrics. Возвращает по последней точке на каждое сочетание " +
                    "(node, metric). Имена нормализуются: точки заменяются на подчёркивания.")
    public ResponseEntity<String> exportPrometheus() {
        User u = currentUser.require();
        List<Node> nodes = nodeService.list(u.getId(), u.isAdmin());

        StringBuilder sb = new StringBuilder(4096);
        // Группируем по имени метрики, чтобы HELP/TYPE-комментарии шли один раз
        java.util.Map<String, java.util.List<MetricLine>> grouped = new java.util.LinkedHashMap<>();
        for (Node node : nodes) {
            List<Metric> latest = metricService.latestPerMetric(node.getId());
            for (Metric m : latest) {
                String promName = toPromName(m.getName());
                grouped.computeIfAbsent(promName, k -> new java.util.ArrayList<>())
                        .add(new MetricLine(node, m));
            }
        }
        for (var entry : grouped.entrySet()) {
            String name = entry.getKey();
            List<MetricLine> lines = entry.getValue();
            String unit = lines.get(0).metric.getUnit();
            sb.append("# HELP ").append(name).append(' ')
                    .append(unit == null || unit.isBlank() ? "exported user metric" : "unit: " + unit)
                    .append('\n');
            sb.append("# TYPE ").append(name).append(' ')
                    .append(promType(lines.get(0).metric)).append('\n');
            for (MetricLine line : lines) {
                appendMetricLine(sb, name, line);
            }
        }
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/plain;version=0.0.4;charset=utf-8"))
                .body(sb.toString());
    }

    private void appendMetricLine(StringBuilder sb, String name, MetricLine line) {
        sb.append(name).append('{');
        sb.append("node=\"").append(escapeLabel(line.node.getName())).append('"');
        sb.append(",node_id=\"").append(line.node.getId()).append('"');
        if (line.metric.getTags() != null) {
            for (Map.Entry<String, String> tag : line.metric.getTags().entrySet()) {
                String key = sanitizeLabelName(tag.getKey());
                if (key.isEmpty()) continue;
                sb.append(',').append(key).append("=\"")
                        .append(escapeLabel(tag.getValue() == null ? "" : tag.getValue()))
                        .append('"');
            }
        }
        sb.append("} ").append(formatValue(line.metric.getValue()));
        // timestamp_ms — Prometheus поддерживает, но это опционально.
        // Передаём, чтобы старые точки не выглядели как «свежие».
        sb.append(' ').append(line.metric.getTimestamp().toEpochMilli()).append('\n');
    }

    private String promType(Metric m) {
        if (m.getType() == null) return "gauge";
        return switch (m.getType()) {
            case COUNTER -> "counter";
            case GAUGE -> "gauge";
            case HISTOGRAM -> "histogram";
            case TIMER -> "summary";   // TIMER ~ summary в Prometheus-наименовании
        };
    }

    /**
     * Конвертация имени метрики в Prometheus-совместимое:
     * - точки/дефисы → подчёркивания
     * - оставляем только [a-zA-Z0-9_:]
     * - если первый символ цифра — добавим '_' префикс
     */
    static String toPromName(String name) {
        if (name == null || name.isEmpty()) return "unknown_metric";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == ':') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        if (sb.length() > 0 && Character.isDigit(sb.charAt(0))) {
            sb.insert(0, '_');
        }
        return sb.toString();
    }

    static String sanitizeLabelName(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'
                    || (i > 0 && c >= '0' && c <= '9')) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    static String escapeLabel(String v) {
        if (v == null) return "";
        // Согласно exposition format: экранируем \, ", \n.
        StringBuilder sb = new StringBuilder(v.length() + 8);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static String formatValue(double v) {
        if (Double.isNaN(v)) return "NaN";
        if (v == Double.POSITIVE_INFINITY) return "+Inf";
        if (v == Double.NEGATIVE_INFINITY) return "-Inf";
        // если целое — печатаем без точки, иначе обычным double
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    private record MetricLine(Node node, Metric metric) {}
}
