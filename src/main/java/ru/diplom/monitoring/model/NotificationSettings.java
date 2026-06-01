package ru.diplom.monitoring.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Глобальные настройки каналов уведомлений (бот-токен Telegram + SMTP).
 * Хранятся в БД одной строкой с фиксированным id={@link #SINGLETON_ID},
 * чтобы админ мог менять их прямо из UI без перезапуска приложения.
 * Если строки нет — {@link ru.diplom.monitoring.service.AlertNotifier}
 * откатывается на значения из application.yml.
 */
@Entity
@Table(name = "notification_settings")
public class NotificationSettings {

    public static final String SINGLETON_ID = "default";

    @Id
    @Column(length = 36)
    private String id = SINGLETON_ID;

    @Column(name = "telegram_bot_token", length = 200)
    private String telegramBotToken;

    @Column(name = "smtp_host", length = 200)
    private String smtpHost;

    @Column(name = "smtp_port")
    private Integer smtpPort;

    @Column(name = "smtp_username", length = 200)
    private String smtpUsername;

    @Column(name = "smtp_password", length = 200)
    private String smtpPassword;

    @Column(name = "smtp_starttls", nullable = false)
    private boolean smtpStarttls = true;

    @Column(name = "email_from", length = 200)
    private String emailFrom;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public NotificationSettings() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTelegramBotToken() { return telegramBotToken; }
    public void setTelegramBotToken(String v) { this.telegramBotToken = v; }

    public String getSmtpHost() { return smtpHost; }
    public void setSmtpHost(String v) { this.smtpHost = v; }

    public Integer getSmtpPort() { return smtpPort; }
    public void setSmtpPort(Integer v) { this.smtpPort = v; }

    public String getSmtpUsername() { return smtpUsername; }
    public void setSmtpUsername(String v) { this.smtpUsername = v; }

    public String getSmtpPassword() { return smtpPassword; }
    public void setSmtpPassword(String v) { this.smtpPassword = v; }

    public boolean isSmtpStarttls() { return smtpStarttls; }
    public void setSmtpStarttls(boolean v) { this.smtpStarttls = v; }

    public String getEmailFrom() { return emailFrom; }
    public void setEmailFrom(String v) { this.emailFrom = v; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
