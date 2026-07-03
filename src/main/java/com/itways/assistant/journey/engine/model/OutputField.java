package com.itways.assistant.journey.engine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutputField {
    private String path;
    private String label;
    private String type;
    private boolean dynamic;
    private List<String> platforms;

    public static OutputField of(String path, String label, String type) {
        return OutputField.builder().path(path).label(label).type(type).build();
    }

    public static OutputField dynamic(String path, String label, String type) {
        return OutputField.builder().path(path).label(label).type(type).dynamic(true).build();
    }
}
