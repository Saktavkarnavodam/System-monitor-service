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

    public AlertService(AlertRuleRepository ruleRepo,
                        AlertRepository alertRepo,
                        MetricService metricService,
                        NodeService nodeService) {
        this.ruleRepo = ruleRepo;
        this.alertRepo = alertRepo;
        this.metricService = metricService;
        this.nodeService = nodeService;
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
                alert.setMessage(String.format("%s %s %.3f (порог %.3f) в течение %d сек",
                        rule.getMetricName(), rule.getCondition(), latest.getValue(),
                        rule.getThreshold(), rule.getDurationSeconds()));
                alertRepo.save(alert);
            } else {
                Alert a = existing.get();
                a.setValue(latest.getValue());
                alertRepo.save(a);
            }
        } else {
            resolveIfActive(rule, latest.getNodeId(), now, "Metric returned to normal");
        }
    }

    private void resolveIfActive(AlertRule rule, String nodeId, Instant now, String reason) {
        Optional<Alert> existing = alertRepo.findFirstByRuleIdAndNodeIdAndStatus(
                rule.getId(), nodeId, AlertStatus.FIRING);
        existing.ifPresent(a -> {
            a.setStatus(AlertStatus.RESOLVED);
            a.setResolvedAt(now);
            a.setMessage((a.getMessage() == null ? "" : a.getMessage()) + " | resolved: " + reason);
            alertRepo.save(a);
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
}
