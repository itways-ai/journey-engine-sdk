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

    /**
     * Variables this journey is allowed to leave in conversation memory.
     *
     * <p>
     * Opt-in, and deliberately not "remember everything the run produced". The
     * variable map holds whatever every step fetched from every upstream system,
     * and the run result already strips it before the reply leaves the service
     * for exactly that reason; copying it wholesale into a store that is then
     * replayed into an LLM prompt would undo that in one line.
     *
     * <p>
     * Each entry is {@code [profile:][name=]path}:
     *
     * <ul>
     * <li>{@code inputs.entities.projectId} — remembered under its last segment,
     * {@code projectId}.
     * <li>{@code openTasks=steps.3.output.count} — an explicit name, which is
     * what step-output paths want: {@code count} is a true label and a useless
     * one to a model reading it a turn later.
     * <li>{@code profile:team=steps.2.output.team} — promoted to the end user's
     * durable profile instead of expiring with the conversation, which is how
     * "my team is Platform" outlives the session that established it.
     * </ul>
     *
     * <p>
     * The profile marker is a colon rather than a dot because a dot is already
     * the path separator: {@code profile.team} could not be told apart from a
     * variable genuinely nested under {@code profile}.
     *
     * <p>
     * Null or empty means this journey contributes no structured facts. Its
     * reply text is still remembered — that comes from the turn log and needs no
     * authoring.
     */
    private List<String> memoryKeys;

    private List<JourneyStep> steps;
}
