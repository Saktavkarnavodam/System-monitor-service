package ru.diplom.monitoring.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.diplom.monitoring.dto.AgentTokenInfo;
import ru.diplom.monitoring.dto.AgentTokenIssued;
import ru.diplom.monitoring.model.AgentToken;
import ru.diplom.monitoring.repository.AgentTokenRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Управление долгоживущими agent-токенами.
 *
 * <p>Жизненный цикл:
 * <ol>
 *   <li>{@link #issue(String, String)} — генерация: 32 случайных байта,
 *       Base64URL без паддинга, префикс {@code agt_}; в БД сохраняется
 *       только SHA-256 хеш плюс последние 6 символов для отображения;</li>
 *   <li>{@link #findActiveByToken(String)} — поиск активного токена по
 *       его plain-text значению (вызывается на каждый запрос с заголовком
 *       Authorization: Bearer agt_…);</li>
 *   <li>{@link #revoke(String, String, boolean)} — отзыв (мягкое удаление):
 *       устанавливается revoked=true, токен перестаёт работать, но запись
 *       остаётся в истории.</li>
 * </ol>
 *
 * <p>Хранение SHA-256-хеша вместо plain text — стандарт индустрии.
 * Даже если злоумышленник получит дамп БД, восстановить токены он не сможет.
 */
@Service
@Transactional
public class AgentTokenService {

    /** Префикс, по которому JwtAuthFilter отличает agent-токен от JWT. */
    public static final String TOKEN_PREFIX = "agt_";

    /** Длина случайных байт для генерации токена (32 байта = 256 бит энтропии). */
    private static final int RANDOM_BYTES = 32;

    /** Сколько последних символов токена показывать в UI как «суффикс». */
    private static final int SUFFIX_LEN = 6;

    private final AgentTokenRepository repo;
    private final SecureRandom random = new SecureRandom();

    public AgentTokenService(AgentTokenRepository repo) {
        this.repo = repo;
    }

    /**
     * Выпустить новый токен для пользователя {@code ownerId} с человекочитаемым
     * именем {@code name}. Возвращает <b>полный токен в plain text</b> —
     * единственный раз, когда сервер его знает; дальше остаётся только хеш.
     */
    public AgentTokenIssued issue(String ownerId, String name) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId обязателен");
        }
        String label = (name == null || name.isBlank()) ? "agent" : name.trim();
        if (label.length() > 64) label = label.substring(0, 64);

        // Авто-отзыв «висящих» токенов: если пользователь нажал «Получить
        // команду установки» несколько раз без выполнения её на узле, старые
        // одноразовые токены копятся в UI. Считаем токен «висящим», если
        // ни одного запроса с ним не приходило (lastUsedAt == null) — такой
        // никем не используется и его безопасно отозвать. Уже задействованные
        // токены (есть хотя бы один запрос) — не трогаем, они стоят на живых
        // агентах.
        for (AgentToken stale : repo.findByOwnerIdAndLastUsedAtIsNullAndRevokedFalse(ownerId)) {
            stale.setRevoked(true);
            repo.save(stale);
        }

        // 1) генерируем секрет
        String secret = generateSecret();
        String fullToken = TOKEN_PREFIX + secret;
        String hash = sha256Hex(fullToken);
        String suffix = fullToken.substring(fullToken.length() - SUFFIX_LEN);

        // 2) сохраняем только хеш + метаданные
        AgentToken at = new AgentToken();
        at.setId(UUID.randomUUID().toString());
        at.setOwnerId(ownerId);
        at.setName(label);
        at.setTokenHash(hash);
        at.setTokenSuffix(suffix);
        at.setCreatedAt(Instant.now());
        at.setRevoked(false);
        repo.save(at);

        AgentTokenInfo info = toInfo(at);
        return new AgentTokenIssued(info, fullToken);
    }

    /**
     * Найти активный токен по его plain-text значению.
     * Используется в JwtAuthFilter — если найден, помечаем lastUsedAt = now.
     */
    public Optional<AgentToken> findActiveByToken(String fullToken) {
        if (fullToken == null || !fullToken.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        String hash = sha256Hex(fullToken);
        return repo.findByTokenHashAndRevokedFalse(hash);
    }

    /**
     * Обновить {@code lastUsedAt} — вызывается из auth-фильтра после успешной
     * аутентификации по токену. Делается отдельным методом чтобы у фильтра
     * был свой transactional boundary и при ошибке записи запрос всё равно
     * продолжился.
     */
    public void touch(String tokenId) {
        repo.findById(tokenId).ifPresent(t -> {
            t.setLastUsedAt(Instant.now());
            repo.save(t);
        });
    }

    @Transactional(readOnly = true)
    public List<AgentTokenInfo> list(String ownerId) {
        return repo.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
                .map(this::toInfo).toList();
    }

    /**
     * Отзыв токена. Мягкое удаление: запись сохраняется (для аудита),
     * но в auth-фильтре больше не находится.
     *
     * @return true если токен был найден и отозван (или уже отозван),
     *         false — если не найден или принадлежит другому пользователю.
     */
    public boolean revoke(String tokenId, String requesterId, boolean isAdmin) {
        Optional<AgentToken> opt = repo.findById(tokenId);
        if (opt.isEmpty()) return false;
        AgentToken t = opt.get();
        if (!isAdmin && !t.getOwnerId().equals(requesterId)) return false;
        if (!t.isRevoked()) {
            t.setRevoked(true);
            repo.save(t);
        }
        return true;
    }

    // -----------------------------------------------------------
    // helpers
    // -----------------------------------------------------------

    private String generateSecret() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        // URL-safe Base64 без паддинга — компактно и безопасно для curl-команд
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256, возвращается hex-строка длиной 64 символа. */
    static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен — это невозможно", e);
        }
    }

    private AgentTokenInfo toInfo(AgentToken t) {
        return new AgentTokenInfo(
                t.getId(), t.getName(), t.getTokenSuffix(),
                t.getCreatedAt(), t.getLastUsedAt(), t.isRevoked());
    }
}
