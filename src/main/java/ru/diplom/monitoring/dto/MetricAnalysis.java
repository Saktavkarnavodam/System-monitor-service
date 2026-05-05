package ru.diplom.monitoring.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Результат корреляционного анализа метрики.
 *
 * <p>Когда пользователь хочет понять «почему скакнул CPU?», система:
 * <ol>
 *   <li>выбирает целевую метрику (target) за окно [at - windowSeconds, at];</li>
 *   <li>считает z-score последнего значения относительно среднего по окну —
 *       это даёт оценку аномальности;</li>
 *   <li>для каждой другой метрики этого же узла считает Pearson correlation
 *       с целевой;</li>
 *   <li>сортирует по |correlation| и возвращает топ-N кандидатов на «причину».</li>
 * </ol>
 */
@Schema(description = "Результат корреляционного анализа причин изменения метрики")
public class MetricAnalysis {

    @Schema(description = "Анализируемая метрика и её состояние")
    private TargetInfo target;

    @Schema(description = "Окно анализа: левая и правая границы")
    private Instant windowFrom;
    private Instant windowTo;

    @Schema(description = "Список метрик с их корреляцией к target, отсортирован по убыванию |corr|")
    private List<CorrelatedMetric> correlations;

    @Schema(description = "Человекочитаемое резюме анализа (на русском)")
    private String summary;

    public MetricAnalysis() {}

    // -- getters/setters --

    public TargetInfo getTarget() { return target; }
    public void setTarget(TargetInfo target) { this.target = target; }

    public Instant getWindowFrom() { return windowFrom; }
    public void setWindowFrom(Instant windowFrom) { this.windowFrom = windowFrom; }

    public Instant getWindowTo() { return windowTo; }
    public void setWindowTo(Instant windowTo) { this.windowTo = windowTo; }

    public List<CorrelatedMetric> getCorrelations() { return correlations; }
    public void setCorrelations(List<CorrelatedMetric> correlations) { this.correlations = correlations; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    // ------------------------------------------------------------
    // Nested types
    // ------------------------------------------------------------

    @Schema(description = "Информация об анализируемой метрике")
    public static class TargetInfo {
        private String name;
        private double currentValue;
        @Schema(description = "Среднее значение по окну")
        private double mean;
        @Schema(description = "Стандартное отклонение по окну")
        private double stddev;
        @Schema(description = "Z-score текущего значения относительно среднего")
        private double zScore;
        @Schema(description = "Считается аномалией, если |zScore| > 2.0")
        private boolean anomaly;
        @Schema(description = "Тренд: RISING, FALLING, STABLE")
        private String trend;
        @Schema(description = "Изменение от первого до последнего значения окна (%)")
        private double changePercent;
        @Schema(description = "Сколько точек попало в окно")
        private int sampleCount;

        public TargetInfo() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public double getCurrentValue() { return currentValue; }
        public void setCurrentValue(double currentValue) { this.currentValue = currentValue; }

        public double getMean() { return mean; }
        public void setMean(double mean) { this.mean = mean; }

        public double getStddev() { return stddev; }
        public void setStddev(double stddev) { this.stddev = stddev; }

        public double getZScore() { return zScore; }
        public void setZScore(double zScore) { this.zScore = zScore; }

        public boolean isAnomaly() { return anomaly; }
        public void setAnomaly(boolean anomaly) { this.anomaly = anomaly; }

        public String getTrend() { return trend; }
        public void setTrend(String trend) { this.trend = trend; }

        public double getChangePercent() { return changePercent; }
        public void setChangePercent(double changePercent) { this.changePercent = changePercent; }

        public int getSampleCount() { return sampleCount; }
        public void setSampleCount(int sampleCount) { this.sampleCount = sampleCount; }
    }

    @Schema(description = "Метрика, коррелирующая с целевой")
    public static class CorrelatedMetric {
        private String name;
        @Schema(description = "Pearson correlation coefficient: [-1, 1]")
        private double correlation;
        @Schema(description = "Сила связи: STRONG (>=0.7), MODERATE (>=0.4), WEAK (<0.4)")
        private String strength;
        @Schema(description = "Знак корреляции: POSITIVE/NEGATIVE")
        private String direction;
        private double currentValue;
        private double mean;
        private double changePercent;

        public CorrelatedMetric() {}

        public CorrelatedMetric(String name, double correlation, double currentValue,
                                double mean, double changePercent) {
            this.name = name;
            this.correlation = correlation;
            this.currentValue = currentValue;
            this.mean = mean;
            this.changePercent = changePercent;
            double abs = Math.abs(correlation);
            this.strength = abs >= 0.7 ? "STRONG" : abs >= 0.4 ? "MODERATE" : "WEAK";
            this.direction = correlation >= 0 ? "POSITIVE" : "NEGATIVE";
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public double getCorrelation() { return correlation; }
        public void setCorrelation(double correlation) { this.correlation = correlation; }

        public String getStrength() { return strength; }
        public void setStrength(String strength) { this.strength = strength; }

        public String getDirection() { return direction; }
        public void setDirection(String direction) { this.direction = direction; }

        public double getCurrentValue() { return currentValue; }
        public void setCurrentValue(double currentValue) { this.currentValue = currentValue; }

        public double getMean() { return mean; }
        public void setMean(double mean) { this.mean = mean; }

        public double getChangePercent() { return changePercent; }
        public void setChangePercent(double changePercent) { this.changePercent = changePercent; }
    }
}
