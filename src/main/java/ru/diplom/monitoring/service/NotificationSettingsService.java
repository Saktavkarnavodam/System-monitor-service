package ru.diplom.monitoring.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.diplom.monitoring.model.NotificationSettings;
import ru.diplom.monitoring.repository.NotificationSettingsRepository;

import java.time.Instant;
import java.util.Properties;

/**
 * Доступ к глобальным настройкам уведомлений (Telegram + SMTP).
 *
 * <p>Логика выбора значения: сначала смотрим в БД (то, что админ задал в UI);
 * если в БД пусто — берём дефолт из application.yml ({@code @Value}).
 * Это позволяет в дев-режиме оставить пустые env-переменные и настроить
 * всё прямо из UI, без перезапуска.
 */
@Service
public class NotificationSettingsService {

    private final NotificationSettingsRepository repo;

    @Value("${app.notifications.telegram.bot-token:}")
    private String defaultTelegramBotToken;

    @Value("${app.notifications.email.from:}")
    private String defaultEmailFrom;

    @Value("${spring.mail.host:}")
    private String defaultSmtpHost;

    @Value("${spring.mail.port:587}")
    private int defaultSmtpPort;

    @Value("${spring.mail.username:}")
    private String defaultSmtpUsername;

    @Value("${spring.mail.password:}")
    private String defaultSmtpPassword;

    public NotificationSettingsService(NotificationSettingsRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public NotificationSettings getOrEmpty() {
        return repo.findById(NotificationSettings.SINGLETON_ID).orElseGet(NotificationSettings::new);
    }

    @Transactional
    public NotificationSettings save(NotificationSettings incoming) {
        NotificationSettings cur = repo.findById(NotificationSettings.SINGLETON_ID)
                .orElseGet(NotificationSettings::new);
        cur.setId(NotificationSettings.SINGLETON_ID);
        cur.setTelegramBotToken(blankToNull(incoming.getTelegramBotToken()));
        cur.setSmtpHost(blankToNull(incoming.getSmtpHost()));
        cur.setSmtpPort(incoming.getSmtpPort());
        cur.setSmtpUsername(blankToNull(incoming.getSmtpUsername()));
        // если пользователь оставил поле пароля пустым — сохраняем старый пароль,
        // чтобы при правке других полей не приходилось каждый раз вводить пароль заново
        if (incoming.getSmtpPassword() != null && !incoming.getSmtpPassword().isBlank()) {
            cur.setSmtpPassword(incoming.getSmtpPassword());
        }
        cur.setSmtpStarttls(incoming.isSmtpStarttls());
        cur.setEmailFrom(blankToNull(incoming.getEmailFrom()));
        cur.setUpdatedAt(Instant.now());
        return repo.save(cur);
    }

    /** Эффективный bot-token: из БД либо из application.yml. */
    public String effectiveTelegramBotToken() {
        String v = getOrEmpty().getTelegramBotToken();
        return (v != null && !v.isBlank()) ? v : defaultTelegramBotToken;
    }

    /** Эффективный адрес "From:" для писем. */
    public String effectiveEmailFrom() {
        String v = getOrEmpty().getEmailFrom();
        return (v != null && !v.isBlank()) ? v : defaultEmailFrom;
    }

    /**
     * Собирает {@link JavaMailSenderImpl} из эффективных SMTP-настроек.
     * Возвращает {@code null}, если SMTP не сконфигурирован (host пустой).
     */
    public JavaMailSenderImpl buildMailSender() {
        NotificationSettings s = getOrEmpty();
        String host = pick(s.getSmtpHost(), defaultSmtpHost);
        if (host == null || host.isBlank()) return null;

        int port = s.getSmtpPort() != null && s.getSmtpPort() > 0 ? s.getSmtpPort() : defaultSmtpPort;
        String user = pick(s.getSmtpUsername(), defaultSmtpUsername);
        String pass = pick(s.getSmtpPassword(), defaultSmtpPassword);
        boolean starttls = s.isSmtpStarttls();

        JavaMailSenderImpl m = new JavaMailSenderImpl();
        m.setHost(host);
        m.setPort(port);
        if (user != null && !user.isBlank()) m.setUsername(user);
        if (pass != null && !pass.isBlank()) m.setPassword(pass);
        Properties p = m.getJavaMailProperties();
        p.put("mail.transport.protocol", "smtp");
        p.put("mail.smtp.auth", user != null && !user.isBlank() ? "true" : "false");
        p.put("mail.smtp.starttls.enable", String.valueOf(starttls));
        p.put("mail.smtp.connectiontimeout", "5000");
        p.put("mail.smtp.timeout", "5000");
        p.put("mail.smtp.writetimeout", "5000");
        return m;
    }

    private static String pick(String dbValue, String fallback) {
        if (dbValue != null && !dbValue.isBlank()) return dbValue;
        return fallback;
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v;
    }
}
