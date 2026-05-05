package ru.diplom.monitoring.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.diplom.monitoring.dto.AgentTokenInfo;
import ru.diplom.monitoring.dto.AgentTokenIssued;
import ru.diplom.monitoring.model.User;
import ru.diplom.monitoring.security.CurrentUser;
import ru.diplom.monitoring.service.AgentTokenService;

import java.util.List;
import java.util.Map;

/**
 * CRUD по agent-токенам пользователя.
 *
 * <p>Эндпоинты намеренно отделены от обычного auth: токены — это <b>не</b>
 * пароли, у них своя модель доступа (долгоживущие, отзываемые,
 * принадлежат конкретному юзеру, могут быть на каждый узел свои).
 */
@RestController
@RequestMapping("/api/v1/agent-tokens")
@Tag(name = "AgentTokens", description = "Долгоживущие API-ключи для агентов мониторинга")
@SecurityRequirement(name = "bearerAuth")
public class AgentTokenController {

    private final AgentTokenService service;
    private final CurrentUser currentUser;

    public AgentTokenController(AgentTokenService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PostMapping
    @Operation(summary = "Выпустить новый agent-токен",
            description = "Возвращает токен в plain text единственный раз — после этого " +
                    "сервер хранит только SHA-256 от него. Скопируйте сразу.")
    public AgentTokenIssued issue(@RequestBody Map<String, String> body) {
        User u = currentUser.require();
        String name = body.getOrDefault("name", "agent");
        return service.issue(u.getId(), name);
    }

    @GetMapping
    @Operation(summary = "Список agent-токенов текущего пользователя (без секретов)")
    public List<AgentTokenInfo> list() {
        User u = currentUser.require();
        return service.list(u.getId());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Отозвать agent-токен — все агенты, использующие его, перестанут работать")
    public ResponseEntity<Void> revoke(@PathVariable String id) {
        User u = currentUser.require();
        boolean ok = service.revoke(id, u.getId(), u.isAdmin());
        return ok ? ResponseEntity.noContent().build()
                  : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
}
