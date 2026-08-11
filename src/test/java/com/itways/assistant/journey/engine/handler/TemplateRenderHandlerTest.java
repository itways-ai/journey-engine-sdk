package com.itways.assistant.journey.engine.handler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.model.TemplateRenderResult;
import com.itways.assistant.journey.engine.service.TemplateRenderPort;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;

/**
 * The port is stubbed by hand rather than mocked: Mockito's inline mock maker cannot
 * instrument types under this module's Byte Buddy version on JDK 21, and a one-method
 * interface does not need a mocking framework anyway.
 */
class TemplateRenderHandlerTest {

    private static final String ACCOUNT = "acc-1";

    private StubPort port;
    private VariableContext variableContext;
    private TemplateRenderHandler handler;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        port = new StubPort();
        variableContext = new VariableContext();
        handler = new TemplateRenderHandler(
                variableContext,
                new StepOutputSchemaHelper(objectMapper),
                new EngineUtils(objectMapper),
                port);
    }

    @Test
    @DisplayName("publishes the rendered text as the step output")
    void publishesRenderedOutput() {
        port.result = new TemplateRenderResult("<h1>Hello, Sarah!</h1>", "html", List.of(), null);

        ExecutionContext context = context();
        StepResult result = handler.execute(
                step("42", "{\"bindings\":{\"firstName\":\"{{inputs.entities.name}}\"}}"), context);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(variableContext.read(context, "steps.1.output")).isEqualTo("<h1>Hello, Sarah!</h1>");
        assertThat(variableContext.read(context, "steps.1.contentType")).isEqualTo("html");
        assertThat(port.accountId).isEqualTo(ACCOUNT);
        assertThat(port.templateId).isEqualTo(42L);
    }

    @Test
    @DisplayName("sends only the bound values, resolved from journey variables")
    void sendsResolvedBindings() {
        port.result = ok();

        handler.execute(step("42", "{\"bindings\":{\"firstName\":\"{{inputs.entities.name}}\"}}"), context());

        assertThat(port.model).containsExactly(Map.entry("firstName", "Sarah"));
    }

    @Test
    @DisplayName("keeps a lone placeholder's type instead of stringifying it")
    void preservesBoundValueType() {
        port.result = ok();

        handler.execute(step("42", "{\"bindings\":{\"count\":\"{{inputs.entities.orderCount}}\"}}"), context());

        assertThat(port.model).containsEntry("count", 3);
    }

    @Test
    @DisplayName("carries the content type into step metadata so the timeline can render it")
    void exposesFormatToTheTimeline() {
        port.result = new TemplateRenderResult("# Title", "markdown", List.of(), null);

        StepResult result = handler.execute(step("42", null), context());

        assertThat(result.getMetadata()).containsEntry("format", "markdown");
        assertThat(result.getData()).isEqualTo(Map.of("renderedContent", "# Title"));
    }

    @Test
    @DisplayName("fails when no template is selected")
    void failsWithoutATemplate() {
        StepResult result = handler.execute(step("  ", null), context());

        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getMessage()).contains("no template selected");
        assertThat(port.called).isFalse();
    }

    @Test
    @DisplayName("fails when the target is not a template id")
    void failsOnNonNumericTarget() {
        StepResult result = handler.execute(step("welcome-email", null), context());

        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getMessage()).contains("not a template id");
        assertThat(port.called).isFalse();
    }

    @Test
    @DisplayName("fails when the template itself is broken")
    void failsOnBrokenTemplate() {
        port.result = new TemplateRenderResult(null, null, List.of(), "Unexpected end of file");

        StepResult result = handler.execute(step("42", null), context());

        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getMessage()).contains("Unexpected end of file");
    }

    @Test
    @DisplayName("fails when a template variable had nothing bound to it")
    void failsOnUnboundVariable() {
        port.result = new TemplateRenderResult("Hi ", "html", List.of("lastName"), null);

        StepResult result = handler.execute(step("42", null), context());

        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getMessage()).contains("lastName");
    }

    @Test
    @DisplayName("tolerates an unbound variable when the step opts in")
    void allowsMissingInputsWhenConfigured() {
        port.result = new TemplateRenderResult("Hi ", "html", List.of("lastName"), null);

        StepResult result = handler.execute(step("42", "{\"allowMissingInputs\":true}"), context());

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getMetadata()).containsEntry("unresolvedTemplateVariables", List.of("lastName"));
    }

    @Test
    @DisplayName("reports an unreachable template service as a step error, not a crash")
    void failsWhenServiceUnreachable() {
        port.failure = new RuntimeException("connection refused");

        StepResult result = handler.execute(step("42", null), context());

        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getMessage()).contains("connection refused");
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private static TemplateRenderResult ok() {
        return new TemplateRenderResult("out", "html", List.of(), null);
    }

    private ExecutionContext context() {
        ExecutionContext context = new ExecutionContext();
        context.setAccountId(ACCOUNT);
        variableContext.ensureStructure(context);
        variableContext.mergeInputs(context, Map.of("name", "Sarah", "orderCount", 3));
        return context;
    }

    private JourneyStep step(String actionTarget, String apiConfig) {
        JourneyStep step = new JourneyStep();
        step.setStepOrder(1);
        step.setStepName("Render welcome");
        step.setActionType("TEMPLATE_RENDER");
        step.setActionTarget(actionTarget);
        step.setApiConfig(apiConfig);
        return step;
    }

    private static final class StubPort implements TemplateRenderPort {

        private TemplateRenderResult result;
        private RuntimeException failure;

        private boolean called;
        private String accountId;
        private long templateId;
        private Map<String, Object> model;

        @Override
        public TemplateRenderResult render(String accountId, long templateId, Map<String, Object> model) {
            this.called = true;
            this.accountId = accountId;
            this.templateId = templateId;
            this.model = model;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
