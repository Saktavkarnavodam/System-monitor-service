package ru.diplom.monitoring.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.diplom.monitoring.dto.NotificationSettingsDto;
import ru.diplom.monitoring.dto.NotificationsStatus;
import ru.diplom.monitoring.model.NotificationSettings;
import ru.diplom.monitoring.model.User;
import ru.diplom.monitoring.security.CurrentUser;
import ru.diplom.monitoring.service.NotificationSettingsService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Админ-эндпоинты для глобальных настроек уведомлений (Telegram + SMTP).
 * Не-админы могут получить публичный {@link NotificationsStatus} — чтобы UI
 * понимал, какие каналы работают, и подсказывал это пользователю.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "Настройки каналов уведомлений (admin) и публичный статус")
public class NotificationSettingsController {

    private final NotificationSettingsService settingsService;
    private final CurrentUser currentUser;

    public NotificationSettingsController(NotificationSettingsService settingsService,
                                          CurrentUser currentUser) {
        this.settingsService = settingsService;
        this.currentUser = currentUser;
    }

    @GetMapping("/status")
    @Operation(summary = "Какие каналы сейчас настроены — без секретов",
            security = @SecurityRequirement(name = "bearerAuth"))
    public NotificationsStatus status() {
        currentUser.require();
        boolean tg = !settingsService.effectiveTelegramBotToken().isBlank();
        boolean email = settingsService.buildMailSender() != null
                && !settingsService.effectiveEmailFrom().isBlank();
        return new NotificationsStatus(tg, email, settingsService.effectiveEmailFrom());
    }

    @GetMapping("/settings")
    @Operation(summary = "Получить настройки уведомлений (admin)",
            security = @SecurityRequirement(name = "bearerAuth"))
    public NotificationSettingsDto getSettings() {
        requireAdmin();
        NotificationSettings s = settingsService.getOrEmpty();
        NotificationSettingsDto dto = new NotificationSettingsDto();
        dto.setTelegramBotToken(s.getTelegramBotToken());
        dto.setSmtpHost(s.getSmtpHost());
        dto.setSmtpPort(s.getSmtpPort());
        dto.setSmtpUsername(s.getSmtpUsername());
        dto.setSmtpPassword(s.getSmtpPassword() == null || s.getSmtpPassword().isBlank()
                ? null
                : NotificationSettingsDto.PASSWORD_MASK);
        dto.setSmtpStarttls(s.isSmtpStarttls());
        dto.setEmailFrom(s.getEmailFrom());
        boolean tg = !settingsService.effectiveTelegramBotToken().isBlank();
        boolean email = settingsService.buildMailSender() != null
                && !settingsService.effectiveEmailFrom().isBlank();
        dto.setStatus("telegram=" + (tg ? "on" : "off") + ", email=" + (email ? "on" : "off"));
        return dto;
    }

    @PutMapping("/settings")
    @Operation(summary = "Сохранить настройки уведомлений (admin)",
            security = @SecurityRequirement(name = "bearerAuth"))
    public NotificationSettingsDto saveSettings(@RequestBody NotificationSettingsDto dto) {
        requireAdmin();
        NotificationSettings incoming = new NotificationSettings();
        incoming.setTelegramBotToken(dto.getTelegramBotToken());
        incoming.setSmtpHost(dto.getSmtpHost());
        incoming.setSmtpPort(dto.getSmtpPort());
        incoming.setSmtpUsername(dto.getSmtpUsername());
        // пустой/маскированный пароль = не менять (см. NotificationSettingsService.save)
        if (dto.getSmtpPassword() != null
                && !dto.getSmtpPassword().isBlank()
                && !NotificationSettingsDto.PASSWORD_MASK.equals(dto.getSmtpPassword())) {
            incoming.setSmtpPassword(dto.getSmtpPassword());
        }
        incoming.setSmtpStarttls(dto.isSmtpStarttls());
        incoming.setEmailFrom(dto.getEmailFrom());
        settingsService.save(incoming);
        return getSettings();
    }

    @PostMapping("/test")
    @Operation(summary = "Отправить тестовое сообщение (admin). channel=telegram|email",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Map<String, Object>> sendTest(@RequestParam String channel) {
        requireAdmin();
        User me = currentUser.require();
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            if ("telegram".equalsIgnoreCase(channel)) {
                sendTelegramTest(me);
            } else if ("email".equalsIgnoreCase(channel)) {
                sendEmailTest(me);
            } else {
                body.put("ok", false);
                body.put("error", "Неизвестный канал: " + channel + " (ожидается telegram|email)");
                return ResponseEntity.badRequest().body(body);
            }
            body.put("ok", true);
            body.put("message", "Тест отправлен. Проверьте " + channel + ".");
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            body.put("ok", false);
            body.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return ResponseEntity.status(500).body(body);
        }
    }

    private void sendTelegramTest(User me) throws Exception {
        String token = settingsService.effectiveTelegramBotToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Не задан Telegram bot-token");
        }
        if (me.getTelegramChatId() == null || me.getTelegramChatId().isBlank()) {
            throw new IllegalStateException("У текущего пользователя не задан Telegram chat_id (раздел «Контакты для алертов»)");
        }
        String url = "https://api.telegram.org/bot" + token + "/sendMessage";
        String json = "{\"chat_id\":\"" + me.getTelegramChatId().replace("\"", "\\\"")
                + "\",\"text\":\"\\u2705 Тестовое сообщение из системы мониторинга. Канал Telegram настроен корректно.\"}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("Telegram API ответил " + resp.statusCode() + ": " + resp.body());
        }
    }

    private void sendEmailTest(User me) {
        JavaMailSenderImpl sender = settingsService.buildMailSender();
        if (sender == null) {
            throw new IllegalStateException("Не задан SMTP host");
        }
        String from = settingsService.effectiveEmailFrom();
        if (from == null || from.isBlank()) {
            throw new IllegalStateException("Не задан email-from");
        }
        if (me.getEmail() == null || me.getEmail().isBlank()) {
            throw new IllegalStateException("У текущего пользователя не задан email (раздел «Контакты для алертов»)");
        }
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(me.getEmail());
        msg.setSubject("[TEST] Тестовое сообщение из системы мониторинга");
        msg.setText("Это тестовое письмо. Если вы его получили — SMTP-канал настроен корректно.\n"
                + "From: " + from + "\nTo: " + me.getEmail());
        sender.send(msg);
    }

    private void requireAdmin() {
        if (!currentUser.isAdmin()) {
            throw new AccessDeniedException("Требуются права администратора");
        }
    }
}
