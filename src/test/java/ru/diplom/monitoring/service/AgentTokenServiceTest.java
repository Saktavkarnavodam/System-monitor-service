package ru.diplom.monitoring.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import ru.diplom.monitoring.dto.AgentTokenInfo;
import ru.diplom.monitoring.dto.AgentTokenIssued;
import ru.diplom.monitoring.model.AgentToken;
import ru.diplom.monitoring.repository.AgentTokenRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Тесты agent-токенов:
 *  - сам токен возвращается plain text только при выпуске;
 *  - в БД лежит SHA-256, не plain text;
 *  - поиск по полному токену работает, по чужому — нет;
 *  - revoke отключает токен, но запись остаётся;
 *  - touch обновляет lastUsedAt;
 *  - изоляция по ownerId.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgentTokenServiceTest {

    @Autowired private AgentTokenService service;
    @Autowired private AgentTokenRepository repo;

    private final String userA = "user-a";
    private final String userB = "user-b";

    @BeforeEach
    void cleanup() {
        repo.deleteAll();
    }

    @Test
    void issue_returnsPlainTokenOnce_andStoresHashOnly() {
        AgentTokenIssued issued = service.issue(userA, "ноутбук");

        // токен есть в ответе, начинается с префикса и достаточно длинный
        assertThat(issued.getToken()).startsWith("agt_");
        assertThat(issued.getToken().length()).isGreaterThan(30);

        // в БД лежит ровно одна запись
        List<AgentToken> all = repo.findAll();
        assertThat(all).hasSize(1);

        AgentToken stored = all.get(0);
        // в БД хранится не plain text, а SHA-256
        assertThat(stored.getTokenHash()).isNotEqualTo(issued.getToken());
        assertThat(stored.getTokenHash()).hasSize(64);     // hex SHA-256
        assertThat(stored.getOwnerId()).isEqualTo(userA);
        assertThat(stored.getName()).isEqualTo("ноутбук");
        assertThat(stored.isRevoked()).isFalse();
        // суффикс совпадает с последними символами токена
        assertThat(issued.getToken()).endsWith(stored.getTokenSuffix());
    }

    @Test
    void issue_blankNameDefaultsToAgent() {
        AgentTokenIssued issued = service.issue(userA, "   ");
        assertThat(issued.getInfo().getName()).isEqualTo("agent");
    }

    @Test
    void issue_truncatesLongName() {
        String longName = "a".repeat(200);
        AgentTokenIssued issued = service.issue(userA, longName);
        assertThat(issued.getInfo().getName().length()).isLessThanOrEqualTo(64);
    }

    @Test
    void findActiveByToken_returnsOwnerForValidToken() {
        AgentTokenIssued issued = service.issue(userA, "ноутбук");

        Optional<AgentToken> found = service.findActiveByToken(issued.getToken());
        assertThat(found).isPresent();
        assertThat(found.get().getOwnerId()).isEqualTo(userA);
    }

    @Test
    void findActiveByToken_rejectsWrongPrefix() {
        // токен без префикса agt_ — это JWT, не agent-токен
        Optional<AgentToken> found = service.findActiveByToken("eyJfake.jwt.value");
        assertThat(found).isEmpty();
    }

    @Test
    void findActiveByToken_rejectsRevokedToken() {
        AgentTokenIssued issued = service.issue(userA, "test");
        service.revoke(issued.getInfo().getId(), userA, false);

        Optional<AgentToken> found = service.findActiveByToken(issued.getToken());
        assertThat(found).isEmpty();
    }

    @Test
    void findActiveByToken_rejectsTamperedToken() {
        AgentTokenIssued issued = service.issue(userA, "test");
        // меняем 1 символ в середине → SHA-256 меняется → не находится
        String tampered = issued.getToken().substring(0, 10) + "Z" + issued.getToken().substring(11);
        Optional<AgentToken> found = service.findActiveByToken(tampered);
        assertThat(found).isEmpty();
    }

    @Test
    void list_returnsOnlyOwnTokens() {
        service.issue(userA, "a-laptop");
        service.issue(userA, "a-desktop");
        service.issue(userB, "b-laptop");

        List<AgentTokenInfo> userATokens = service.list(userA);
        assertThat(userATokens).hasSize(2);
        assertThat(userATokens).extracting(AgentTokenInfo::getName)
                .containsExactlyInAnyOrder("a-laptop", "a-desktop");

        List<AgentTokenInfo> userBTokens = service.list(userB);
        assertThat(userBTokens).hasSize(1);
        assertThat(userBTokens.get(0).getName()).isEqualTo("b-laptop");
    }

    @Test
    void revoke_marksTokenAsRevokedButKeepsRecord() {
        AgentTokenIssued issued = service.issue(userA, "test");
        boolean ok = service.revoke(issued.getInfo().getId(), userA, false);

        assertThat(ok).isTrue();
        assertThat(repo.findAll()).hasSize(1);
        assertThat(repo.findById(issued.getInfo().getId()).orElseThrow().isRevoked()).isTrue();
    }

    @Test
    void revoke_rejectsForeignTokenForNonAdmin() {
        AgentTokenIssued issued = service.issue(userA, "test");

        // userB пытается отозвать токен userA
        boolean ok = service.revoke(issued.getInfo().getId(), userB, false);
        assertThat(ok).isFalse();
        assertThat(repo.findById(issued.getInfo().getId()).orElseThrow().isRevoked()).isFalse();
    }

    @Test
    void revoke_allowsAdminToRevokeAnyToken() {
        AgentTokenIssued issued = service.issue(userA, "test");
        boolean ok = service.revoke(issued.getInfo().getId(), userB, true); // admin
        assertThat(ok).isTrue();
    }

    @Test
    void revoke_returnsFalseForNonExistent() {
        boolean ok = service.revoke("does-not-exist", userA, false);
        assertThat(ok).isFalse();
    }

    @Test
    void touch_updatesLastUsedAt() {
        AgentTokenIssued issued = service.issue(userA, "test");
        assertThat(repo.findById(issued.getInfo().getId()).orElseThrow().getLastUsedAt()).isNull();

        service.touch(issued.getInfo().getId());

        AgentToken after = repo.findById(issued.getInfo().getId()).orElseThrow();
        assertThat(after.getLastUsedAt()).isNotNull();
    }

    @Test
    void issue_blankOwnerIdRejected() {
        assertThatThrownBy(() -> service.issue("", "test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.issue(null, "test"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issue_twoTokensHaveDifferentValues() {
        AgentTokenIssued t1 = service.issue(userA, "first");
        AgentTokenIssued t2 = service.issue(userA, "second");
        assertThat(t1.getToken()).isNotEqualTo(t2.getToken());
        assertThat(t1.getInfo().getId()).isNotEqualTo(t2.getInfo().getId());
    }
}
