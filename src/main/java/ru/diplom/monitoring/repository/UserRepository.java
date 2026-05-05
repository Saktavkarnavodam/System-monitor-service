package ru.diplom.monitoring.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.diplom.monitoring.model.User;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);
}
