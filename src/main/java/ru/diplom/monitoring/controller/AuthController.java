package ru.diplom.monitoring.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.diplom.monitoring.dto.AuthRequest;
import ru.diplom.monitoring.dto.AuthResponse;
import ru.diplom.monitoring.dto.UpdateContactsRequest;
import ru.diplom.monitoring.model.User;
import ru.diplom.monitoring.security.CurrentUser;
import ru.diplom.monitoring.security.JwtService;
import ru.diplom.monitoring.service.UserService;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Регистрация, вход и информация о текущем пользователе")
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;
    private final CurrentUser currentUser;

    public AuthController(UserService userService, JwtService jwtService, CurrentUser currentUser) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.currentUser = currentUser;
    }

    @PostMapping("/register")
    @Operation(summary = "Создать нового пользователя и получить JWT")
    public AuthResponse register(@Valid @RequestBody AuthRequest req) {
        User u = userService.register(req.getUsername(), req.getPassword());
        String token = jwtService.issue(u);
        return new AuthResponse(token, u.getId(), u.getUsername(), u.getRole(), jwtService.ttlSeconds());
    }

    @PostMapping("/login")
    @Operation(summary = "Войти и получить JWT")
    public AuthResponse login(@Valid @RequestBody AuthRequest req) {
        User u = userService.findByUsername(req.getUsername())
                .orElseThrow(() -> new AccessDeniedException("Неверные имя пользователя или пароль"));
        if (!userService.checkPassword(u, req.getPassword())) {
            throw new AccessDeniedException("Неверные имя пользователя или пароль");
        }
        String token = jwtService.issue(u);
        return new AuthResponse(token, u.getId(), u.getUsername(), u.getRole(), jwtService.ttlSeconds());
    }

    @GetMapping("/me")
    @Operation(summary = "Информация о текущем пользователе", security = @SecurityRequirement(name = "bearerAuth"))
    public User me() {
        return currentUser.require();
    }

    @PutMapping("/me/contacts")
    @Operation(summary = "Обновить email и Telegram chat_id для уведомлений",
            security = @SecurityRequirement(name = "bearerAuth"))
    public User updateContacts(@Valid @RequestBody UpdateContactsRequest req) {
        User u = currentUser.require();
        return userService.updateContacts(u.getId(), req.getEmail(), req.getTelegramChatId());
    }
}
