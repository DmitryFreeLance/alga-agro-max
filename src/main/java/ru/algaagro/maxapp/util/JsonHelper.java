package ru.algaagro.maxapp.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class JsonHelper {

    private final ObjectMapper objectMapper;

    public JsonHelper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode objectNode() {
        return objectMapper.createObjectNode();
    }

    public ArrayNode arrayNode() {
        return objectMapper.createArrayNode();
    }

    public JsonNode readTree(String value) {
        try {
            return value == null || value.isBlank() ? objectNode() : objectMapper.readTree(value);
        } catch (JsonProcessingException e) {
            return objectNode();
        }
    }

    public <T> T readValue(String value, TypeReference<T> typeReference, T fallback) {
        try {
            return value == null || value.isBlank() ? fallback : objectMapper.readValue(value, typeReference);
        } catch (JsonProcessingException e) {
            return fallback;
        }
    }

    public String writeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize json", e);
        }
    }

    public List<String> readStringList(String json) {
        return readValue(json, new TypeReference<>() { }, Collections.emptyList());
    }

    public Map<String, Object> readMap(String json) {
        return readValue(json, new TypeReference<>() { }, Collections.emptyMap());
    }
}
