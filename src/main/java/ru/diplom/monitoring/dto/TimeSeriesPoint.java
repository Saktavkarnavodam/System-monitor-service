package ru.diplom.monitoring.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Одна точка агрегированного временного ряда (один bucket)")
public class TimeSeriesPoint {
    @Schema(description = "Левая граница bucket'а")
    private Instant timestamp;

    @Schema(description = "Количество замеров, попавших в bucket")
    private long count;
    private double min;
    private double max;
    private double avg;
    private double last;

    public TimeSeriesPoint() {}

    public TimeSeriesPoint(Instant timestamp, long count, double min, double max, double avg, double last) {
        this.timestamp = timestamp;
        this.count = count;
        this.min = min;
        this.max = max;
        this.avg = avg;
        this.last = last;
    }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public long getCount() { return count; }
    public void setCount(long count) { this.count = count; }

    public double getMin() { return min; }
    public void setMin(double min) { this.min = min; }

    public double getMax() { return max; }
    public void setMax(double max) { this.max = max; }

    public double getAvg() { return avg; }
    public void setAvg(double avg) { this.avg = avg; }

    public double getLast() { return last; }
    public void setLast(double last) { this.last = last; }
}
