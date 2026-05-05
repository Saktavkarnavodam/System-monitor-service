package ru.diplom.monitoring.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

@Schema(description = "Запрос на регистрацию узла распределённой системы")
public class RegisterNodeRequest {

    @NotBlank
    @Schema(example = "order-service-01", description = "Имя узла")
    private String name;

    @Schema(example = "10.0.1.42")
    private String host;

    @Schema(example = "8080")
    private Integer port;

    @Schema(example = "service", description = "Тип узла: service, database, worker, gateway, и т.д.")
    private String type;

    @Schema(description = "Произвольные теги узла")
    private Map<String, String> tags;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
