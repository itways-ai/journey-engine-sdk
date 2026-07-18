package com.itways.assistant.journey.engine.handler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;

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
            if (code == null || code.isBlank()) {
                return StepResult.error("CODE_SCRIPT: script code is required");
            }

            // JSON round-trip so scripts see plain JS objects (steps['3'].output.status)
            // instead of Java Maps that do not support JS property access.
            Map<String, Object> variables = new HashMap<>(context.getVariables());
            String ctxJson = objectMapper.writeValueAsString(variables);

            Object resultValue;
            try (Context js = Context.newBuilder("js")
                    .allowHostAccess(HostAccess.ALL)
                    .option("engine.WarnInterpreterOnly", "false")
                    .allowExperimentalOptions(true)
                    .build()) {
                js.getBindings("js").putMember("__ctxJson", ctxJson);
                // Seed VariableContext roots as plain JS locals shared across evals.
                js.eval("js", """
                        var __ctx = JSON.parse(__ctxJson);
                        var inputs = __ctx.inputs;
                        var steps = __ctx.steps;
                        var state = __ctx.state;
                        var channel = __ctx.channel;
                        var runtime = __ctx.runtime;
                        """);
                // Top-level script completion value (last expression) is returned —
                // same semantics journey authors expect from ScriptEngine.eval.
                Value result = js.eval("js", code);
                resultValue = valueToJava(result);
            }

            variableContext.storeOutput(context, step, resultValue);
            return StepResult.success(resultValue, step.getMessage());
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.toString();
            log.error("CODE_SCRIPT execution failed for step '{}': {}", step.getStepName(), detail, e);
            return StepResult.error("CODE_SCRIPT execution failed: " + detail);
        }
    }

    private static Object valueToJava(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            if (value.fitsInInt()) {
                return value.asInt();
            }
            if (value.fitsInLong()) {
                return value.asLong();
            }
            return value.asDouble();
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.hasArrayElements()) {
            int size = (int) value.getArraySize();
            List<Object> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                list.add(valueToJava(value.getArrayElement(i)));
            }
            return list;
        }
        if (value.hasMembers()) {
            Map<String, Object> map = new HashMap<>();
            for (String key : value.getMemberKeys()) {
                map.put(key, valueToJava(value.getMember(key)));
            }
            return map;
        }
        return value.toString();
    }

}
