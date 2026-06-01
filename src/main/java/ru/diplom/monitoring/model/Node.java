package ru.diplom.monitoring.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import ru.diplom.monitoring.persistence.MapToJsonConverter;
import ru.diplom.monitoring.persistence.ServiceListToJsonConverter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "nodes", indexes = {
        // главный фильтр в API: список узлов конкретного пользователя
        @Index(name = "idx_nodes_owner", columnList = "owner_id"),
        // быстрая выборка нездоровых узлов для дашборда
        @Index(name = "idx_nodes_status", columnList = "status"),
        // используется фоновой задачей refreshStatuses, чтобы быстро находить
        // узлы, чей heartbeat устарел
        @Index(name = "idx_nodes_last_heartbeat", columnList = "last_heartbeat")
})
public class Node {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 255)
    private String host;

    @Column
    private Integer port;

    @Column(length = 64)
    private String type;

    @Column(name = "tags_json", columnDefinition = "TEXT")
    @Convert(converter = MapToJsonConverter.class)
    private Map<String, String> tags = new HashMap<>();

    /** Произвольные сервисы на узле (web, db, cache, …). Каждый прозванивается probe-ом. */
    @Column(name = "services_json", columnDefinition = "TEXT")
    @Convert(converter = ServiceListToJsonConverter.class)
    private List<ServiceTarget> services = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NodeStatus status = NodeStatus.UNKNOWN;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "last_heartbeat")
    private Instant lastHeartbeat;

    public Node() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags == null ? new HashMap<>() : tags; }

    public NodeStatus getStatus() { return status; }
    public void setStatus(NodeStatus status) { this.status = status; }

    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }

    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(Instant lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }

    public List<ServiceTarget> getServices() { return services; }
    public void setServices(List<ServiceTarget> services) {
        this.services = services == null ? new ArrayList<>() : services;
    }
}
