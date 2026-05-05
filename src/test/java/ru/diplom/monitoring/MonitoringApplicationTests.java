package ru.diplom.monitoring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class MonitoringApplicationTests {

    @Test
    void contextLoads() {
        // Smoke-тест: просто проверяет, что Spring-контекст со всеми бинами,
        // безопасностью, JPA и репозиториями стартует без ошибок.
    }
}
