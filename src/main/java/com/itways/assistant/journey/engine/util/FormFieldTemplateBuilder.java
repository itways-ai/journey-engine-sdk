package com.itways.assistant.journey.engine.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Converts USER_INPUT form field definitions into a DataMapped jsonTemplate array.
 */
@Component
@RequiredArgsConstructor
public class FormFieldTemplateBuilder {

    private final ObjectMapper objectMapper;

    public String buildJsonTemplate(Object fieldsObj) {
        List<Map<String, Object>> templateFields = new ArrayList<>();
        if (fieldsObj == null) {
            return "[]";
        }

        List<?> fields = fieldsObj instanceof List<?> list ? list : List.of();
        for (Object item : fields) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> field = (Map<String, Object>) raw;
            String name = stringVal(field.get("name"));
            if (name == null || name.isBlank()) {
                continue;
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field", name);
            entry.put("type", mapFieldType(stringVal(field.get("type"))));
            entry.put("hint", buildHint(field));

            String options = buildOptions(field);
            if (options != null && !options.isBlank()) {
                entry.put("options", options);
            }
            templateFields.add(entry);
        }

        try {
            return objectMapper.writeValueAsString(templateFields);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String buildHint(Map<String, Object> field) {
        String label = stringVal(field.get("label"));
        String placeholder = stringVal(field.get("placeholder"));
        if (label != null && placeholder != null && !placeholder.isBlank()) {
            return label + " — " + placeholder;
        }
        if (label != null && !label.isBlank()) {
            return label;
        }
        return placeholder != null ? placeholder : nameFallback(field);
    }

    private String nameFallback(Map<String, Object> field) {
        return stringVal(field.get("name"));
    }

    private String buildOptions(Map<String, Object> field) {
        Object optionsObj = field.get("options");
        if (!(optionsObj instanceof List<?> options)) {
            return null;
        }
        return options.stream()
                .map(opt -> {
                    if (opt instanceof Map<?, ?> optMap) {
                        Object value = optMap.get("value");
                        if (value != null) {
                            return value.toString();
                        }
                        Object label = optMap.get("label");
                        return label != null ? label.toString() : null;
                    }
                    return opt != null ? opt.toString() : null;
                })
                .filter(v -> v != null && !v.isBlank())
                .collect(Collectors.joining(","));
    }

    private String mapFieldType(String feType) {
        if (feType == null) {
            return "text";
        }
        return switch (feType.toLowerCase()) {
            case "number" -> "number";
            case "checkbox" -> "boolean";
            case "dropdown", "radio" -> "enum";
            default -> "text";
        };
    }

    private String stringVal(Object value) {
        return value != null ? value.toString() : null;
    }
}
