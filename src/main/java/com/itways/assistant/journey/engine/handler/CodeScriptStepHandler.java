package com.itways.assistant.journey.engine.handler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import org.springframework.stereotype.Component;
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

    private static final Set<String> EXCLUDED_SCRIPT_KEYS = Set.of(
            "polyglot.js.allowHostAccess",
            "polyglot.js.allowHostClassLookup");
    private static final Pattern JS_IDENTIFIER = Pattern.compile("^[a-zA-Z_$][a-zA-Z0-9_$]*$");

    private final EngineUtils engineUtils;

    @Override
    public String getType() {
        return "CODE_SCRIPT";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            ApiConfig config = engineUtils.parseApiConfig(step.getApiConfig());
            String code = config.getCode();

            if (code == null || code.isBlank()) {
                return StepResult.error("CODE_SCRIPT: script code is required");
            }

            log.info("CODE_SCRIPT step '{}' — executing custom logic", step.getStepName());

            ScriptEngineManager manager = new ScriptEngineManager();
            ScriptEngine engine = manager.getEngineByName("js");

            if (engine == null) {
                log.error("CODE_SCRIPT: no JavaScript engine found on the classpath (Java 21+ requires GraalVM JS)");
                return StepResult.error("CODE_SCRIPT: JavaScript engine is not available. Add GraalVM JS to the classpath.");
            }

            Map<String, Object> variables = buildScriptVariables(context.getVariables());
            Object resultValue;

            try {
                List<String> paramNames = variables.keySet().stream()
                        .filter(name -> !EXCLUDED_SCRIPT_KEYS.contains(name))
                        .filter(CodeScriptStepHandler::isJavaScriptIdentifier)
                        .sorted()
                        .toList();

                Bindings bindings = engine.createBindings();
                bindings.put("polyglot.js.allowHostAccess", true);
                bindings.put("polyglot.js.allowHostClassLookup", (Predicate<String>) clazz -> false);
                bindings.putAll(variables);
                resultValue = engine.eval(buildExecutableScript(paramNames, code), bindings);

                if (resultValue instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> resultMap = (Map<String, Object>) resultValue;
                    context.getVariables().putAll(resultMap);
                }
            } catch (Exception e) {
                log.error("CODE_SCRIPT execution error ({}): {}", e.getClass().getSimpleName(), e.getMessage(), e);
                return StepResult.error("CODE_SCRIPT execution failed: " + e.getMessage());
            }

            log.info("CODE_SCRIPT step '{}' result: {}", step.getStepName(), resultValue);

            String safeName = engineUtils.sanitizeKey(step.getStepName());
            context.setVariable(safeName, resultValue);
            context.setVariable("step" + step.getStepOrder(), resultValue);
            context.setVariable("lastStep", resultValue);

            String resolvedMessage = step.getMessage() != null && !step.getMessage().isBlank()
                    ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
                    : null;

            return StepResult.success(resultValue, resolvedMessage);
        } catch (Exception e) {
            log.error("CODE_SCRIPT failed", e);
            return StepResult.error("CODE_SCRIPT: " + e.getMessage());
        }
    }

    /**
     * Expose map field values (e.g. structured USER_INPUT answers) as top-level script identifiers.
     */
    static Map<String, Object> buildScriptVariables(Map<String, Object> contextVariables) {
        Map<String, Object> scriptVars = new HashMap<>(contextVariables);
        for (Object value : contextVariables.values()) {
            if (value instanceof Map<?, ?> nested) {
                nested.forEach((key, nestedValue) -> {
                    if (key instanceof String name && isJavaScriptIdentifier(name)) {
                        scriptVars.putIfAbsent(name, nestedValue);
                    }
                });
            }
        }
        return scriptVars;
    }

    private static String buildExecutableScript(List<String> paramNames, String code) {
        String params = String.join(", ", paramNames);
        if (paramNames.isEmpty()) {
            return "(function() {\n" + code + "\n})()";
        }
        return "(function(" + params + ") {\n" + code + "\n})(" + params + ")";
    }

    private static boolean isJavaScriptIdentifier(String name) {
        return name != null && JS_IDENTIFIER.matcher(name).matches();
    }

}
