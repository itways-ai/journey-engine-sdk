package com.itways.assistant.journey.engine.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.EndUserAuth;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.language.EngineMessages;
import com.itways.assistant.journey.engine.language.LanguageDetector;
import com.itways.assistant.journey.engine.language.StepLocalizer;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.ExecutionStatus;
import com.itways.assistant.journey.engine.model.Journey;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.JourneyRunLifecycleEvent;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.JourneyRunLifecyclePort;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.service.StepHandlerRegistry;
import com.itways.assistant.journey.engine.service.StepTextPort;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.Placeholders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The engine state machine: ordering, eligibility, the four step outcomes
 * (SUCCESS / WAITING / JUMP / ERROR), resumption, lifecycle emission and final
 * message extraction. Everything is wired by hand — Mockito's inline mock maker
 * cannot instrument types under this module's Byte Buddy version on JDK 21, so
 * hand-written stubs are the module convention.
 */
@DisplayName("JourneyEngineImpl")
class JourneyEngineImplTest {

    private static final String ACCOUNT = "acc-1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VariableContext variableContext = new VariableContext();
    private final EngineUtils engineUtils = new EngineUtils(objectMapper);
    private final EngineMessages engineMessages = new EngineMessages();

    private StepHandlerRegistry registry;
    private RecordingLifecyclePort lifecyclePort;
    private JourneyEngineImpl engine;

    @BeforeEach
    void buildEngine() {
        registry = new StepHandlerRegistry(new ArrayList<>());
        lifecyclePort = new RecordingLifecyclePort();
        engine = newEngine(List.of(lifecyclePort));
    }

    private JourneyEngineImpl newEngine(List<JourneyRunLifecyclePort> ports) {
        StepLocalizer localizer = new StepLocalizer(objectMapper, new LanguageDetector(),
                providerOf(null), providerOf(null));
        return new JourneyEngineImpl(registry, engineUtils, variableContext, objectMapper,
                ports, engineMessages, localizer);
    }

    // ---- Scenarios ----

    @Nested
    @DisplayName("a linear run")
    class LinearRun {

        @Test
        @DisplayName("executes steps in order and finishes with the last success message")
        void ordered() {
            ScriptedHandler handler = registerScripted("STUB");
            handler.onAny((step, context) -> StepResult.success("out-" + step.getStepOrder(),
                    "msg-" + step.getStepOrder()));

            Map<String, Object> result = engine.start(journeyOf(
                    stub(1, null), stub(2, 1)), ACCOUNT, null, Map.of("text", "hi"));

            assertThat(result.get("status")).isEqualTo("FINISHED");
            assertThat(result.get("message")).isEqualTo("msg-2");
            assertThat(handler.executedOrders).containsExactly(1, 2);
            assertThat(result.get("executionId")).isNotNull();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> views = (List<Map<String, Object>>) result.get("stepResults");
            assertThat(views).hasSize(2);
            assertThat(views.get(0))
                    .containsEntry("type", "STUB")
                    .containsEntry("status", "SUCCESS")
                    .containsKeys("startedAt", "completedAt", "durationMs");
        }

        @Test
        @DisplayName("publishes run identity into runtime before the first step")
        void runtimeSeeded() {
            ScriptedHandler handler = registerScripted("STUB");
            handler.onAny((step, context) -> {
                Object executionId = Placeholders.resolve("{{runtime.executionId}}", context.getVariables());
                Object language = Placeholders.resolve("{{runtime.language}}", context.getVariables());
                return StepResult.success(executionId + "/" + language);
            });

            Map<String, Object> params = new HashMap<>();
            params.put("language", "ar");
            Map<String, Object> result = engine.start(journeyOf(stub(1, null)), ACCOUNT, null, params);

            ExecutionContext context = (ExecutionContext) result.get("context");
            assertThat(context.getStepResults().get(1))
                    .asString()
                    .startsWith(context.getExecutionId() + "/")
                    .endsWith("/ar");
        }

        @Test
        @DisplayName("lifts the end-user token out of author-visible variables before any step runs")
        void tokenNeverReachesVariables() {
            ScriptedHandler handler = registerScripted("STUB");
            List<Object> seenInVariables = new ArrayList<>();
            handler.onAny((step, context) -> {
                seenInVariables.add(context.getVariables().get(EndUserAuth.PARAM_USER_TOKEN));
                seenInVariables.add(EndUserAuth.token(context));
                return StepResult.success("done");
            });

            Map<String, Object> params = new HashMap<>();
            params.put(EndUserAuth.PARAM_USER_TOKEN, "bearer-x");
            engine.start(journeyOf(stub(1, null)), ACCOUNT, null, params);

            assertThat(seenInVariables.get(0)).isNull();
            assertThat(seenInVariables.get(1)).isEqualTo("bearer-x");
        }
    }

    @Nested
    @DisplayName("degenerate journeys")
    class Degenerate {

        @Test
        @DisplayName("a journey with no steps finishes with the localized no-steps message")
        void noSteps() {
            Map<String, Object> result = engine.start(journeyOf(), ACCOUNT, null, Map.of());
            assertThat(result.get("status")).isEqualTo("FINISHED");
            assertThat(result.get("message"))
                    .isEqualTo(engineMessages.get(
                            com.itways.assistant.journey.engine.language.ConversationLanguage.ENGLISH,
                            "run.noSteps"));
        }

        @Test
        @DisplayName("a cyclic step graph aborts the run as ERROR instead of looping forever")
        void cyclicGraph() {
            registerScripted("STUB").onAny((step, context) -> StepResult.success("x"));
            Map<String, Object> result = engine.start(
                    journeyOf(stub(1, 2), stub(2, 1)), ACCOUNT, null, Map.of());
            assertThat(result.get("status")).isEqualTo("ERROR");
        }

        @Test
        @DisplayName("a step type with no registered handler halts the run")
        void unknownHandlerHalts() {
            Map<String, Object> result = engine.start(
                    journeyOf(JourneyStep.builder().stepOrder(1).actionType("NO_SUCH_TYPE").build()),
                    ACCOUNT, null, Map.of());
            assertThat(result.get("status")).isEqualTo("ERROR");
        }

        @Test
        @DisplayName("a step type with no handler continues when continueOnError is set")
        void unknownHandlerContinueOnError() {
            ScriptedHandler handler = registerScripted("STUB");
            handler.onAny((step, context) -> StepResult.success("x", "made it"));

            JourneyStep broken = JourneyStep.builder()
                    .stepOrder(1).actionType("NO_SUCH_TYPE").continueOnError(true).build();
            Map<String, Object> result = engine.start(
                    journeyOf(broken, stub(2, 1)), ACCOUNT, null, Map.of());

            assertThat(result.get("status")).isEqualTo("FINISHED");
            // The failed step's placeholder result is what downstream eligibility sees.
            ExecutionContext context = (ExecutionContext) result.get("context");
            assertThat(context.getStepResults().get(1)).isEqualTo("FAILED");
            assertThat(handler.executedOrders).containsExactly(2);
        }
    }

    @Nested
    @DisplayName("failure handling")
    class Failures {

        @Test
        @DisplayName("an ERROR halts the run and later steps never execute")
        void errorHalts() {
            ScriptedHandler handler = registerScripted("STUB");
            handler.on(1, (step, context) -> StepResult.error("boom"));
            handler.onAny((step, context) -> StepResult.success("never"));

            Map<String, Object> result = engine.start(
                    journeyOf(stub(1, null), stub(2, 1)), ACCOUNT, null, Map.of());

            assertThat(result.get("status")).isEqualTo("ERROR");
            assertThat(handler.executedOrders).containsExactly(1);
        }

        @Test
        @DisplayName("a handler that throws is converted to a step ERROR, not a crashed run")
        void handlerExceptionContained() {
            ScriptedHandler handler = registerScripted("STUB");
            handler.on(1, (step, context) -> {
                throw new IllegalStateException("bug in handler");
            });

            Map<String, Object> result = engine.start(journeyOf(stub(1, null)), ACCOUNT, null, Map.of());

            assertThat(result.get("status")).isEqualTo("ERROR");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> views = (List<Map<String, Object>>) result.get("stepResults");
            assertThat(views.get(0).get("message")).asString().contains("Internal Handler Error");
        }

        @Test
        @DisplayName("a failure with a userMessage is what the customer reads; the diagnostic stays in detail")
        void userMessageWins() {
            ScriptedHandler handler = registerScripted("STUB");
            handler.on(1, (step, context) ->
                    StepResult.error("TEMPLATE_RENDER: '{{id}}' is not a template id", "We hit a snag."));

            Map<String, Object> result = engine.start(journeyOf(stub(1, null)), ACCOUNT, null, Map.of());

            assertThat(result.get("message")).isEqualTo("We hit a snag.");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> views = (List<Map<String, Object>>) result.get("stepResults");
            assertThat(views.get(0).get("detail")).asString().contains("not a template id");
        }

        @Test
        @DisplayName("a failure without a userMessage is replaced by the generic localized sentence")
        void technicalMessageHidden() {
            // The raw diagnostic is half identifier and untranslatable; the run
            // history keeps it, the reply must not.
            ScriptedHandler handler = registerScripted("STUB");
            handler.on(1, (step, context) -> StepResult.error("connect timeout on 10.0.0.7:8443"));

            Map<String, Object> result = engine.start(journeyOf(stub(1, null)), ACCOUNT, null, Map.of());

            assertThat(result.get("message"))
                    .isEqualTo(engineMessages.get(
                            com.itways.assistant.journey.engine.language.ConversationLanguage.ENGLISH,
                            "run.error.generic"))
                    .isNotEqualTo("connect timeout on 10.0.0.7:8443");
        }

        @Test
        @DisplayName("continueOnError records FAILED output and lets the run finish")
        void continueOnError() {
            ScriptedHandler handler = registerScripted("STUB");
            handler.on(1, (step, context) -> StepResult.error("boom"));
            handler.onAny((step, context) -> StepResult.success("ok", "done"));

            JourneyStep failing = JourneyStep.builder()
                    .stepOrder(1).actionType("STUB").continueOnError(true).build();
            Map<String, Object> result = engine.start(
                    journeyOf(failing, stub(2, 1)), ACCOUNT, null, Map.of());

            assertThat(result.get("status")).isEqualTo("FINISHED");
            assertThat(variableContext.read((ExecutionContext) result.get("context"), "steps.1.output"))
                    .isEqualTo("FAILED");
            assertThat(handler.executedOrders).containsExactly(1, 2);
        }
    }

    @Nested
    @DisplayName("waiting and resumption")
    class WaitingAndResume {

        @Test
        @DisplayName("WAITING parks the run immediately without executing later steps")
        void waitingParks() {
            ScriptedHandler handler = registerScripted("STUB");
            handler.on(1, (step, context) -> {
                context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);
                return StepResult.waiting("What is your order number?", new HashMap<>());
            });
            handler.onAny((step, context) -> StepResult.success("never"));

            Map<String, Object> result = engine.start(
                    journeyOf(stub(1, null), stub(2, 1)), ACCOUNT, null, Map.of());

            assertThat(result.get("status")).isEqualTo("WAITING");
            assertThat(result.get("message")).isEqualTo("What is your order number?");
            assertThat(result.get("context")).isNotNull();
            assertThat(handler.executedOrders).containsExactly(1);
        }

        @Test
        @DisplayName("resume skips steps at or before currentStepIndex")
        void resumeSkipsCompleted() {
            ScriptedHandler handler = registerScripted("STUB");
            handler.onAny((step, context) -> StepResult.success("out", "done"));

            ExecutionContext parked = ExecutionContext.builder()
                    .executionId("exec-1")
                    .accountId(ACCOUNT)
                    .journeyId(9L)
                    .currentStepIndex(1)
                    .status(ExecutionStatus.WAITING_FOR_INPUT)
                    .variables(new HashMap<>())
                    .build();
            // A parked context carries its completed steps' results — without
            // them, step 2's parent looks un-run and eligibility skips it. This
            // is why the host rebuilds stepResults field-by-field on resume.
            parked.addStepResult(1, "earlier-output");

            Map<String, Object> result = engine.resume(
                    journeyOf(stub(1, null), stub(2, 1)), parked, Map.of("answer", "42"));

            assertThat(result.get("status")).isEqualTo("FINISHED");
            assertThat(handler.executedOrders).containsExactly(2);
            // Resume must preserve the run's identity, not mint a new one.
            assertThat(result.get("executionId")).isEqualTo("exec-1");
        }
    }

    @Nested
    @DisplayName("JUMP")
    class Jump {

        @Test
        @DisplayName("a backward jump replays from the target and rolls back replayed state exactly once")
        void backwardJumpRollsBack() {
            ScriptedHandler handler = registerScripted("STUB");
            List<Integer> stateSeen = new ArrayList<>();
            handler.on(1, (step, context) -> {
                // Mimics STATE_STORE INCREMENT: without rollback this compounds per pass.
                Object current = variableContext.getState(context).get("counter");
                int value = current instanceof Number number ? number.intValue() : 0;
                variableContext.writeState(context, step, "counter", value + 1);
                stateSeen.add(value + 1);
                return StepResult.success(value + 1);
            });
            boolean[] jumped = { false };
            handler.on(2, (step, context) -> {
                if (!jumped[0]) {
                    jumped[0] = true;
                    return StepResult.jump(1, "loop once");
                }
                return StepResult.success("through", "made it");
            });

            Map<String, Object> result = engine.start(
                    journeyOf(stub(1, null), stub(2, 1)), ACCOUNT, null, Map.of());

            assertThat(result.get("status")).isEqualTo("FINISHED");
            assertThat(handler.executedOrders).containsExactly(1, 2, 1, 2);
            // The rollback cleared the first pass's write, so the counter never compounds.
            assertThat(stateSeen).containsExactly(1, 1);
            ExecutionContext context = (ExecutionContext) result.get("context");
            assertThat(variableContext.getState(context)).containsEntry("counter", 1);
        }

        @Test
        @DisplayName("a jump clears the replayed steps' outputs so stale data cannot satisfy eligibility")
        void jumpClearsOutputs() {
            ScriptedHandler handler = registerScripted("STUB");
            boolean[] jumped = { false };
            handler.on(1, (step, context) -> StepResult.success("first"));
            handler.on(2, (step, context) -> {
                if (!jumped[0]) {
                    jumped[0] = true;
                    assertThat(context.getStepResults()).containsKey(1);
                    return StepResult.jump(1, "again");
                }
                // On the replayed pass, the pre-jump output of step 1 was cleared
                // and rewritten by the replay, not left over.
                return StepResult.success("second", "done");
            });

            Map<String, Object> result = engine.start(
                    journeyOf(stub(1, null), stub(2, 1)), ACCOUNT, null, Map.of());

            assertThat(result.get("status")).isEqualTo("FINISHED");
            assertThat(handler.executedOrders).containsExactly(1, 2, 1, 2);
        }

        @Test
        @DisplayName("an unconditional backward jump is stopped at the jump cap instead of hanging the turn")
        void infiniteJumpLoopIsCapped() {
            // The state rollback above means an author cannot build a loop
            // counter that survives its own loop — so a bad exit condition IS an
            // infinite loop, and it runs on the request thread. The cap turns
            // "hangs forever" into a localized ERROR.
            ScriptedHandler handler = registerScripted("STUB");
            handler.on(1, (step, context) -> StepResult.success("pass"));
            handler.on(2, (step, context) -> StepResult.jump(1, "again"));

            Map<String, Object> result = engine.start(
                    journeyOf(stub(1, null), stub(2, 1)), ACCOUNT, null, Map.of());

            assertThat(result.get("status")).isEqualTo("ERROR");
            assertThat(result.get("message")).isEqualTo(engineMessages.get(
                    com.itways.assistant.journey.engine.language.ConversationLanguage.ENGLISH,
                    "run.loopLimit"));
            // Bounded work: the cap fired, not the heat death of the request thread.
            assertThat(handler.executedOrders.size())
                    .isLessThanOrEqualTo(2 * (JourneyEngineImpl.MAX_JUMPS_PER_TURN + 1));
        }
    }

    @Nested
    @DisplayName("branch eligibility")
    class Eligibility {

        @Test
        @DisplayName("CONDITION routes to the matching boolean branch only")
        void conditionRouting() {
            ScriptedHandler handler = registerScripted("STUB");
            handler.on(1, (step, context) -> StepResult.success(Boolean.TRUE));
            handler.onAny((step, context) -> StepResult.success("ran-" + step.getStepOrder()));

            Map<String, Object> result = engine.start(journeyOf(
                    stub(1, null),
                    branch(2, 1, "true"),
                    branch(3, 1, "false")), ACCOUNT, null, Map.of());

            assertThat(result.get("status")).isEqualTo("FINISHED");
            assertThat(handler.executedOrders).containsExactly(1, 2);
        }

        @Test
        @DisplayName("a Map result with a boolean 'result' key routes like a raw Boolean")
        void conditionMapResult() {
            ScriptedHandler handler = registerScripted("STUB");
            handler.on(1, (step, context) -> StepResult.success(Map.of("result", false)));
            handler.onAny((step, context) -> StepResult.success("ran"));

            Map<String, Object> result = engine.start(journeyOf(
                    stub(1, null),
                    branch(2, 1, "true"),
                    branch(3, 1, "false")), ACCOUNT, null, Map.of());

            assertThat(handler.executedOrders).containsExactly(1, 3);
            assertThat(result.get("status")).isEqualTo("FINISHED");
        }

        @Test
        @DisplayName("SWITCH runs the named case matching the stored value, case-insensitively")
        void switchNamedCase() {
            ScriptedHandler handler = registerScripted("STUB");
            handler.on(1, (step, context) -> StepResult.success(Map.of("value", "Gold")));
            handler.onAny((step, context) -> StepResult.success("ran"));

            Map<String, Object> result = engine.start(journeyOf(
                    stub(1, null),
                    branch(2, 1, "gold"),
                    branch(3, 1, "silver"),
                    branch(4, 1, "DEFAULT")), ACCOUNT, null, Map.of());

            assertThat(handler.executedOrders).containsExactly(1, 2);
            assertThat(result.get("status")).isEqualTo("FINISHED");
        }

        @Test
        @DisplayName("DEFAULT runs only when no named sibling matches")
        void switchDefaultFallback() {
            ScriptedHandler handler = registerScripted("STUB");
            handler.on(1, (step, context) -> StepResult.success(Map.of("value", "bronze")));
            handler.onAny((step, context) -> StepResult.success("ran"));

            engine.start(journeyOf(
                    stub(1, null),
                    branch(2, 1, "gold"),
                    branch(3, 1, "DEFAULT")), ACCOUNT, null, Map.of());

            assertThat(handler.executedOrders).containsExactly(1, 3);
        }

        @Test
        @DisplayName("a rejoin step runs when any one of its parent branches completed")
        void rejoinAfterOneBranch() {
            ScriptedHandler handler = registerScripted("STUB");
            handler.on(1, (step, context) -> StepResult.success(Boolean.TRUE));
            handler.onAny((step, context) -> StepResult.success("ran-" + step.getStepOrder(), "done"));

            JourneyStep rejoin = JourneyStep.builder()
                    .stepOrder(4).actionType("STUB").parentOrders(List.of(2, 3)).build();
            Map<String, Object> result = engine.start(journeyOf(
                    stub(1, null),
                    branch(2, 1, "true"),
                    branch(3, 1, "false"),
                    rejoin), ACCOUNT, null, Map.of());

            assertThat(handler.executedOrders).containsExactly(1, 2, 4);
            assertThat(result.get("status")).isEqualTo("FINISHED");
        }

        @Test
        @DisplayName("a child whose parent never ran stays ineligible")
        void orphanedBranchSkipped() {
            ScriptedHandler handler = registerScripted("STUB");
            handler.on(1, (step, context) -> StepResult.success(Boolean.FALSE));
            handler.onAny((step, context) -> StepResult.success("ran"));

            // Step 3 hangs off the never-taken true branch's child.
            engine.start(journeyOf(
                    stub(1, null),
                    branch(2, 1, "true"),
                    stub(3, 2)), ACCOUNT, null, Map.of());

            assertThat(handler.executedOrders).containsExactly(1);
        }
    }

    @Nested
    @DisplayName("observers and lifecycle ports")
    class ObserversAndLifecycle {

        @Test
        @DisplayName("a throwing observer never breaks the run")
        void observerIsolated() {
            ScriptedHandler handler = registerScripted("STUB");
            handler.onAny((step, context) -> StepResult.success("ok", "done"));

            Map<String, Object> result = engine.start(journeyOf(stub(1, null)), ACCOUNT, null,
                    Map.of(), view -> {
                        throw new IllegalStateException("SSE pipe broke");
                    });

            assertThat(result.get("status")).isEqualTo("FINISHED");
        }

        @Test
        @DisplayName("the observer receives each step view as it completes, in order")
        void observerReceivesViews() {
            ScriptedHandler handler = registerScripted("STUB");
            handler.onAny((step, context) -> StepResult.success("ok", "m" + step.getStepOrder()));
            List<Object> seen = new ArrayList<>();

            engine.start(journeyOf(stub(1, null), stub(2, 1)), ACCOUNT, null, Map.of(),
                    view -> seen.add(view.get("message")));

            assertThat(seen).containsExactly("m1", "m2");
        }

        @Test
        @DisplayName("the durable RUNNING write is hard: a failing port aborts the start")
        void runningEmissionIsHard() {
            registerScripted("STUB").onAny((step, context) -> StepResult.success("x"));
            JourneyEngineImpl failingEngine = newEngine(List.of(event -> {
                throw new IllegalStateException("history store down");
            }));

            assertThatThrownBy(() -> failingEngine.start(journeyOf(stub(1, null)), ACCOUNT, null, Map.of()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("terminal lifecycle failures are soft: the run's result is unaffected")
        void terminalEmissionIsSoft() {
            registerScripted("STUB").onAny((step, context) -> StepResult.success("x", "done"));
            FailTerminalPort port = new FailTerminalPort();
            JourneyEngineImpl softEngine = newEngine(List.of(port));

            Map<String, Object> result = softEngine.start(journeyOf(stub(1, null)), ACCOUNT, null, Map.of());

            assertThat(result.get("status")).isEqualTo("FINISHED");
            assertThat(port.terminalAttempted).isTrue();
        }

        @Test
        @DisplayName("a completed run emits RUNNING then COMPLETED with matching identity")
        void lifecycleSequence() {
            registerScripted("STUB").onAny((step, context) -> StepResult.success("x", "done"));

            Map<String, Object> result = engine.start(journeyOf(stub(1, null)), ACCOUNT, null, Map.of());

            assertThat(lifecyclePort.statuses)
                    .containsExactly(JourneyRunLifecycleEvent.STATUS_RUNNING,
                            JourneyRunLifecycleEvent.STATUS_COMPLETED);
            assertThat(lifecyclePort.events.get(1).getExecutionId()).isEqualTo(result.get("executionId"));
            assertThat(lifecyclePort.events.get(1).getAccountId()).isEqualTo(ACCOUNT);
        }
    }

    @Test
    @DisplayName("unresolved placeholders inside a step surface on that step's view")
    void unresolvedVariablesSurfaced() {
        ScriptedHandler handler = registerScripted("STUB");
        handler.onAny((step, context) -> {
            Placeholders.replace("{{steps.9.output.missing}}", context.getVariables());
            return StepResult.success("ok");
        });

        Map<String, Object> result = engine.start(journeyOf(stub(1, null)), ACCOUNT, null, Map.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> views = (List<Map<String, Object>>) result.get("stepResults");
        assertThat(views.get(0).get("unresolvedVariables"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("steps.9.output.missing");
    }

    // ---- Fixtures ----

    private Journey journeyOf(JourneyStep... steps) {
        return Journey.builder()
                .id(9L)
                .name("Test Journey")
                .triggerIntent("test-intent")
                .active(true)
                .steps(List.of(steps))
                .build();
    }

    private static JourneyStep stub(int order, Integer parentOrder) {
        return JourneyStep.builder().stepOrder(order).actionType("STUB")
                .stepName("step-" + order).parentOrder(parentOrder).build();
    }

    private static JourneyStep branch(int order, int parentOrder, String branchName) {
        return JourneyStep.builder().stepOrder(order).actionType("STUB")
                .stepName("step-" + order).parentOrder(parentOrder).branchName(branchName).build();
    }

    private ScriptedHandler registerScripted(String type) {
        ScriptedHandler handler = new ScriptedHandler(type);
        registry.registerHandler(handler);
        return handler;
    }

    /** Per-order scripted behaviors with an execution recording, in place of Mockito. */
    private static final class ScriptedHandler implements StepHandler {
        private final String type;
        private final Map<Integer, BiFunction<JourneyStep, ExecutionContext, StepResult>> byOrder = new HashMap<>();
        private BiFunction<JourneyStep, ExecutionContext, StepResult> fallback =
                (step, context) -> StepResult.success(null);
        final List<Integer> executedOrders = new ArrayList<>();

        private ScriptedHandler(String type) {
            this.type = type;
        }

        void on(int order, BiFunction<JourneyStep, ExecutionContext, StepResult> behavior) {
            byOrder.put(order, behavior);
        }

        void onAny(BiFunction<JourneyStep, ExecutionContext, StepResult> behavior) {
            fallback = behavior;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public StepResult execute(JourneyStep step, ExecutionContext context) {
            executedOrders.add(step.getStepOrder());
            return byOrder.getOrDefault(step.getStepOrder(), fallback).apply(step, context);
        }
    }

    private static final class RecordingLifecyclePort implements JourneyRunLifecyclePort {
        final List<JourneyRunLifecycleEvent> events = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();

        @Override
        public void onLifecycleEvent(JourneyRunLifecycleEvent event) {
            events.add(event);
            statuses.add(event.getStatus());
        }
    }

    /** Accepts the RUNNING write, fails every terminal one. */
    private static final class FailTerminalPort implements JourneyRunLifecyclePort {
        boolean terminalAttempted;

        @Override
        public void onLifecycleEvent(JourneyRunLifecycleEvent event) {
            if (!JourneyRunLifecycleEvent.STATUS_RUNNING.equals(event.getStatus())) {
                terminalAttempted = true;
                throw new IllegalStateException("history store down");
            }
        }
    }

    /** Minimal ObjectProvider, as StepLocalizerTest hand-rolls — Mockito is unusable here. */
    private static <T> ObjectProvider<T> providerOf(T instance) {
        return new ObjectProvider<T>() {
            @Override
            public T getObject() {
                if (instance == null) {
                    throw new IllegalStateException("no instance");
                }
                return instance;
            }

            @Override
            public T getObject(Object... args) {
                return getObject();
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }
        };
    }
}
