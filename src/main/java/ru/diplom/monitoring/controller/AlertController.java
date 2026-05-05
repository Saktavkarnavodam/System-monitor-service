package ru.diplom.monitoring.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.diplom.monitoring.exception.NotFoundException;
import ru.diplom.monitoring.model.Alert;
import ru.diplom.monitoring.model.AlertRule;
import ru.diplom.monitoring.model.User;
import ru.diplom.monitoring.security.CurrentUser;
import ru.diplom.monitoring.service.AlertService;

import java.util.Collection;
import java.util.List;

@RestController
@RequestMapping("/api/v1/alerts")
@Tag(name = "Alerts", description = "Правила оповещений и активные алерты (видны только свои)")
@SecurityRequirement(name = "bearerAuth")
public class AlertController {

    private final AlertService alertService;
    private final CurrentUser currentUser;

    public AlertController(AlertService alertService, CurrentUser currentUser) {
        this.alertService = alertService;
        this.currentUser = currentUser;
    }

    @PostMapping("/rules")
    @Operation(summary = "Создать или обновить правило алерта (привязка к текущему пользователю)")
    public AlertRule saveRule(@RequestBody AlertRule rule) {
        User u = currentUser.require();
        return alertService.saveRule(u.getId(), rule);
    }

    @GetMapping("/rules")
    @Operation(summary = "Список правил текущего пользователя")
    public Collection<AlertRule> listRules() {
        User u = currentUser.require();
        return alertService.listRules(u.getId(), u.isAdmin());
    }

    @GetMapping("/rules/{id}")
    @Operation(summary = "Получить правило по идентификатору")
    public AlertRule getRule(@PathVariable String id) {
        User u = currentUser.require();
        return alertService.findRule(id, u.getId(), u.isAdmin())
                .orElseThrow(() -> new NotFoundException("Правило не найдено: " + id));
    }

    @DeleteMapping("/rules/{id}")
    @Operation(summary = "Удалить правило алерта")
    public ResponseEntity<Void> deleteRule(@PathVariable String id) {
        User u = currentUser.require();
        if (!alertService.deleteRule(id, u.getId(), u.isAdmin())) {
            throw new NotFoundException("Правило не найдено: " + id);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/active")
    @Operation(summary = "Список активных (firing) алертов текущего пользователя")
    public Collection<Alert> active() {
        User u = currentUser.require();
        return alertService.listActive(u.getId(), u.isAdmin());
    }

    @GetMapping("/history")
    @Operation(summary = "История алертов текущего пользователя")
    public List<Alert> history(@RequestParam(defaultValue = "100") int limit) {
        User u = currentUser.require();
        return alertService.history(u.getId(), u.isAdmin(), limit);
    }

    @PostMapping("/evaluate")
    @Operation(summary = "Принудительная синхронная проверка всех правил (системно — оценивает все правила)")
    public String evaluate() {
        // фоновая оценка по всем правилам — каждая правило ограничено своим owner-фильтром в metric-запросе
        alertService.evaluate();
        return "ok";
    }
}
