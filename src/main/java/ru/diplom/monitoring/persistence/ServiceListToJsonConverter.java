package ru.diplom.monitoring.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import ru.diplom.monitoring.model.ServiceTarget;

import java.util.ArrayList;
import java.util.List;

/**
 * JPA-конвертер List&lt;ServiceTarget&gt; &lt;-&gt; JSON-строка.
 * Используется, чтобы хранить список произвольных сервисов узла (web :80,
 * postgres :5432, …) в одной колонке без отдельной таблицы.
 */
@Converter
public class ServiceListToJsonConverter implements AttributeConverter<List<ServiceTarget>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<ServiceTarget>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<ServiceTarget> attribute) {
        if (attribute == null || attribute.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сериализовать services в JSON", e);
        }
    }

    @Override
    public List<ServiceTarget> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
