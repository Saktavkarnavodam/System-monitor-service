package ru.diplom.monitoring.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.diplom.monitoring.model.Alert;
import ru.diplom.monitoring.model.AlertRule;
import ru.diplom.monitoring.model.AlertStatus;
import ru.diplom.monitoring.model.Metric;
import ru.diplom.monitoring.repository.AlertRepository;
import ru.diplom.monitoring.repository.AlertRuleRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Управление правилами алертов и активными/историческими алертами.
 * Все правила и алерты хранятся в БД, поиск активного алерта по
 * правилу+узлу — O(1) благодаря индексу {@code idx_alerts_rule_node_status}.
 */
@Service
@Transactional
public class AlertService {

    private final AlertRuleRepository ruleRepo;
    private final AlertRepository alertRepo;
    private final MetricService metricService;
    private final NodeService nodeService;
    private final AlertNotifier notifier;

    public AlertService(AlertRuleRepository ruleRepo,
                        AlertRepository alertRepo,
                        MetricService metricService,
                        NodeService nodeService,
                        AlertNotifier notifier) {
        this.ruleRepo = ruleRepo;
        this.alertRepo = alertRepo;
        this.metricService = metricService;
        this.nodeService = nodeService;
        this.notifier = notifier;
    }

    public AlertRule saveRule(String ownerId, AlertRule rule) {
        if (rule.getId() != null && !rule.getId().isBlank()) {
            Optional<AlertRule> existing = ruleRepo.findById(rule.getId());
            if (existing.isPresent() && !existing.get().getOwnerId().equals(ownerId)) {
                throw new SecurityException("Нет доступа к этому правилу");
            }
        } else {
            rule.setId(UUID.randomUUID().toString());
        }
        rule.setOwnerId(ownerId);
        if (rule.getNodeId() != null) {
            List<String> ownedIds = nodeService.ownedNodeIds(ownerId);
            if (!ownedIds.contains(rule.getNodeId())) {
                throw new IllegalArgumentException("Узел не принадлежит пользователю: " + rule.getNodeId());
            }
        }
        // Защита от дублей: правило с той же метрикой + условием + порогом + узлом
        // сработает одновременно с уже существующим, и уведомления придут дважды
        // (и в Telegram, и на почту). Блокируем такое при создании/редактировании.
        for (AlertRule other : ruleRepo.findByOwnerId(ownerId)) {
            if (other.getId().equals(rule.getId())) continue; // не сравниваем правило с самим собой
            if (sameTarget(other, rule)) {
                throw new IllegalArgumentException(
                        "Дубль правила: «" + other.getName() + "» уже отслеживает "
                        + rule.getMetricName() + " " + rule.getCondition() + " " + rule.getThreshold()
                        + (rule.getNodeId() == null ? " (все узлы)" : " на этом узле")
                        + ". Иначе уведомления придут дважды — измените метрику, условие или порог.");
            }
        }
        return ruleRepo.save(rule);
    }

    public boolean deleteRule(String id, String requesterId, boolean isAdmin) {
        Optional<AlertRule> r = ruleRepo.findById(id);
        if (r.isEmpty()) return false;
        if (!isAdmin && !r.get().getOwnerId().equals(requesterId)) return false;
        // удаляем активные алерты этого правила, чтобы не висели сиротами
        List<Alert> firing = alertRepo.findByRuleIdAndStatus(id, AlertStatus.FIRING);
        Instant now = Instant.now();
        for (Alert a : firing) {
            a.setStatus(AlertStatus.RESOLVED);
            a.setResolvedAt(now);
            a.setMessage((a.getMessage() == null ? "" : a.getMessage()) + " | resolved: rule deleted");
            alertRepo.save(a);
        }
        ruleRepo.deleteById(id);
        return true;
    }

    @Transactional(readOnly = true)
    public List<AlertRule> listRules(String ownerId, boolean isAdmin) {
        if (isAdmin) return ruleRepo.findAll();
        return ruleRepo.findByOwnerId(ownerId);
    }

    @Transactional(readOnly = true)
    public Optional<AlertRule> findRule(String id, String requesterId, boolean isAdmin) {
        Optional<AlertRule> r = ruleRepo.findById(id);
        if (r.isEmpty()) return Optional.empty();
        if (!isAdmin && !r.get().getOwnerId().equals(requesterId)) return Optional.empty();
        return r;
    }

    @Transactional(readOnly = true)
    public List<Alert> listActive(String ownerId, boolean isAdmin) {
        if (isAdmin) return alertRepo.findByStatus(AlertStatus.FIRING);
        return alertRepo.findByOwnerIdAndStatus(ownerId, AlertStatus.FIRING);
    }

    @Transactional(readOnly = true)
    public List<Alert> history(String ownerId, boolean isAdmin, int limit) {
        int safeLimit = limit > 0 ? limit : 1000;
        var pg = PageRequest.of(0, safeLimit);
        if (isAdmin) return alertRepo.findAllOrderByFiredAtDesc(pg);
        return alertRepo.findByOwnerIdOrderByFiredAtDesc(ownerId, pg);
    }

    /**
     * Прогон всех включённых правил. Для каждого:
     *  1) забираем последние точки метрики за окно правила;
     *  2) если все нарушают условие — поднимаем (или поддерживаем) FIRING-алерт;
     *  3) иначе — резолвим висящий FIRING (если есть).
     */
    public void evaluate() {
        Instant now = Instant.now();
        List<AlertRule> enabled = ruleRepo.findByEnabledTrue();
        for (AlertRule rule : enabled) {
            evaluateRule(rule, now);
        }
    }

    private void evaluateRule(AlertRule rule, Instant now) {
        Duration window = Duration.ofSeconds(Math.max(rule.getDurationSeconds(), 1));
        Instant from = now.minus(window);
        // правило оценивает только метрики своего владельца
        List<Metric> recent = metricService.query(rule.getOwnerId(), rule.getNodeId(),
                rule.getMetricName(), from, now, 0);

        if (recent.isEmpty()) {
            resolveIfActive(rule, rule.getNodeId(), now, "No data in window");
            return;
        }

        boolean allViolate = recent.stream()
                .allMatch(m -> rule.getCondition().test(rule.getThreshold()).test(m.getValue()));
        Metric latest = recent.get(0); // search возвращает DESC по timestamp

        Optional<Alert> existing = alertRepo.findFirstByRuleIdAndNodeIdAndStatus(
                rule.getId(), latest.getNodeId(), AlertStatus.FIRING);

        if (allViolate) {
            if (existing.isEmpty()) {
                Alert alert = new Alert();
                alert.setId(UUID.randomUUID().toString());
                alert.setOwnerId(rule.getOwnerId());
                alert.setRuleId(rule.getId());
                alert.setRuleName(rule.getName());
                alert.setNodeId(latest.getNodeId());
                alert.setMetricName(rule.getMetricName());
                alert.setValue(latest.getValue());
                alert.setThreshold(rule.getThreshold());
                alert.setSeverity(rule.getSeverity());
                alert.setStatus(AlertStatus.FIRING);
                alert.setFiredAt(now);
                alert.setMessage(String.format(java.util.Locale.ROOT,
                        "%s %s %.3f (порог %.3f) в течение %d сек",
                        rule.getMetricName(), rule.getCondition(), latest.getValue(),
                        rule.getThreshold(), rule.getDurationSeconds()));
                Alert saved = alertRepo.save(alert);
                notifySafely(rule, saved);
            } else {
                Alert a = existing.get();
                a.setValue(latest.getValue());
                alertRepo.save(a);
            }
        } else {
            resolveIfActive(rule, latest.getNodeId(), now, "Metric returned to normal");
        }
    }

    /**
     * Async-уведомление; вызывается из транзакции AlertService.evaluate(),
     * но сам notifier помечен @Async + ловит все исключения внутри,
     * поэтому транзакция не страдает.
     */
    private void notifySafely(AlertRule rule, Alert alert) {
        try {
            notifier.notify(rule, alert);
        } catch (Exception e) {
            // notifier сам логирует, но на всякий случай — глотаем
        }
    }

    private void resolveIfActive(AlertRule rule, String nodeId, Instant now, String reason) {
        Optional<Alert> existing = alertRepo.findFirstByRuleIdAndNodeIdAndStatus(
                rule.getId(), nodeId, AlertStatus.FIRING);
        existing.ifPresent(a -> {
            a.setStatus(AlertStatus.RESOLVED);
            a.setResolvedAt(now);
            a.setMessage((a.getMessage() == null ? "" : a.getMessage()) + " | resolved: " + reason);
            Alert saved = alertRepo.save(a);
            notifySafely(rule, saved);
        });
    }

    /**
     * Удалить активные алерты для узла (вызывается при deregister узла).
     * Резолвим, а не удаляем, чтобы сохранить историю.
     */
    public void purgeForNode(String nodeId) {
        // помечаем висящие FIRING-алерты этого узла как RESOLVED
        Instant now = Instant.now();
        for (Alert a : alertRepo.findByStatus(AlertStatus.FIRING)) {
            if (nodeId.equals(a.getNodeId())) {
                a.setStatus(AlertStatus.RESOLVED);
                a.setResolvedAt(now);
                a.setMessage((a.getMessage() == null ? "" : a.getMessage()) + " | resolved: node deregistered");
                alertRepo.save(a);
            }
        }
    }

    /** Две «мишени» правил совпадают, если это одна метрика, одно условие, один порог и один узел. */
    private static boolean sameTarget(AlertRule a, AlertRule b) {
        return a.getCondition() == b.getCondition()
                && Double.compare(a.getThreshold(), b.getThreshold()) == 0
                && trimEquals(a.getMetricName(), b.getMetricName())
                && java.util.Objects.equals(a.getNodeId(), b.getNodeId());
    }

    private static boolean trimEquals(String x, String y) {
        if (x == null || y == null) return x == y;
        return x.trim().equals(y.trim());
    }
}
