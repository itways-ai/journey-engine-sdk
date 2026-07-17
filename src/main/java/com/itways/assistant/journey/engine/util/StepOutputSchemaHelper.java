package com.itways.assistant.journey.engine.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.OutputField;
import com.itways.assistant.journey.engine.model.StepDefinition;
import com.itways.assistant.journey.engine.model.StepOutputSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class StepOutputSchemaHelper {

    private static final String HGI_PREFIX = "hgi hgi-stroke hgi-rounded ";

    private final ObjectMapper objectMapper;

    public StepOutputSchemaHelper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Full Hugeicons CSS class string for catalog consumers (Angular builder). */
    public static String hgiIcon(String slug) {
        if (slug == null || slug.isBlank()) {
            return HGI_PREFIX + "hgi-ai-chip-02";
        }
        if (slug.startsWith("hgi ")) {
            return slug;
        }
        String name = slug.startsWith("hgi-") ? slug : "hgi-" + slug;
        return HGI_PREFIX + name;
    }

    public StepDefinition apiCallDefinition() {
        return StepDefinition.builder()
                .type("API_CALL")
                .category("system")
                .label("API Call")
                .icon(hgiIcon("hgi-cloud-02"))
                .uiComponent("step-api")
                .build();
    }

    public StepDefinition userInputDefinition() {
        return StepDefinition.builder()
                .type("USER_INPUT")
                .category("interaction")
                .label("User Input")
                .icon(hgiIcon("hgi-user-edit-01"))
                .uiComponent("step-user-input")
                .waitsForInput(true)
                .build();
    }

    public StepDefinition responseDefinition() {
        return StepDefinition.builder()
                .type("RESPONSE")
                .category("interaction")
                .label("Response")
                .icon(hgiIcon("hgi-message-01"))
                .uiComponent("step-response")
                .build();
    }

    public StepOutputSchema apiCallSchema(JourneyStep step) {
        List<OutputField> fields = new ArrayList<>(List.of(
                OutputField.of("output", "Response Body", "object"),
                OutputField.of("status", "HTTP Status", "number"),
                OutputField.of("headers", "Response Headers", "object")));
        fields.addAll(parseDiscoveredVariables(step));
        return StepOutputSchema.builder().stepType("API_CALL").fields(fields).build();
    }

    public StepOutputSchema userInputSchema(JourneyStep step) {
        return StepOutputSchema.builder()
                .stepType("USER_INPUT")
                .fields(List.of(OutputField.of("output", "User Answer", "string")))
                .build();
    }

    public StepOutputSchema responseSchema(JourneyStep step) {
        return StepOutputSchema.builder()
                .stepType("RESPONSE")
                .fields(List.of(OutputField.of("output", "Response Text", "string")))
                .build();
    }

    public StepOutputSchema conditionSchema() {
        return StepOutputSchema.builder()
                .stepType("CONDITION")
                .fields(List.of(OutputField.of("output", "Condition Result", "boolean")))
                .build();
    }

    public StepOutputSchema switchSchema() {
        return StepOutputSchema.builder()
                .stepType("SWITCH")
                .fields(List.of(OutputField.of("output", "Switch Value", "string")))
                .build();
    }

    public StepOutputSchema knowledgeRetrievalSchema() {
        return StepOutputSchema.builder()
                .stepType("KNOWLEDGE_RETRIEVAL")
                .fields(List.of(
                        OutputField.of("output", "Retrieved Answer", "string"),
                        OutputField.of("found", "Knowledge Found", "boolean")))
                .build();
    }

    public StepOutputSchema stateStoreSchema(JourneyStep step) {
        ApiConfig config = loadApiConfig(step.getApiConfig());
        List<OutputField> fields = new ArrayList<>();
        if (config.getVariable() != null && !config.getVariable().isBlank()) {
            fields.add(OutputField.of("state." + config.getVariable(), "Stored Value", "string"));
        }
        return StepOutputSchema.builder()
                .stepType("STATE_STORE")
                .fields(fields)
                .writesToState(true)
                .build();
    }

    public StepOutputSchema dataMapSchema(JourneyStep step) {
        List<OutputField> fields = new ArrayList<>();
        ApiConfig config = loadApiConfig(step.getApiConfig());
        if (config.getFields() instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && map.get("targetField") != null) {
                    fields.add(OutputField.dynamic(
                            "output." + map.get("targetField"),
                            String.valueOf(map.get("targetField")),
                            "string"));
                }
            }
        }
        return StepOutputSchema.builder().stepType("DATA_MAP").fields(fields).build();
    }

    public StepOutputSchema genericOutputSchema(String stepType, String label) {
        return StepOutputSchema.builder()
                .stepType(stepType)
                .fields(List.of(OutputField.of("output", label, "string")))
                .build();
    }

    public StepDefinition genericDefinition(String type, String category, String label, String iconSlug, String uiComponent) {
        return StepDefinition.builder()
                .type(type)
                .category(category)
                .label(label)
                .icon(hgiIcon(iconSlug))
                .uiComponent(uiComponent)
                .build();
    }

    public StepDefinition conditionDefinition() {
        return genericDefinition("CONDITION", "logic", "Condition", "hgi-git-branch", "step-condition");
    }

    public StepDefinition switchDefinition() {
        return genericDefinition("SWITCH", "logic", "Switch", "hgi-shuffle-01", "step-switch");
    }

    public StepDefinition jumpDefinition() {
        return genericDefinition("JUMP", "logic", "Jump", "hgi-route-01", "step-jump");
    }

    public StepDefinition delayDefinition() {
        return genericDefinition("DELAY", "logic", "Delay", "hgi-clock-01", "step-delay");
    }

    public StepDefinition knowledgeDefinition() {
        return genericDefinition("KNOWLEDGE_RETRIEVAL", "ai", "Knowledge Retrieval", "hgi-book-open-01", "step-knowledge");
    }

    public StepDefinition documentInsightDefinition() {
        return genericDefinition("DOCUMENT_INSIGHT", "ai", "Document Insight", "hgi-file-search-01", "step-document");
    }

    public StepDefinition templateRenderDefinition() {
        return genericDefinition("TEMPLATE_RENDER", "ai", "Template Render", "hgi-magic-wand-01", "step-template");
    }

    public StepDefinition dataMapDefinition() {
        return genericDefinition("DATA_MAP", "system", "Data Map", "hgi-database-01", "step-data-map");
    }

    public StepDefinition codeScriptDefinition() {
        return genericDefinition("CODE_SCRIPT", "system", "Code Script", "hgi-source-code-square", "step-code");
    }

    public StepDefinition stateStoreDefinition() {
        StepDefinition def = genericDefinition("STATE_STORE", "system", "State Store", "hgi-database-setting", "step-state");
        def.setWritesToState(true);
        return def;
    }

    public StepDefinition humanApprovalDefinition() {
        StepDefinition def = genericDefinition("HUMAN_APPROVAL", "interaction", "Human Approval", "hgi-user-check-01", "step-approval");
        def.setWaitsForInput(true);
        return def;
    }

    public StepDefinition triggerJourneyDefinition() {
        return genericDefinition("TRIGGER_JOURNEY", "logic", "Trigger Journey", "hgi-flash", "step-trigger");
    }

    public StepDefinition sendMailDefinition() {
        return genericDefinition("SEND_MAIL", "interaction", "Send Mail", "hgi-mail-send-01", "step-mail");
    }

    private List<OutputField> parseDiscoveredVariables(JourneyStep step) {
        List<OutputField> fields = new ArrayList<>();
        ApiConfig config = loadApiConfig(step.getApiConfig());
        Object discovered = config.getDiscoveredVariables();
        if (discovered == null) {
            return fields;
        }
        try {
            List<Map<String, Object>> vars = objectMapper.convertValue(discovered, new TypeReference<>() {});
            for (Map<String, Object> v : vars) {
                String path = v.get("path") != null ? String.valueOf(v.get("path")) : null;
                if (path == null || path.isBlank()) {
                    continue;
                }
                String fullPath = path.startsWith("output.") ? path : "output." + path;
                String label = v.get("label") != null ? String.valueOf(v.get("label")) : path;
                fields.add(OutputField.dynamic(fullPath, label, "string"));
            }
        } catch (Exception ignored) {
        }
        return fields;
    }

    private ApiConfig loadApiConfig(String json) {
        try {
            if (json == null || json.isEmpty()) {
                return new ApiConfig();
            }
            return objectMapper.readValue(json, ApiConfig.class);
        } catch (Exception e) {
            return new ApiConfig();
        }
    }
}
