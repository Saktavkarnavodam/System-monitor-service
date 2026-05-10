package ru.diplom.monitoring.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.diplom.monitoring.dto.MetricAnalysis;
import ru.diplom.monitoring.dto.MetricAnalysis.CorrelatedMetric;
import ru.diplom.monitoring.dto.MetricAnalysis.TargetInfo;
import ru.diplom.monitoring.model.Metric;
import ru.diplom.monitoring.repository.MetricRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Корреляционный анализ метрик — «почему оно скакнуло».
 *
 * <p>Базовая идея: когда пользователь видит всплеск метрики X, ему хочется
 * понять, что с ней произошло одновременно. Мы берём все остальные метрики
 * этого узла за то же временное окно, выравниваем их по общим bucket'ам
 * (одинаковая частота дискретизации) и считаем для каждой пары
 * <i>Pearson correlation coefficient</i>:
 *
 * <pre>
 *   r = Σ((xi − x̄)(yi − ȳ)) / √(Σ(xi − x̄)² · Σ(yi − ȳ)²)
 * </pre>
 *
 * <p>r ∈ [−1, 1]:
 * <ul>
 *   <li>r = 1 — метрики двигались синхронно вверх/вниз;</li>
 *   <li>r = −1 — антикоррелированы (одна растёт когда другая падает);</li>
 *   <li>r ≈ 0 — независимы.</li>
 * </ul>
 *
 * <p>Плюс z-score для самой целевой метрики:
 *
 * <pre>z = (currentValue − mean) / stddev</pre>
 *
 * При |z| &gt; 2 — это статистическая аномалия (правило «два сигма»).
 */
@Service
@Transactional(readOnly = true)
public class AnalysisService {

    /** Порог |z-score|, при котором считаем значение аномалией. */
    public static final double ANOMALY_Z_THRESHOLD = 2.0;

    /** Порог |corr|, при котором считаем связь сильной. */
    public static final double STRONG_CORR = 0.7;

    /** Порог |corr|, при котором считаем связь умеренной. */
    public static final double MODERATE_CORR = 0.4;

    /** Минимум совпадающих bucket'ов для расчёта корреляции — иначе шум. */
    private static final int MIN_SAMPLES = 3;

    private final MetricRepository repo;

    public AnalysisService(MetricRepository repo) {
        this.repo = repo;
    }

    /**
     * Главная точка входа: проанализировать метрику {@code metricName} на узле
     * {@code nodeId} в окне [at − windowSeconds, at].
     *
     * @param ownerId       null для администратора (без фильтра по владельцу)
     * @param nodeId        обязательный — анализ всегда привязан к узлу
     * @param metricName    целевая метрика, например {@code cpu.usage}
     * @param at            момент времени, для которого делаем анализ;
     *                      null = сейчас
     * @param windowSeconds длина окна анализа в секундах
     * @param bucketSeconds длина bucket'а: метрики выравниваются на одну сетку
     * @param topN          сколько коррелирующих метрик вернуть
     */
    public MetricAnalysis analyze(String ownerId, String nodeId, String metricName,
                                  Instant at, int windowSeconds, int bucketSeconds, int topN) {
        if (windowSeconds <= 0) windowSeconds = 600;          // 10 минут по умолчанию
        if (bucketSeconds <= 0) bucketSeconds = 30;
        if (topN <= 0) topN = 5;
        if (at == null) at = Instant.now();

        Instant from = at.minus(Duration.ofSeconds(windowSeconds));
        Instant to = at;

        // 1) Забираем целевую метрику
        List<Metric> targetRows = repo.search(ownerId, nodeId, metricName, from, to,
                PageRequest.of(0, 100_000));

        MetricAnalysis result = new MetricAnalysis();
        result.setWindowFrom(from);
        result.setWindowTo(to);

        TargetInfo target = buildTargetInfo(metricName, targetRows);
        result.setTarget(target);

        if (target.getSampleCount() < MIN_SAMPLES) {
            result.setCorrelations(new ArrayList<>());
            result.setSummary("Недостаточно данных для анализа: всего "
                    + target.getSampleCount() + " точек метрики «" + metricName
                    + "» в окне " + windowSeconds + " сек. Нужно минимум " + MIN_SAMPLES + ".");
            return result;
        }

        // 2) Забираем все остальные метрики узла за то же окно
        List<Metric> allRows = repo.search(ownerId, nodeId, null, from, to,
                PageRequest.of(0, 500_000));

        // Раскладываем по имени метрики
        Map<String, List<Metric>> byName = allRows.stream()
                .filter(m -> !metricName.equals(m.getName()))
                .collect(Collectors.groupingBy(Metric::getName));

        // 3) Выравниваем target на bucket-сетку
        double[] targetSeries = bucketize(targetRows, from, bucketSeconds, windowSeconds);

        // 4) Для каждой другой метрики — считаем корреляцию с target на тех же bucket'ах
        List<CorrelatedMetric> corrs = new ArrayList<>();
        for (Map.Entry<String, List<Metric>> e : byName.entrySet()) {
            double[] otherSeries = bucketize(e.getValue(), from, bucketSeconds, windowSeconds);

            double r = pearson(targetSeries, otherSeries);
            if (Double.isNaN(r)) continue;     // одна из серий — константа

            // Контекст для UI: последнее значение метрики по времени, среднее и
            // относительное изменение начало→конец окна.
            double[] vals = e.getValue().stream().mapToDouble(Metric::getValue).toArray();
            double currentVal = e.getValue().stream()
                    .max(Comparator.comparing(Metric::getTimestamp))
                    .map(Metric::getValue).orElse(vals[vals.length - 1]);
            double mean = mean(vals);
            double changePct = changePercent(e.getValue());

            corrs.add(new CorrelatedMetric(e.getKey(), r, currentVal, mean, changePct));
        }

        // 5) Сортируем по убыванию |corr|, берём top-N
        corrs.sort((a, b) -> Double.compare(Math.abs(b.getCorrelation()),
                                            Math.abs(a.getCorrelation())));
        if (corrs.size() > topN) corrs = corrs.subList(0, topN);

        result.setCorrelations(corrs);
        result.setSummary(buildSummary(target, corrs, windowSeconds));
        return result;
    }

    // =================================================================
    // helpers
    // =================================================================

    private TargetInfo buildTargetInfo(String name, List<Metric> rows) {
        TargetInfo t = new TargetInfo();
        t.setName(name);
        t.setSampleCount(rows.size());

        if (rows.isEmpty()) return t;

        double[] vals = rows.stream().mapToDouble(Metric::getValue).toArray();
        double mean = mean(vals);
        double stddev = stddev(vals, mean);

        // search() возвращает DESC -> [0] = самое свежее
        Metric latest = rows.stream().max(Comparator.comparing(Metric::getTimestamp))
                .orElse(rows.get(0));
        Metric oldest = rows.stream().min(Comparator.comparing(Metric::getTimestamp))
                .orElse(rows.get(rows.size() - 1));

        t.setCurrentValue(latest.getValue());
        t.setMean(mean);
        t.setStddev(stddev);
        double z = stddev > 0 ? (latest.getValue() - mean) / stddev : 0.0;
        t.setZScore(round(z, 3));
        t.setAnomaly(Math.abs(z) > ANOMALY_Z_THRESHOLD);

        // Тренд по сравнению старого и свежего значения
        double oldVal = oldest.getValue();
        double newVal = latest.getValue();
        double diffPct = oldVal == 0
                ? (newVal == 0 ? 0 : 100)
                : (newVal - oldVal) / Math.abs(oldVal) * 100;
        t.setChangePercent(round(diffPct, 2));
        t.setTrend(Math.abs(diffPct) < 5 ? "STABLE" : (diffPct > 0 ? "RISING" : "FALLING"));

        return t;
    }

    /**
     * Раскладывает список метрик по фиксированной bucket-сетке окна.
     * Возвращает массив длиной {@code numBuckets}: в каждом bucket'е —
     * среднее значение попавших в него точек. Пробелы между точками
     * заполняются forward-fill'ом (последним известным значением);
     * пустые bucket'ы в самом начале окна — backward-fill'ом первого
     * известного значения. Это даёт корректный ввод для коэффициента
     * Пирсона: серии одной длины без NaN.
     */
    static double[] bucketize(List<Metric> rows, Instant windowFrom,
                              int bucketSeconds, int windowSeconds) {
        int numBuckets = Math.max(1, windowSeconds / bucketSeconds);
        double[] sums = new double[numBuckets];
        int[] counts = new int[numBuckets];
        long fromMs = windowFrom.toEpochMilli();
        long bucketMs = bucketSeconds * 1000L;

        for (Metric m : rows) {
            long ts = m.getTimestamp().toEpochMilli();
            long offset = ts - fromMs;
            if (offset < 0) continue;
            int idx = (int) (offset / bucketMs);
            if (idx >= numBuckets) idx = numBuckets - 1;
            sums[idx] += m.getValue();
            counts[idx]++;
        }

        // Если в окне вообще нет точек — возвращаем нули.
        int firstKnownIdx = -1;
        for (int i = 0; i < numBuckets; i++) {
            if (counts[i] > 0) { firstKnownIdx = i; break; }
        }
        double[] series = new double[numBuckets];
        if (firstKnownIdx < 0) return series;

        // Backward-fill: bucket'ы до первой известной точки = первая известная.
        double firstAvg = sums[firstKnownIdx] / counts[firstKnownIdx];
        for (int i = 0; i < firstKnownIdx; i++) series[i] = firstAvg;

        // Forward-fill: пробелы после первой точки = последнее известное среднее.
        double lastKnown = firstAvg;
        for (int i = firstKnownIdx; i < numBuckets; i++) {
            if (counts[i] > 0) {
                series[i] = sums[i] / counts[i];
                lastKnown = series[i];
            } else {
                series[i] = lastKnown;
            }
        }
        return series;
    }

    /**
     * Pearson correlation coefficient. Возвращает NaN, если одна из серий —
     * константа (стандартное отклонение = 0): корреляция в этом случае
     * математически не определена.
     */
    static double pearson(double[] x, double[] y) {
        int n = Math.min(x.length, y.length);
        if (n < MIN_SAMPLES) return Double.NaN;

        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) { mx += x[i]; my += y[i]; }
        mx /= n; my /= n;

        double num = 0, dx = 0, dy = 0;
        for (int i = 0; i < n; i++) {
            double a = x[i] - mx;
            double b = y[i] - my;
            num += a * b;
            dx  += a * a;
            dy  += b * b;
        }
        double denom = Math.sqrt(dx * dy);
        if (denom == 0) return Double.NaN;     // одна из серий — константа
        return round(num / denom, 4);
    }

    static double mean(double[] vals) {
        if (vals.length == 0) return 0;
        double s = 0;
        for (double v : vals) s += v;
        return s / vals.length;
    }

    static double stddev(double[] vals, double mean) {
        if (vals.length == 0) return 0;
        double s = 0;
        for (double v : vals) {
            double d = v - mean;
            s += d * d;
        }
        return Math.sqrt(s / vals.length);
    }

    private static double changePercent(List<Metric> rows) {
        if (rows.isEmpty()) return 0;
        Metric oldest = rows.stream().min(Comparator.comparing(Metric::getTimestamp))
                .orElse(rows.get(0));
        Metric latest = rows.stream().max(Comparator.comparing(Metric::getTimestamp))
                .orElse(rows.get(rows.size() - 1));
        double oldVal = oldest.getValue();
        double newVal = latest.getValue();
        if (oldVal == 0) return newVal == 0 ? 0 : 100;
        return round((newVal - oldVal) / Math.abs(oldVal) * 100, 2);
    }

    private static double round(double v, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(v * scale) / scale;
    }

    /**
     * Собирает короткий русскоязычный текст-резюме анализа. Этот текст —
     * самая «продаваемая» часть фичи: пользователь сразу понимает причину,
     * без необходимости интерпретировать корреляции вручную.
     */
    private String buildSummary(TargetInfo t, List<CorrelatedMetric> corrs, int windowSeconds) {
        StringBuilder sb = new StringBuilder();

        // 1) Статус целевой метрики
        if (t.isAnomaly()) {
            sb.append(String.format(Locale.ROOT,
                    "Зафиксирована аномалия метрики «%s»: текущее значение %.2f отклоняется на %.1fσ от среднего (%.2f). ",
                    t.getName(), t.getCurrentValue(), Math.abs(t.getZScore()), t.getMean()));
        } else {
            sb.append(String.format(Locale.ROOT,
                    "Метрика «%s» в пределах нормы (z=%.2f, среднее %.2f). ",
                    t.getName(), t.getZScore(), t.getMean()));
        }

        // 2) Тренд
        if ("RISING".equals(t.getTrend())) {
            sb.append(String.format(Locale.ROOT, "За окно (%d сек) выросла на %.1f%%. ",
                    windowSeconds, t.getChangePercent()));
        } else if ("FALLING".equals(t.getTrend())) {
            sb.append(String.format(Locale.ROOT, "За окно (%d сек) упала на %.1f%%. ",
                    windowSeconds, Math.abs(t.getChangePercent())));
        }

        // 3) Топ коррелирующих метрик — кандидаты в «причины»
        List<CorrelatedMetric> strong = corrs.stream()
                .filter(c -> Math.abs(c.getCorrelation()) >= MODERATE_CORR)
                .collect(Collectors.toList());

        if (strong.isEmpty()) {
            sb.append("Других метрик с заметной корреляцией не найдено — ")
              .append("изменение, вероятно, не связано с другими отслеживаемыми параметрами этого узла.");
            return sb.toString();
        }

        sb.append("Вероятные сопутствующие факторы: ");
        for (int i = 0; i < strong.size(); i++) {
            CorrelatedMetric c = strong.get(i);
            String dir = c.getCorrelation() >= 0 ? "одновременный рост" : "обратное движение";
            sb.append(String.format(Locale.ROOT, "«%s» (r=%.2f, %s%s)",
                    c.getName(), c.getCorrelation(), dir,
                    c.getChangePercent() != 0
                            ? String.format(Locale.ROOT, ", %+.1f%%", c.getChangePercent())
                            : ""));
            sb.append(i < strong.size() - 1 ? "; " : ".");
        }

        return sb.toString();
    }
}
