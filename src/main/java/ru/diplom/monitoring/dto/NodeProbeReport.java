package ru.diplom.monitoring.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * Полный отчёт probe узла: возраст heartbeat + результаты TCP-handshake
 * по каждому сервису. По одному узлу теперь может мониториться несколько
 * сервисов (web + db + cache) — каждый сервис прозванивается отдельно
 * и попадает отдельной строкой в {@link #services}.
 *
 * <p>Если у узла нет ни одного сервиса, {@link #services} пустой — UI
 * показывает только heartbeat-вердикт, без TCP-диагностики.
 */
public class NodeProbeReport {

    @Schema(description = "Сколько секунд прошло с последнего heartbeat (null если ни разу не было)")
    private Long heartbeatAgeSeconds;

    @Schema(description = "Результаты TCP-probe по каждому сервису узла")
    private List<ServiceProbe> services = new ArrayList<>();

    public NodeProbeReport() {}

    public Long getHeartbeatAgeSeconds() { return heartbeatAgeSeconds; }
    public void setHeartbeatAgeSeconds(Long v) { this.heartbeatAgeSeconds = v; }

    public List<ServiceProbe> getServices() { return services; }
    public void setServices(List<ServiceProbe> v) { this.services = v; }

    /** Результат probe одного конкретного сервиса. */
    public static class ServiceProbe {
        @Schema(description = "Имя сервиса (как задано пользователем)", example = "web")
        private String name;
        @Schema(description = "Порт", example = "8080")
        private int port;
        @Schema(description = "Удалось ли открыть TCP-сокет")
        private boolean reachable;
        @Schema(description = "Задержка TCP-handshake в мс (если reachable=true)")
        private Long latencyMs;
        @Schema(description = "Текст ошибки, если reachable=false")
        private String error;

        public ServiceProbe() {}

        public static ServiceProbe ok(String name, int port, long latencyMs) {
            ServiceProbe r = new ServiceProbe();
            r.name = name; r.port = port; r.reachable = true; r.latencyMs = latencyMs;
            return r;
        }

        public static ServiceProbe fail(String name, int port, String error) {
            ServiceProbe r = new ServiceProbe();
            r.name = name; r.port = port; r.reachable = false; r.error = error;
            return r;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public boolean isReachable() { return reachable; }
        public void setReachable(boolean reachable) { this.reachable = reachable; }
        public Long getLatencyMs() { return latencyMs; }
        public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }
}
