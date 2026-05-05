package ru.diplom.monitoring.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ответ при создании нового agent-токена: <b>содержит сам токен в plain text</b>.
 * Это единственный момент, когда полное значение известно серверу — после
 * сохранения в БД остаётся только SHA-256-хеш.
 *
 * <p>UI обязан показать токен пользователю и предупредить: «скопируй сейчас,
 * больше ты его не увидишь» — это стандартная практика безопасных ключей
 * (точно так же поступают GitHub PAT, AWS Access Keys, Stripe API-ключи).
 */
@Schema(description = "Свежевыпущенный agent-токен — единственный момент, когда виден plain text")
public class AgentTokenIssued {

    @Schema(description = "Метаданные токена, которые потом будут показываться в списке")
    private AgentTokenInfo info;

    @Schema(description = "Полный токен в формате agt_… — показывается ровно один раз",
            example = "agt_8f3kP9qLmW2vR5tXyZaBcDeFgHiJkLmN")
    private String token;

    public AgentTokenIssued() {}

    public AgentTokenIssued(AgentTokenInfo info, String token) {
        this.info = info;
        this.token = token;
    }

    public AgentTokenInfo getInfo() { return info; }
    public void setInfo(AgentTokenInfo info) { this.info = info; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
}
