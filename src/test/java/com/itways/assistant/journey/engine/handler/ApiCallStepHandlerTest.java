package com.itways.assistant.journey.engine.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.EndUserAuth;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.ExecutionStatus;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API_CALL owns a private RestTemplate, so these tests speak real HTTP
 * to a throwaway server on an ephemeral localhost port instead of mocking the
 * client (Mockito is unusable in this module anyway — see
 * {@link TemplateRenderHandlerTest}). What the server records is the handler's
 * outbound contract; the two behaviors that were real incidents are the typed
 * request body (a stringified integer made every typed host column reject the
 * call) and the end-user token resolving in headers only, never in a URL that
 * gets logged or a body that gets persisted.
 */
@DisplayName("ApiCallStepHandler")
class ApiCallStepHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VariableContext variableContext = new VariableContext();
    private final ApiCallStepHandler handler = new ApiCallStepHandler(
            new EngineUtils(objectMapper), variableContext, new StepOutputSchemaHelper(objectMapper),
            5000, 30000);

    private RecordingServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new RecordingServer();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private ExecutionContext context() {
        ExecutionContext context = ExecutionContext.builder()
                .variables(new HashMap<>()).status(ExecutionStatus.RUNNING).build();
        variableContext.ensureStructure(context);
        variableContext.mergeInputs(context, Map.of("orderId", 42, "traceId", "tr-9"));
        return context;
    }

    private static JourneyStep step(String url, String apiConfig) {
        return JourneyStep.builder()
                .stepOrder(2).stepName("Call host").actionType("API_CALL")
                .actionTarget(url).apiConfig(apiConfig).build();
    }

    @Nested
    @DisplayName("the request it sends")
    class Request {

        @Test
        @DisplayName("interpolates journey variables into the URL")
        void interpolatesUrl() {
            StepResult result = handler.execute(
                    step(server.url("/orders/{{inputs.entities.orderId}}"), null), context());

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(server.method).isEqualTo("GET");
            assertThat(server.uri.getPath()).isEqualTo("/orders/42");
        }

        @Test
        @DisplayName("a lone-placeholder body value keeps its resolved type on the wire")
        void bodyKeepsLonePlaceholderType() throws Exception {
            ExecutionContext context = context();
            // A previous step produced a typed value; the request must carry a
            // JSON number, not "97" — typed host columns reject the string form.
            variableContext.writeStepField(context,
                    JourneyStep.builder().stepOrder(1).build(), "output", Map.of("score", 97));

            handler.execute(step(server.url("/scores"), """
                    {"method":"POST","body":{"score":"{{steps.1.output.score}}",
                                             "note":"Score: {{steps.1.output.score}}"}}
                    """), context);

            assertThat(server.method).isEqualTo("POST");
            @SuppressWarnings("unchecked")
            Map<String, Object> sent = new ObjectMapper().readValue(server.body, Map.class);
            assertThat(sent.get("score")).isInstanceOf(Integer.class).isEqualTo(97);
            // Mixed templates still stringify — the only sensible reading of them.
            assertThat(sent.get("note")).isEqualTo("Score: 97");
        }

        @Test
        @DisplayName("interpolates headers, including the reserved end-user token")
        void interpolatesHeaders() {
            ExecutionContext context = context();
            Map<String, Object> params = new HashMap<>();
            params.put(EndUserAuth.PARAM_USER_TOKEN, "tok-123");
            EndUserAuth.lift(context, params);

            handler.execute(step(server.url("/me"), """
                    {"headers":{"Authorization":"Bearer {{auth.userToken}}",
                                "X-Trace-Id":"{{inputs.entities.traceId}}"}}
                    """), context);

            assertThat(server.headers.getFirst("Authorization")).isEqualTo("Bearer tok-123");
            assertThat(server.headers.getFirst("X-Trace-Id")).isEqualTo("tr-9");
        }

        @Test
        @DisplayName("the end-user token never resolves in the URL or the body")
        void tokenStaysOutOfUrlAndBody() throws Exception {
            ExecutionContext context = context();
            Map<String, Object> params = new HashMap<>();
            params.put(EndUserAuth.PARAM_USER_TOKEN, "tok-123");
            EndUserAuth.lift(context, params);

            handler.execute(step(server.url("/echo?token={{auth.userToken}}"), """
                    {"method":"POST","body":{"token":"{{auth.userToken}}"}}
                    """), context);

            // URLs are logged and bodies are persisted with the run, so the
            // credential must not leak into either surface.
            assertThat(server.uri.toString()).doesNotContain("tok-123");
            assertThat(server.body).doesNotContain("tok-123");
            @SuppressWarnings("unchecked")
            Map<String, Object> sent = new ObjectMapper().readValue(server.body, Map.class);
            assertThat(sent.get("token")).isNull();
        }

        @Test
        @DisplayName("configured queryParams are silently dropped from the request")
        void queryParamsAreIgnored() {
            // NOTE: possible defect — ApiConfig declares queryParams and the
            // builder persists them, but execute() never reads the field, so an
            // authored query parameter simply vanishes. Pinned as current behavior.
            handler.execute(step(server.url("/list"), """
                    {"queryParams":{"limit":"5"}}
                    """), context());

            assertThat(server.uri.getQuery()).isNull();
        }
    }

    @Nested
    @DisplayName("under simulation")
    class Simulated {

        @Test
        @DisplayName("nothing is called, and the step says so")
        void doesNotCallTheHost() {
            // The reason a simulator exists at all: testing a journey used to
            // mean real calls to the customer's systems.
            ExecutionContext context = context();
            context.setInternal(com.itways.assistant.journey.engine.context.Simulation.INTERNAL_SIMULATE, true);

            StepResult result = handler.execute(step(server.url("/orders/42"), null), context);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(server.method).isNull();
            assertThat(result.getMetadata())
                    .containsEntry(com.itways.assistant.journey.engine.context.Simulation.META_SIMULATED, true);
        }

        @Test
        @DisplayName("the URL is still interpolated, so a placeholder that resolves to nothing still shows up")
        void stillInterpolatesTheUrl() {
            // Stubbing before interpolation would hide the most common API_CALL
            // bug: an id that never resolved, leaving /orders/ as the path.
            ExecutionContext context = context();
            context.setInternal(com.itways.assistant.journey.engine.context.Simulation.INTERNAL_SIMULATE, true);

            StepResult result = handler.execute(
                    step(server.url("/orders/{{inputs.entities.orderId}}"), null), context);

            assertThat(result.getData()).asInstanceOf(
                    org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .containsEntry("simulated", true)
                    .hasEntrySatisfying("url", url -> assertThat(String.valueOf(url)).endsWith("/orders/42"));
        }

        @Test
        @DisplayName("downstream steps see a status but no invented payload")
        void publishesStatusWithoutFiction() {
            // An invented response shape would let a downstream step read
            // fields no real call returns and appear to work.
            ExecutionContext context = context();
            context.setInternal(com.itways.assistant.journey.engine.context.Simulation.INTERNAL_SIMULATE, true);

            handler.execute(step(server.url("/orders/42"), null), context);

            assertThat(variableContext.read(context, "steps.2.status")).isEqualTo(200);
            assertThat(variableContext.read(context, "steps.2.output.simulated")).isEqualTo(true);
        }
    }

    @Nested
    @DisplayName("the response it publishes")
    class Response {

        @Test
        @DisplayName("parses the JSON response into the step's output with the HTTP status alongside")
        void publishesParsedResponse() {
            server.responseBody = "{\"id\":7,\"status\":\"ok\"}";
            ExecutionContext context = context();

            StepResult result = handler.execute(step(server.url("/orders/42"), null), context);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getData()).isEqualTo(Map.of("id", 7, "status", "ok"));
            assertThat(variableContext.read(context, "steps.2.output.id")).isEqualTo(7);
            assertThat(variableContext.read(context, "steps.2.status")).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("failures")
    class Failures {

        @Test
        @DisplayName("a non-2xx response becomes a step ERROR carrying the host's own words")
        void non2xxBecomesStepError() {
            server.responseStatus = 404;
            server.responseBody = "{\"error\":\"no such order\"}";
            ExecutionContext context = context();

            StepResult result = handler.execute(step(server.url("/orders/42"), null), context);

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getMessage()).contains("API Call Failed").contains("404")
                    .contains("no such order");
            // A failed call must not leave a half-written output for later steps.
            assertThat(variableContext.read(context, "steps.2.output")).isNull();
        }

        @Test
        @DisplayName("a host that never answers times out into a step ERROR instead of hanging the turn")
        void slowHostTimesOut() {
            // The engine runs API calls on the request thread; before read
            // timeouts existed, a silent host parked the whole conversation
            // forever. 250ms timeout vs a 2s reply proves the bound is real.
            ApiCallStepHandler impatient = new ApiCallStepHandler(
                    new EngineUtils(objectMapper), variableContext,
                    new StepOutputSchemaHelper(objectMapper), 5000, 250);
            server.delayMs = 2000;

            StepResult result = impatient.execute(step(server.url("/slow"), null), context());

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getMessage()).contains("did not answer in time");
        }

        @Test
        @DisplayName("an unreachable host is a step ERROR, not a crashed run")
        void connectionRefusedBecomesStepError() throws IOException {
            int deadPort;
            try (ServerSocket socket = new ServerSocket(0)) {
                deadPort = socket.getLocalPort();
            }

            StepResult result = handler.execute(
                    step("http://localhost:" + deadPort + "/orders", null), context());

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getMessage()).startsWith("API Call Failed");
        }
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    /** One-endpoint HTTP server that records the request and plays a scripted reply. */
    private static final class RecordingServer {

        private final HttpServer httpServer;

        volatile int responseStatus = 200;
        volatile String responseBody = "{\"id\":7,\"status\":\"ok\"}";
        volatile long delayMs = 0;

        volatile String method;
        volatile URI uri;
        volatile Headers headers;
        volatile String body;

        RecordingServer() throws IOException {
            httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            httpServer.createContext("/", exchange -> {
                method = exchange.getRequestMethod();
                uri = exchange.getRequestURI();
                headers = exchange.getRequestHeaders();
                body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            httpServer.start();
        }

        String url(String pathAndQuery) {
            return "http://localhost:" + httpServer.getAddress().getPort() + pathAndQuery;
        }

        void stop() {
            httpServer.stop(0);
        }
    }
}
