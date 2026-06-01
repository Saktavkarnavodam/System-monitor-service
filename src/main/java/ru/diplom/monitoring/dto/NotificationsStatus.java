package ru.diplom.monitoring.dto;

/**
 * Публичный статус каналов уведомлений (без секретов).
 * Видят все авторизованные — нужен в UI, чтобы при редактировании контактов
 * или правила сразу было понятно, какие каналы сейчас вообще работают.
 */
public class NotificationsStatus {
    private boolean telegramConfigured;
    private boolean emailConfigured;
    private String emailFrom;

    public NotificationsStatus() {}

    public NotificationsStatus(boolean tg, boolean email, String emailFrom) {
        this.telegramConfigured = tg;
        this.emailConfigured = email;
        this.emailFrom = emailFrom;
    }

    public boolean isTelegramConfigured() { return telegramConfigured; }
    public void setTelegramConfigured(boolean v) { this.telegramConfigured = v; }

    public boolean isEmailConfigured() { return emailConfigured; }
    public void setEmailConfigured(boolean v) { this.emailConfigured = v; }

    public String getEmailFrom() { return emailFrom; }
    public void setEmailFrom(String v) { this.emailFrom = v; }
}
