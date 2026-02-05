package com.itways.assistant.journey.engine.model;

import java.util.List;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Journey {
    private Long id;
    private String name;
    private String triggerIntent;
    private String variableMapping;
    private String aiHints;
    private String slug;
    private String uniqueCode;
    private boolean active;
    private List<JourneyStep> steps;
}
