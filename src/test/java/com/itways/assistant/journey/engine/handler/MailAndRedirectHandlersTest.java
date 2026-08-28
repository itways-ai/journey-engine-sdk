package com.itways.assistant.journey.engine.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.ExecutionStatus;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.MailConfig;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.MailDeliveryPort;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two side-effect notification steps. SEND_MAIL's contract worth guarding
 * is honesty: it used to fabricate "Mail sent to X" with no transport wired at
 * all, and reporting a delivery that never happened is worse than failing.
 * REDIRECT's is reachability: the resolved URL must travel in metadata, the
 * only channel that actually reaches the browser.
 */
@DisplayName("Mail and redirect handlers")
class MailAndRedirectHandlersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VariableContext variableContext = new VariableContext();
    private final EngineUtils engineUtils = new EngineUtils(objectMapper);
    private final StepOutputSchemaHelper schemaHelper = new StepOutputSchemaHelper(objectMapper);

    private ExecutionContext context() {
        ExecutionContext context = ExecutionContext.builder()
                .variables(new HashMap<>()).status(ExecutionStatus.RUNNING).build();
        variableContext.ensureStructure(context);
        variableContext.mergeInputs(context,
                Map.of("email", "sarah@example.com", "name", "Sarah", "orderId", 42));
        return context;
    }

    @Nested
    @DisplayName("SEND_MAIL")
    class Mail {

        private static final String CONFIG = """
                {"smtpHost":"smtp.example.com",
                 "to":"{{inputs.entities.email}}",
                 "subject":"Order {{inputs.entities.orderId}}",
                 "body":"Hi {{inputs.entities.name}}, your order shipped."}
                """;

        private final RecordingMailPort port = new RecordingMailPort();
        private final MailStepHandler handler = new MailStepHandler(
                objectMapper, engineUtils, variableContext, schemaHelper, Optional.of(port));

        private JourneyStep mailStep(String apiConfig) {
            return JourneyStep.builder()
                    .stepOrder(5).stepName("Notify customer").actionType("SEND_MAIL")
                    .apiConfig(apiConfig).build();
        }

        @Test
        @DisplayName("with no transport wired, the step fails instead of faking a delivery")
        void failsWhenNoTransportWired() {
            MailStepHandler unwired = new MailStepHandler(
                    objectMapper, engineUtils, variableContext, schemaHelper, Optional.empty());

            StepResult result = unwired.execute(mailStep(CONFIG), context());

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getMessage()).contains("no mail transport");
        }

        @Test
        @DisplayName("resolves recipient, subject and body from journey variables before handing off")
        void resolvesRecipientSubjectAndBody() {
            ExecutionContext context = context();

            StepResult result = handler.execute(mailStep(CONFIG), context);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(port.to).isEqualTo("sarah@example.com");
            assertThat(port.subject).isEqualTo("Order 42");
            assertThat(port.body).isEqualTo("Hi Sarah, your order shipped.");
            // The per-step SMTP settings ride along untouched for the transport.
            assertThat(port.config.getSmtpHost()).isEqualTo("smtp.example.com");
            assertThat(variableContext.read(context, "steps.5.output"))
                    .isEqualTo("Mail sent to sarah@example.com");
        }

        @Test
        @DisplayName("an unreadable mail configuration fails before anything is sent")
        void failsOnUnreadableConfiguration() {
            StepResult result = handler.execute(mailStep("not json at all"), context());

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getMessage()).contains("unreadable mail configuration");
            assertThat(port.called).isFalse();
        }

        @Test
        @DisplayName("a recipient that resolves to nothing fails rather than mailing nobody")
        void failsWhenRecipientResolvesBlank() {
            StepResult result = handler.execute(
                    mailStep("{\"to\":\"{{inputs.entities.missing}}\",\"subject\":\"s\",\"body\":\"b\"}"),
                    context());

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getMessage()).contains("no recipient");
            assertThat(port.called).isFalse();
        }

        @Test
        @DisplayName("a transport failure is a step ERROR carrying the transport's reason")
        void deliveryFailureIsAStepError() {
            port.failure = new RuntimeException("SMTP down");

            StepResult result = handler.execute(mailStep(CONFIG), context());

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getMessage()).contains("SMTP down");
        }
    }

    @Nested
    @DisplayName("REDIRECT")
    class Redirect {

        private final RedirectStepHandler handler = new RedirectStepHandler(engineUtils, variableContext);

        private JourneyStep redirectStep(String actionTarget, String message) {
            return JourneyStep.builder()
                    .stepOrder(6).stepName("Send to portal").actionType("REDIRECT")
                    .actionTarget(actionTarget).message(message).build();
        }

        @Test
        @DisplayName("publishes the resolved URL everywhere a client might look for it")
        void publishesResolvedUrl() {
            ExecutionContext context = context();

            StepResult result = handler.execute(redirectStep(
                    "https://shop.example/orders/{{inputs.entities.orderId}}",
                    "Taking you to order {{inputs.entities.orderId}}"), context);

            String resolved = "https://shop.example/orders/42";
            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getData()).isEqualTo(resolved);
            assertThat(result.getActionTarget()).isEqualTo(resolved);
            // Metadata is the only channel that reaches the browser — the engine
            // merges it into the client-facing step view but never copies the
            // result's own actionTarget field.
            assertThat(result.getMetadata())
                    .containsEntry(RedirectStepHandler.META_REDIRECT_URL, resolved);
            assertThat(result.getMessage()).isEqualTo("Taking you to order 42");
            assertThat(variableContext.read(context, "steps.6.output")).isEqualTo(resolved);
        }

        @Test
        @DisplayName("fails without a target URL")
        void failsWithoutATarget() {
            StepResult result = handler.execute(redirectStep("  ", null), context());

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getMessage()).contains("actionTarget (URL) is required");
        }

        @Test
        @DisplayName("rejects anything that is not an http(s) URL")
        void rejectsNonHttpSchemes() {
            // A journey variable resolving into the target makes this
            // user-influenced; letting javascript: through would hand the host
            // page an injection primitive.
            StepResult result = handler.execute(redirectStep("javascript:alert(1)", null), context());

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getMessage()).contains("must start with http:// or https://");
        }
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private static final class RecordingMailPort implements MailDeliveryPort {

        private RuntimeException failure;

        private boolean called;
        private MailConfig config;
        private String to;
        private String subject;
        private String body;

        @Override
        public void send(MailConfig smtpConfig, String to, String subject, String body) {
            this.called = true;
            this.config = smtpConfig;
            this.to = to;
            this.subject = subject;
            this.body = body;
            if (failure != null) {
                throw failure;
            }
        }
    }
}
