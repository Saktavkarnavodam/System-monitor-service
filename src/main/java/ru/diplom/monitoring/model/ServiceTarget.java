package ru.diplom.monitoring.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Произвольный сервис на узле: человекочитаемое имя + TCP-порт.
 * Сервисы хранятся как список внутри {@link Node#getServices()} (JSON-столбец),
 * и каждый прозванивается при probe — так одна машина может одновременно
 * мониторить несколько сервисов (web + db + cache и т.д.).
 *
 * <p>Probe — это просто TCP-handshake, ему всё равно что за протокол на порту.
 * Достаточно, чтобы порт принимал TCP-соединение.
 */
public class ServiceTarget {

    @Schema(description = "Человекочитаемое имя, например: 'web', 'postgres', 'redis'", example = "web")
    private String name;

    @Schema(description = "TCP-порт сервиса на этом узле", example = "8080")
    private int port;

    public ServiceTarget() {}

    public ServiceTarget(String name, int port) {
        this.name = name;
        this.port = port;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
}
