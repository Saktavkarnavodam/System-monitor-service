package ru.diplom.monitoring.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.diplom.monitoring.model.Role;
import ru.diplom.monitoring.model.User;
import ru.diplom.monitoring.repository.UserRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository repo;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.bootstrap-admin.username:admin}")
    private String bootstrapAdminUsername;

    @Value("${app.bootstrap-admin.password:admin123}")
    private String bootstrapAdminPassword;

    @Value("${app.bootstrap-admin.enabled:true}")
    private boolean bootstrapAdminEnabled;

    public UserService(UserRepository repo, PasswordEncoder passwordEncoder) {
        this.repo = repo;
        this.passwordEncoder = passwordEncoder;
    }

    @PostConstruct
    public void bootstrapAdmin() {
        if (!bootstrapAdminEnabled) return;
        if (repo.existsByUsernameIgnoreCase(bootstrapAdminUsername)) return;
        User admin = createUser(bootstrapAdminUsername, bootstrapAdminPassword, Role.ADMIN);
        log.warn("Bootstrap admin создан: username='{}', password='{}'. Смените пароль в продакшене!",
                admin.getUsername(), bootstrapAdminPassword);
    }

    public User register(String username, String rawPassword) {
        return createUser(username, rawPassword, Role.USER);
    }

    private synchronized User createUser(String username, String rawPassword, Role role) {
        if (repo.existsByUsernameIgnoreCase(username)) {
            throw new IllegalArgumentException("Имя пользователя уже занято: " + username);
        }
        User u = new User(
                UUID.randomUUID().toString(),
                username,
                passwordEncoder.encode(rawPassword),
                role,
                Instant.now()
        );
        return repo.save(u);
    }

    @Transactional(readOnly = true)
    public Optional<User> findByUsername(String username) {
        if (username == null) return Optional.empty();
        return repo.findByUsernameIgnoreCase(username);
    }

    @Transactional(readOnly = true)
    public Optional<User> findById(String id) {
        return repo.findById(id);
    }

    public boolean checkPassword(User user, String rawPassword) {
        return passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }

    /**
     * Обновляет контакты пользователя (email и Telegram chat_id).
     * null-значение очищает соответствующее поле, пустая строка — тоже.
     */
    public User updateContacts(String userId, String email, String telegramChatId) {
        User u = repo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + userId));
        u.setEmail(normalize(email));
        u.setTelegramChatId(normalize(telegramChatId));
        return repo.save(u);
    }

    private static String normalize(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
