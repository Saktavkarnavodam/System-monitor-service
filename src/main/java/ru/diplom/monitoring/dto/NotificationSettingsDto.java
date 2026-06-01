package ru.diplom.monitoring.dto;

/**
 * DTO для GET/PUT настроек уведомлений. Пароль на GET возвращается
 * замаскированным, чтобы не светить плейн-текст в UI.
 */
public class NotificationSettingsDto {

    private String telegramBotToken;
    private String smtpHost;
    private Integer smtpPort;
    private String smtpUsername;
    /** На GET — null/маска ({@link #PASSWORD_MASK}), на PUT — новый пароль или null/пусто, чтобы оставить старый. */
    private String smtpPassword;
    private boolean smtpStarttls = true;
    private String emailFrom;
    /** Только на GET. Показывает статус каналов («telegram: ok / not configured»). */
    private String status;

    public static final String PASSWORD_MASK = "********";

    public NotificationSettingsDto() {}

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

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
}
