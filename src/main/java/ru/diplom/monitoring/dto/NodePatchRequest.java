package ru.diplom.monitoring.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.diplom.monitoring.model.ServiceTarget;

import java.util.List;

/**
 * Частичное обновление узла из UI: host (адрес машины) + список сервисов.
 * Поля, переданные как {@code null}, не трогаются (PATCH-семантика).
 */
public class NodePatchRequest {

    @Schema(description = "Новый host узла. null = не менять.", example = "192.168.1.10")
    private String host;

    @Schema(description = "Полный новый список сервисов на узле. null = не менять, [] = очистить.")
    private List<ServiceTarget> services;

    public NodePatchRequest() {}

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public List<ServiceTarget> getServices() { return services; }
    public void setServices(List<ServiceTarget> services) { this.services = services; }
}
