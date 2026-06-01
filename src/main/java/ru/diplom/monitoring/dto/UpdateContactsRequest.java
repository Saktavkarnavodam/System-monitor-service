package ru.diplom.monitoring.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * DTO для обновления контактов пользователя (куда слать уведомления алертов).
 * Оба поля опциональны; пустая строка / null = очистить.
 */
public class UpdateContactsRequest {

    @Email(message = "Некорректный email")
    @Size(max = 200)
    @Schema(description = "Email для уведомлений", example = "alex@example.com")
    private String email;

    @Size(max = 64)
    @Schema(description = "Telegram chat_id", example = "123456789")
    private String telegramChatId;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getTelegramChatId() { return telegramChatId; }
    public void setTelegramChatId(String telegramChatId) { this.telegramChatId = telegramChatId; }
}
