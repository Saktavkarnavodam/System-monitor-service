package ru.diplom.monitoring.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import ru.diplom.monitoring.model.Role;
import ru.diplom.monitoring.model.User;
import ru.diplom.monitoring.repository.UserRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserServiceTest {

    @Autowired private UserService userService;
    @Autowired private UserRepository repo;

    @BeforeEach
    void cleanup() {
        repo.deleteAll();
    }

    @Test
    void register_createsUserWithHashedPassword() {
        User u = userService.register("alice", "secret123");
        assertThat(u.getId()).isNotBlank();
        assertThat(u.getUsername()).isEqualTo("alice");
        assertThat(u.getPasswordHash()).isNotEqualTo("secret123");
        assertThat(u.getRole()).isEqualTo(Role.USER);
        assertThat(u.getCreatedAt()).isNotNull();
    }

    @Test
    void register_failsOnDuplicate() {
        userService.register("alice", "x");
        assertThatThrownBy(() -> userService.register("alice", "y"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void register_caseInsensitiveUsername() {
        userService.register("Alice", "x");
        assertThatThrownBy(() -> userService.register("alice", "y"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> userService.register("ALICE", "y"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void checkPassword_acceptsCorrectAndRejectsWrong() {
        User u = userService.register("bob", "topsecret");
        assertThat(userService.checkPassword(u, "topsecret")).isTrue();
        assertThat(userService.checkPassword(u, "wrong")).isFalse();
    }

    @Test
    void findByUsername_returnsUser() {
        userService.register("carol", "x");
        Optional<User> found = userService.findByUsername("CAROL");
        assertThat(found).isPresent();
        assertThat(found.get().getUsername()).isEqualToIgnoringCase("carol");
    }
}
