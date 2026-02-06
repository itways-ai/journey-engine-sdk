package com.itways.assistant.journey.engine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JourneyStep {
    private Long id;
    private int stepOrder;
    private String stepName;
    private String actionType;
    private String actionTarget;
    private String requiredParams;
    private String apiConfig; // JSON configuration for generic API calls
    private boolean continueOnError;

    public String getActionType() {
        return actionType;
    }

    public String getActionTarget() {
        return actionTarget;
    }

    public String getRequiredParams() {
        return requiredParams;
    }

    public boolean isContinueOnError() {
        return continueOnError;
    }

    private String conditionExpression;
    private String branchName;
    private Integer parentOrder;
    private String message;
    @Builder.Default
    private Boolean clientVisible = true; // Visibility in Chat UI

    public boolean isClientVisible() {
        return clientVisible == null || clientVisible;
    }
}
