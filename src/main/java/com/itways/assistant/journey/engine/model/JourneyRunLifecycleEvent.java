package com.itways.assistant.journey.engine.model;

import java.util.Date;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Durable run lifecycle event emitted by the engine. Hosts persist these
 * idempotently keyed by {@link #executionId}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JourneyRunLifecycleEvent {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_ERROR = "ERROR";

    private String executionId;
    private String parentExecutionId;
    private String rootExecutionId;
    private Long journeyId;
    private String accountId;
    private String triggerIntent;
    private String status;
    private Date startedAt;
    private Date completedAt;
    private Long durationMs;
    private String userId;
    private String message;
    private List<Map<String, Object>> stepLogs;
    private Map<String, Object> variables;
    private Map<String, Object> stepResults;
}
