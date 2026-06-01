package ru.diplom.monitoring.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.diplom.monitoring.model.NotificationSettings;

public interface NotificationSettingsRepository extends JpaRepository<NotificationSettings, String> {
}
