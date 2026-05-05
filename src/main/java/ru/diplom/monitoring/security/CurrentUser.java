package ru.diplom.monitoring.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import ru.diplom.monitoring.model.User;

import java.util.Optional;

@Component
public class CurrentUser {

    public Optional<User> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User u)) {
            return Optional.empty();
        }
        return Optional.of(u);
    }

    public User require() {
        return get().orElseThrow(() -> new AccessDeniedException("Не авторизован"));
    }

    public String requireId() {
        return require().getId();
    }

    public boolean isAdmin() {
        return get().map(User::isAdmin).orElse(false);
    }
}
