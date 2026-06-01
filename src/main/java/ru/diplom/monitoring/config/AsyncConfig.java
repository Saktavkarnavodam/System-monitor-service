package ru.diplom.monitoring.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Включение @Async и пул потоков для AlertNotifier'а.
 * Маленький пул — уведомления не должны конкурировать за общий tomcat-пул.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "notifierExecutor")
    public Executor notifierExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(200);
        ex.setThreadNamePrefix("notifier-");
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(10);
        ex.initialize();
        return ex;
    }
}
