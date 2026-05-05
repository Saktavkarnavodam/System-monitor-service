package ru.diplom.monitoring.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Сводная статистика по метрике за временное окно")
public class MetricSummary {
    private String metricName;
    private String nodeId;
    private long count;
    private double min;
    private double max;
    private double avg;
    private double sum;
    private double p50;
    private double p95;
    private double p99;
    private double last;
    private Instant windowStart;
    private Instant windowEnd;

    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public long getCount() { return count; }
    public void setCount(long count) { this.count = count; }

    public double getMin() { return min; }
    public void setMin(double min) { this.min = min; }

    public double getMax() { return max; }
    public void setMax(double max) { this.max = max; }

    public double getAvg() { return avg; }
    public void setAvg(double avg) { this.avg = avg; }

    public double getSum() { return sum; }
    public void setSum(double sum) { this.sum = sum; }

    public double getP50() { return p50; }
    public void setP50(double p50) { this.p50 = p50; }

    public double getP95() { return p95; }
    public void setP95(double p95) { this.p95 = p95; }

    public double getP99() { return p99; }
    public void setP99(double p99) { this.p99 = p99; }

    public double getLast() { return last; }
    public void setLast(double last) { this.last = last; }

    public Instant getWindowStart() { return windowStart; }
    public void setWindowStart(Instant windowStart) { this.windowStart = windowStart; }

    public Instant getWindowEnd() { return windowEnd; }
    public void setWindowEnd(Instant windowEnd) { this.windowEnd = windowEnd; }
}
