package com.itways.assistant.journey.engine.handler;

import java.util.HashMap;
import java.util.Map;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import org.springframework.stereotype.Component;

import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepDefinition;
import com.itways.assistant.journey.engine.model.StepOutputSchema;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class CodeScriptStepHandler implements StepHandler {

    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;

    @Override
    public String getType() {
        return "CODE_SCRIPT";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.codeScriptDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.genericOutputSchema("CODE_SCRIPT", "Script Result");
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            ApiConfig config = engineUtils.parseApiConfig(step.getApiConfig());
            String code = config.getCode();
            if (code == null || code.isEmpty()) {
                return StepResult.error("Script code is missing");
            }

            Map<String, Object> variables = new HashMap<>(context.getVariables());
            Object resultValue = null;

            try {
                ScriptEngineManager manager = new ScriptEngineManager();
                ScriptEngine engine = manager.getEngineByName("js");
                if (engine != null) {
                    Bindings bindings = engine.createBindings();
                    bindings.putAll(variables);
                    resultValue = engine.eval(code, bindings);
                } else {
                    resultValue = engineUtils.replacePlaceholders(code, context.getVariables());
                }
            } catch (Exception e) {
                log.warn("Execution error in script: {}", e.getMessage());
                resultValue = "TRANSFORMATION_ERROR";
            }

            variableContext.storeOutput(context, step, resultValue);
            return StepResult.success(resultValue, step.getMessage());
        } catch (Exception e) {
            log.error("Code Script failed", e);
            return StepResult.error("Script Execution Failure: " + e.getMessage());
        }
    }

}
