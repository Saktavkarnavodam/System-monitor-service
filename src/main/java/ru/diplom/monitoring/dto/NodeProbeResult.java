package ru.diplom.monitoring.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Результат TCP-probe узла. Используется, чтобы отличить ситуацию
 * «упал агент» от «упал сам узел»:
 *
 * <ul>
 *   <li>heartbeat свежий, probe не нужен — узел в норме;</li>
 *   <li>heartbeat устарел, probe={@code reachable=true} — узел доступен,
 *       но агент молчит. Скорее всего, агент упал или не запущен.</li>
 *   <li>heartbeat устарел, probe={@code reachable=false} — узел не отвечает
 *       вовсе. Скорее всего, упал сам узел или сеть.</li>
 *   <li>port не задан — probe невозможен, {@code probed=false}.</li>
 * </ul>
 */
public class NodeProbeResult {

    @Schema(description = "Удалось ли провести probe (false если порт не задан)")
    private boolean probed;

    @Schema(description = "TCP-сокет успешно открылся")
    private boolean reachable;

    @Schema(description = "Задержка TCP-handshake в миллисекундах (если reachable=true)")
    private Long latencyMs;

    @Schema(description = "Текст ошибки или короткое объяснение, если probed=false / reachable=false")
    private String message;

    @Schema(description = "Сколько секунд прошло с последнего heartbeat (null если heartbeat'а ещё не было)")
    private Long heartbeatAgeSeconds;

    public NodeProbeResult() {}

    public static NodeProbeResult unconfigured(String reason, Long heartbeatAgeSeconds) {
        NodeProbeResult r = new NodeProbeResult();
        r.probed = false;
        r.reachable = false;
        r.message = reason;
        r.heartbeatAgeSeconds = heartbeatAgeSeconds;
        return r;
    }

    public static NodeProbeResult ok(long latencyMs, Long heartbeatAgeSeconds) {
        NodeProbeResult r = new NodeProbeResult();
        r.probed = true;
        r.reachable = true;
        r.latencyMs = latencyMs;
        r.heartbeatAgeSeconds = heartbeatAgeSeconds;
        return r;
    }

    public static NodeProbeResult unreachable(String message, Long heartbeatAgeSeconds) {
        NodeProbeResult r = new NodeProbeResult();
        r.probed = true;
        r.reachable = false;
        r.message = message;
        r.heartbeatAgeSeconds = heartbeatAgeSeconds;
        return r;
    }

    public boolean isProbed() { return probed; }
    public void setProbed(boolean probed) { this.probed = probed; }

    public boolean isReachable() { return reachable; }
    public void setReachable(boolean reachable) { this.reachable = reachable; }

    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Long getHeartbeatAgeSeconds() { return heartbeatAgeSeconds; }
    public void setHeartbeatAgeSeconds(Long heartbeatAgeSeconds) { this.heartbeatAgeSeconds = heartbeatAgeSeconds; }
}
