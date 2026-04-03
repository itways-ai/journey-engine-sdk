package com.itways.assistant.journey.engine.handler;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class JumpHandler implements StepHandler {

    @Override
    public String getType() {
        return "JUMP";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            int targetStepOrder = Integer.parseInt(step.getActionTarget());
            
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("targetOrder", targetStepOrder);
            
            return StepResult.builder()
                .status("JUMP")
                .metadata(metadata)
                .message(step.getMessage() != null ? step.getMessage() : "Jumping to step " + targetStepOrder)
                .data(targetStepOrder) // for reference
                .build();
        } catch (NumberFormatException e) {
            return StepResult.error("Invalid JUMP target: " + step.getActionTarget() + ". Must be an integer representing stepOrder.");
        }
    }
}
