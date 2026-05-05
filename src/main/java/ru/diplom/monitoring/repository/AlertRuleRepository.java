package ru.diplom.monitoring.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.diplom.monitoring.model.AlertRule;

import java.util.List;

public interface AlertRuleRepository extends JpaRepository<AlertRule, String> {

    List<AlertRule> findByOwnerId(String ownerId);

    List<AlertRule> findByEnabledTrue();
}
