package com.itways.assistant.journey.engine.handler;

import java.util.HashMap;
import java.util.Map;
import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class CodeScriptStepHandler implements StepHandler {

    private final EngineUtils engineUtils;
    private final ObjectMapper objectMapper;

    @Override
    public String getType() {
        return "CODE_SCRIPT";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            ApiConfig config = loadApiConfig(step.getApiConfig());
            String code = config.getCode();
            
            if (code == null || code.isEmpty()) {
                return StepResult.error("Script code is missing");
            }

            log.info("📜 Code Script Step: '{}' - Executing custom logic", step.getStepName());

            // Bind current context variables to the script environment
            Map<String, Object> variables = new HashMap<>(context.getVariables());
            
            Object resultValue = null;
            
            try {
                ScriptEngineManager manager = new ScriptEngineManager();
                ScriptEngine engine = manager.getEngineByName("js");
                
                if (engine != null) {
                    Bindings bindings = engine.createBindings();
                    bindings.putAll(variables);
                    resultValue = engine.eval(code, bindings);
                    
                    // Allow script to modify context variables if it returns a map
                    if (resultValue instanceof Map) {
                        context.getVariables().putAll((Map) resultValue);
                    }
                } else {
                    // Fallback Logic Proxy: Support basic placeholder replacement if no engine
                    log.warn("Script engine 'js' not found. Falling back to Logic Proxy.");
                    resultValue = engineUtils.replacePlaceholders(code, context.getVariables());
                }
            } catch (Exception e) {
                log.warn("Execution error in script: {}. Falling back to default result.", e.getMessage());
                resultValue = "TRANSFORMATION_ERROR";
            }

            String safeName = engineUtils.sanitizeKey(step.getStepName());
            context.setVariable(safeName, resultValue);
            
            return StepResult.success(resultValue, step.getMessage());
        } catch (Exception e) {
            log.error("❌ Code Script failed", e);
            return StepResult.error("Script Execution Failure: " + e.getMessage());
        }
    }

    private ApiConfig loadApiConfig(String json) {
        try {
            if (json == null || json.isEmpty()) return new ApiConfig();
            return objectMapper.readValue(json, ApiConfig.class);
        } catch (Exception e) {
            return new ApiConfig();
        }
    }
}
