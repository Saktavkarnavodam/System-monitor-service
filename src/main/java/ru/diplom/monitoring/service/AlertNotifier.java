package ru.diplom.monitoring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.diplom.monitoring.model.Alert;
import ru.diplom.monitoring.model.AlertRule;
import ru.diplom.monitoring.model.AlertStatus;
import ru.diplom.monitoring.model.User;
import ru.diplom.monitoring.repository.UserRepository;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Отправка уведомлений о событиях алертов в Telegram и/или Email.
 *
 * <p>Подписки на каналы хранятся на двух уровнях:
 * <ul>
 *   <li><b>Правило алерта</b> ({@link AlertRule#isNotifyTelegram()} / {@link AlertRule#isNotifyEmail()}) —
 *       решает, какие каналы вообще задействовать для этого правила.</li>
 *   <li><b>Пользователь</b> ({@link User#getEmail()} / {@link User#getTelegramChatId()}) —
 *       адрес назначения. Если в правиле канал включён, но у пользователя адрес
 *       пуст — канал тихо пропускается с warn-логом.</li>
 * </ul>
 *
 * <p>Сами секреты (bot token, SMTP-кред) задаются на уровне приложения двумя способами:
 * <ul>
 *   <li>через UI (админская страница «Настройки уведомлений») — хранятся в БД,
 *       читаются {@link NotificationSettingsService};</li>
 *   <li>либо через env / application.yml как дефолт.</li>
 * </ul>
 *
 * <p>Отправка асинхронная ({@code @Async}) — мы не хотим, чтобы тормозящий
 * Telegram API или SMTP подвешивал alert-evaluation-цикл.
 */
@Service
public class AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(AlertNotifier.class);
    private static final String TELEGRAM_API = "https://api.telegram.org/bot";

    private final UserRepository userRepo;
    private final ObjectMapper objectMapper;
    private final JavaMailSender fallbackMailSender;
    private final HttpClient httpClient;

    /**
     * Опциональный сервис настроек из БД. Не передаётся в конструктор,
     * чтобы не ломать существующие юнит-тесты {@link AlertNotifier} —
     * туда подсасывается Spring'ом по {@code @Autowired(required=false)}.
     */
    @Autowired(required = false)
    private NotificationSettingsService settings;

    /** Fallback bot-token из application.yml — используется, если settings/БД пустые. */
    @Value("${app.notifications.telegram.bot-token:}")
    private String telegramBotToken;

    /** Fallback email-from из application.yml. */
    @Value("${app.notifications.email.from:}")
    private String emailFrom;

    /**
     * Внешний URL приложения — нужен, чтобы из письма/телеги можно было
     * перейти прямо на дашборд. Пустой = ссылку не вставляем.
     */
    @Value("${app.public-url:}")
    private String publicUrl;

    public AlertNotifier(UserRepository userRepo,
                         ObjectMapper objectMapper,
                         Optional<JavaMailSender> mailSender) {
        this.userRepo = userRepo;
        this.objectMapper = objectMapper;
        this.fallbackMailSender = mailSender.orElse(null);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @PostConstruct
    void logConfig() {
        // На старте лог по дефолтам из application.yml. БД-настройки админ
        // правит из UI уже после запуска — статус читается /notifications/status.
        try {
            log.info("AlertNotifier: telegram(default)={} email(default)={}",
                    (telegramBotToken == null || telegramBotToken.isBlank()) ? "off" : "on",
                    (fallbackMailSender != null && emailFrom != null && !emailFrom.isBlank()) ? "on" : "off");
        } catch (Exception e) {
            log.debug("AlertNotifier logConfig: {}", e.getMessage());
        }
    }

    /**
     * Отправляет уведомление о событии алерта по всем подходящим каналам.
     * Вызывается AlertService на смену состояния алерта (FIRING / RESOLVED).
     */
    @Async("notifierExecutor")
    public void notify(AlertRule rule, Alert alert) {
        if (rule == null || alert == null) return;
        Optional<User> owner = userRepo.findById(alert.getOwnerId());
        if (owner.isEmpty()) {
            log.warn("Notify: владелец алерта не найден, ownerId={}", alert.getOwnerId());
            return;
        }
        User user = owner.get();
        if (rule.isNotifyTelegram()) {
            sendTelegram(user, alert);
        }
        if (rule.isNotifyEmail()) {
            sendEmail(user, alert);
        }
    }

    void sendTelegram(User user, Alert alert) {
        String token = resolveTelegramToken();
        if (token.isBlank()) {
            log.warn("Telegram-уведомление пропущено: bot-token не настроен (UI → «Настройки уведомлений» или env TELEGRAM_BOT_TOKEN)");
            return;
        }
        if (user.getTelegramChatId() == null || user.getTelegramChatId().isBlank()) {
            log.warn("Telegram-уведомление пропущено: у пользователя {} не задан telegramChatId",
                    user.getUsername());
            return;
        }
        try {
            String url = TELEGRAM_API + token + "/sendMessage";
            String text = buildText(alert, true);
            String body = objectMapper.writeValueAsString(Map.of(
                    "chat_id", user.getTelegramChatId(),
                    "text", text,
                    "parse_mode", "HTML",
                    "disable_web_page_preview", true
            ));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("Telegram-уведомление: HTTP {} {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.warn("Telegram-уведомление failed: {}", e.getMessage());
        }
    }

    void sendEmail(User user, Alert alert) {
        MailSender sender = resolveMailSender();
        if (sender == null) {
            log.warn("Email-уведомление пропущено: SMTP не сконфигурирован (UI → «Настройки уведомлений» или spring.mail.*)");
            return;
        }
        String from = resolveEmailFrom();
        if (from.isBlank()) {
            log.warn("Email-уведомление пропущено: не задан email-from (UI → «Настройки уведомлений» или env EMAIL_FROM)");
            return;
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            log.warn("Email-уведомление пропущено: у пользователя {} не задан email", user.getUsername());
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(user.getEmail());
            String prefix = alert.getStatus() == AlertStatus.FIRING ? "[FIRING]" : "[RESOLVED]";
            String subject = String.format("%s %s: %s",
                    prefix,
                    alert.getSeverity() == null ? "" : alert.getSeverity().name(),
                    alert.getRuleName());
            msg.setSubject(subject.trim());
            msg.setText(buildText(alert, false));
            sender.send(msg);
        } catch (Exception e) {
            log.warn("Email-уведомление failed: {}", e.getMessage());
        }
    }

    /**
     * Сообщение для пользователя. {@code htmlForTelegram=true} — добавляем
     * HTML-разметку (Telegram parse_mode=HTML понимает &lt;b&gt;,&lt;code&gt;).
     */
    String buildText(Alert alert, boolean htmlForTelegram) {
        String status = alert.getStatus() == AlertStatus.FIRING ? "FIRING" : "RESOLVED";
        String emoji = alert.getStatus() == AlertStatus.FIRING ? "🔥" : "✅"; // 🔥 / ✅
        String severity = alert.getSeverity() == null ? "" : alert.getSeverity().name();
        StringBuilder sb = new StringBuilder(256);
        if (htmlForTelegram) {
            sb.append(emoji).append(" <b>").append(status).append("</b> ")
                    .append("<b>").append(escape(alert.getRuleName())).append("</b>")
                    .append(severity.isBlank() ? "" : " (" + severity + ")")
                    .append('\n');
            sb.append("metric: <code>").append(escape(alert.getMetricName())).append("</code>\n");
            sb.append(String.format(Locale.ROOT, "value: <b>%.3f</b> (порог %.3f)%n",
                    alert.getValue(), alert.getThreshold()));
            if (alert.getNodeId() != null) {
                sb.append("node: <code>").append(escape(alert.getNodeId())).append("</code>\n");
            }
            sb.append("fired_at: ").append(alert.getFiredAt()).append('\n');
            if (alert.getStatus() == AlertStatus.RESOLVED && alert.getResolvedAt() != null) {
                sb.append("resolved_at: ").append(alert.getResolvedAt()).append('\n');
            }
            if (publicUrl != null && !publicUrl.isBlank()) {
                sb.append("\n<a href=\"").append(publicUrl).append("\">Открыть дашборд</a>");
            }
        } else {
            sb.append(status).append(": ").append(alert.getRuleName());
            if (!severity.isBlank()) sb.append(" (").append(severity).append(")");
            sb.append("\nmetric: ").append(alert.getMetricName());
            sb.append(String.format(Locale.ROOT, "%nvalue: %.3f (порог %.3f)",
                    alert.getValue(), alert.getThreshold()));
            if (alert.getNodeId() != null) sb.append("\nnode: ").append(alert.getNodeId());
            sb.append("\nfired_at: ").append(alert.getFiredAt());
            if (alert.getStatus() == AlertStatus.RESOLVED && alert.getResolvedAt() != null) {
                sb.append("\nresolved_at: ").append(alert.getResolvedAt());
            }
            if (publicUrl != null && !publicUrl.isBlank()) {
                sb.append("\n\nДашборд: ").append(publicUrl);
            }
        }
        return sb.toString();
    }

    /**
     * Экранирование для Telegram HTML-режима: amp/lt/gt.
     * См. https://core.telegram.org/bots/api#html-style
     */
    static String escape(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // -------------------------------------------------------------------------
    // Резолверы: предпочтение БД-настроек, fallback — application.yml
    // -------------------------------------------------------------------------

    private String resolveTelegramToken() {
        if (settings != null) {
            String v = settings.effectiveTelegramBotToken();
            if (v != null) return v;
        }
        return telegramBotToken == null ? "" : telegramBotToken;
    }

    private String resolveEmailFrom() {
        if (settings != null) {
            String v = settings.effectiveEmailFrom();
            if (v != null) return v;
        }
        return emailFrom == null ? "" : emailFrom;
    }

    private MailSender resolveMailSender() {
        if (settings != null) {
            JavaMailSender built = settings.buildMailSender();
            if (built != null) return built;
        }
        return fallbackMailSender;
    }
}
