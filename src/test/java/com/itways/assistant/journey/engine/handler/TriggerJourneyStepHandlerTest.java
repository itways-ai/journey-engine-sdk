package com.itways.assistant.journey.engine.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.impl.JourneyEngineImpl;
import com.itways.assistant.journey.engine.language.EngineMessages;
import com.itways.assistant.journey.engine.language.LanguageDetector;
import com.itways.assistant.journey.engine.language.StepLocalizer;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.ExecutionStatus;
import com.itways.assistant.journey.engine.model.Journey;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.JourneyLookupPort;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.service.StepHandlerRegistry;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;
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

/**
 * TRIGGER_JOURNEY runs another journey inline on a real engine — the child
 * execution is the behavior under test, so the engine is wired by hand exactly
 * as in JourneyEngineImplTest rather than stubbed. The contracts pinned here
 * are the guard rails (the intent stack and depth cap that keep two journeys
 * calling each other from recursing forever), the variable wall between parent
 * and child, and the park-and-resume relay for a child that stops to ask the
 * user something.
 */
@DisplayName("TriggerJourneyStepHandler")
class TriggerJourneyStepHandlerTest {

    private static final String ACCOUNT = "acc-1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VariableContext variableContext = new VariableContext();
    private final EngineUtils engineUtils = new EngineUtils(objectMapper);
    private final EngineMessages engineMessages = new EngineMessages();
    private final StepOutputSchemaHelper schemaHelper = new StepOutputSchemaHelper(objectMapper);

    private StepHandlerRegistry registry;
    private StubLookupPort lookup;
    private TriggerJourneyStepHandler handler;
    private ScriptedHandler childHandler;
    private ScriptedHandler parentHandler;
    private JourneyEngineImpl engine;

    @BeforeEach
    void buildEngineAndHandler() {
        registry = new StepHandlerRegistry(new ArrayList<>());
        lookup = new StubLookupPort();
        StepLocalizer localizer = new StepLocalizer(objectMapper, new LanguageDetector(),
                providerOf(null), providerOf(null));
        engine = new JourneyEngineImpl(registry, engineUtils, variableContext, objectMapper,
                List.of(), engineMessages, localizer, providerOf(null));
        handler = new TriggerJourneyStepHandler(lookup, engineUtils, variableContext,
                schemaHelper, engine);
        registry.registerHandler(handler);
        childHandler = new ScriptedHandler("CHILD_STUB");
        registry.registerHandler(childHandler);
        parentHandler = new ScriptedHandler("PARENT_STUB");
        registry.registerHandler(parentHandler);
    }

    @Nested
    @DisplayName("running a child inline")
    class InlineChild {

        @Test
        @DisplayName("the child executes to completion and its last output becomes this step's output")
        void childRunsInlineAndParentGetsItsOutput() {
            lookup.register(journey("child-flow", childStub(1)));
            childHandler.onAny((step, context) -> StepResult.success("child-out"));
            ExecutionContext parent = parentContext();

            StepResult result = handler.execute(triggerStep("child-flow"), parent);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getData()).isEqualTo("child-out");
            assertThat(result.getActionTarget()).isEqualTo("child-flow");
            assertThat(result.getMessage()).isEqualTo("Triggered journey: child-flow");
            assertThat(variableContext.read(parent, "steps.2.output")).isEqualTo("child-out");
            assertThat(parent.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
            // The child ran in the parent's account scope, not some default.
            assertThat(lookup.accountId).isEqualTo(ACCOUNT);
        }

        @Test
        @DisplayName("the child's step views ride up in metadata so the timeline can show them")
        void nestedStepResultsSurfaceInMetadata() {
            lookup.register(journey("child-flow", childStub(1)));
            childHandler.onAny((step, context) -> StepResult.success("child-out", "child said"));

            StepResult result = handler.execute(triggerStep("child-flow"), parentContext());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nested = (List<Map<String, Object>>) result.getMetadata()
                    .get(TriggerJourneyStepHandler.META_NESTED_STEP_RESULTS);
            assertThat(nested).hasSize(1);
            assertThat(nested.get(0))
                    .containsEntry("type", "CHILD_STUB")
                    .containsEntry("status", "SUCCESS")
                    .containsEntry("message", "child said");
        }

        @Test
        @DisplayName("through the engine, the parent's next step runs after the child finished")
        void parentContinuesAfterTheChild() {
            lookup.register(journey("child-flow", childStub(1)));
            childHandler.onAny((step, context) -> StepResult.success("child-out"));
            parentHandler.onAny((step, context) -> StepResult.success("after-child", "parent done"));

            Journey parentJourney = journey("parent-flow",
                    triggerStepAt(1, "child-flow"),
                    JourneyStep.builder().stepOrder(2).stepName("after")
                            .actionType("PARENT_STUB").parentOrder(1).build());
            Map<String, Object> result = engine.start(parentJourney, ACCOUNT, null, Map.of("text", "hi"));

            assertThat(result.get("status")).isEqualTo("FINISHED");
            assertThat(parentHandler.executedOrders).containsExactly(2);
            // The run's timeline interleaves the child's views before the
            // trigger step's own — that ordering is what the client renders.
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> views = (List<Map<String, Object>>) result.get("stepResults");
            assertThat(views).extracting(view -> view.get("type"))
                    .containsExactly("CHILD_STUB", "TRIGGER_JOURNEY", "PARENT_STUB");
        }

        @Test
        @DisplayName("a failing child fails the trigger step and still surfaces the child's views")
        void childErrorFailsTheParentStep() {
            lookup.register(journey("child-flow", childStub(1)));
            childHandler.onAny((step, context) -> StepResult.error("child broke"));

            StepResult result = handler.execute(triggerStep("child-flow"), parentContext());

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getMessage()).isNotBlank();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nested = (List<Map<String, Object>>) result.getMetadata()
                    .get(TriggerJourneyStepHandler.META_NESTED_STEP_RESULTS);
            assertThat(nested).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("guard rails")
    class GuardRails {

        @Test
        @DisplayName("a blank intent fails plainly")
        void missingIntentFails() {
            StepResult result = handler.execute(triggerStep("  "), parentContext());

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getMessage()).contains("actionTarget (journey intent) is required");
        }

        @Test
        @DisplayName("an intent no journey answers to fails without starting anything")
        void unknownIntentFails() {
            StepResult result = handler.execute(triggerStep("ghost-flow"), parentContext());

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getMessage()).contains("journey not found for intent 'ghost-flow'");
            assertThat(childHandler.executedOrders).isEmpty();
        }

        @Test
        @DisplayName("an intent already on the call stack is refused — the direct cycle")
        void cycleOnTheCallStackFails() {
            lookup.register(journey("parent-flow", childStub(1)));
            ExecutionContext parent = parentContext();

            StepResult result = handler.execute(triggerStep("parent-flow"), parent);

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getMessage()).contains("already on the call stack").contains("parent-flow");
            assertThat(childHandler.executedOrders).isEmpty();
        }

        @Test
        @DisplayName("a child triggering its own ancestor is caught — the stack rides into nested runs")
        void childTriggeringItsAncestorFails() {
            // parent-flow -> child-flow -> parent-flow. The cycle only exists
            // across the nesting boundary, so this proves the stack survives
            // the trip through start-params into the child engine run.
            lookup.register(journey("child-flow", triggerStepAt(1, "parent-flow")));

            Map<String, Object> result = engine.start(
                    journey("parent-flow", triggerStepAt(1, "child-flow"),
                            JourneyStep.builder().stepOrder(2).stepName("after")
                                    .actionType("PARENT_STUB").parentOrder(1).build()),
                    ACCOUNT, null, Map.of("text", "hi"));

            assertThat(result.get("status")).isEqualTo("ERROR");
            assertThat(parentHandler.executedOrders).isEmpty();
        }

        @Test
        @DisplayName("nesting deeper than five journeys is refused")
        void depthCapFails() {
            lookup.register(journey("one-more-flow", childStub(1)));
            ExecutionContext parent = parentContext();
            parent.setInternal(TriggerJourneyStepHandler.TRIGGERED_JOURNEY_STACK,
                    new ArrayList<>(List.of("a", "b", "c", "d", "e")));

            StepResult result = handler.execute(triggerStep("one-more-flow"), parent);

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getMessage()).contains("max nesting depth (5)");
            assertThat(childHandler.executedOrders).isEmpty();
        }
    }

    @Nested
    @DisplayName("variable isolation")
    class VariableIsolation {

        @Test
        @DisplayName("the child gets copies of inputs and channel but never the parent's state or answer")
        void childSeesCopiesNotParentState() {
            lookup.register(journey("child-flow", childStub(1)));
            ExecutionContext parent = parentContext();
            variableContext.getState(parent).put("secret", "classified");
            variableContext.getInputs(parent).put("answer", "yes-parent");

            Map<String, Object> seenByChild = new HashMap<>();
            childHandler.on(1, (step, context) -> {
                seenByChild.put("state", new HashMap<>(variableContext.getState(context)));
                seenByChild.put("answer", variableContext.getInputs(context).get("answer"));
                seenByChild.put("text", variableContext.getInputs(context).get("text"));
                variableContext.writeState(context, step, "childKey", "childValue");
                return StepResult.success("child-out");
            });

            StepResult result = handler.execute(triggerStep("child-flow"), parent);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            // Session state is the parent's own; a consumed answer must not be
            // replayed into the child as if the user had answered it too.
            assertThat((Map<?, ?>) seenByChild.get("state")).isEmpty();
            assertThat(seenByChild.get("answer")).isNull();
            assertThat(seenByChild.get("text")).isEqualTo("hi");
            // And the wall holds in both directions.
            assertThat(variableContext.getState(parent))
                    .containsEntry("secret", "classified")
                    .doesNotContainKey("childKey");
            @SuppressWarnings("unchecked")
            Map<String, Object> parentSteps = (Map<String, Object>) parent.getVariables().get("steps");
            assertThat(parentSteps).containsOnlyKeys("2");
        }
    }

    @Nested
    @DisplayName("a child that stops to ask the user")
    class WaitingChild {

        private void childAsksForOrderNumber() {
            childHandler.on(1, (step, context) -> {
                Object answer = variableContext.getInputs(context).get("answer");
                if (answer == null) {
                    context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);
                    return StepResult.waiting("What is your order number?", new HashMap<>());
                }
                variableContext.storeOutput(context, step, answer);
                return StepResult.success(answer, "got it");
            });
        }

        @Test
        @DisplayName("a waiting child parks the parent and stores the resumable child context")
        void childWaitingParksTheParent() {
            lookup.register(journey("child-flow", childStub(1)));
            childAsksForOrderNumber();
            ExecutionContext parent = parentContext();

            StepResult result = handler.execute(triggerStep("child-flow"), parent);

            assertThat(result.getStatus()).isEqualTo("WAITING");
            // The child's question is what the user must read — the parent has
            // nothing of its own to say here.
            assertThat(result.getMessage()).isEqualTo("What is your order number?");
            assertThat(parent.getStatus()).isEqualTo(ExecutionStatus.WAITING_FOR_INPUT);
            assertThat(result.getMetadata())
                    .containsEntry(TriggerJourneyStepHandler.META_SKIP_SELF_VIEW, true)
                    .containsKey(TriggerJourneyStepHandler.META_NESTED_STEP_RESULTS);
            @SuppressWarnings("unchecked")
            Map<String, Object> active = (Map<String, Object>) parent
                    .getInternal(TriggerJourneyStepHandler.ACTIVE_TRIGGERED_JOURNEY);
            assertThat(active)
                    .containsEntry("intent", "child-flow")
                    .containsKey("childContext");
        }

        @Test
        @DisplayName("the next turn's answer reaches the parked child, which finishes the trigger step")
        void resumedAnswerReachesTheParkedChild() {
            lookup.register(journey("child-flow", childStub(1)));
            childAsksForOrderNumber();
            ExecutionContext parent = parentContext();
            handler.execute(triggerStep("child-flow"), parent);

            // The engine stores each turn's input under this internal key
            // before re-running the trigger step; mimic that hand-off.
            parent.setInternal(TriggerJourneyStepHandler.PENDING_RESUME_INPUT,
                    Map.of("answer", "A-42"));
            parent.setStatus(ExecutionStatus.RUNNING);
            StepResult result = handler.execute(triggerStep("child-flow"), parent);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getData()).isEqualTo("A-42");
            assertThat(variableContext.read(parent, "steps.2.output")).isEqualTo("A-42");
            // A stale active-child marker would send every later turn back
            // into the finished child instead of onward through the parent.
            assertThat(parent.getInternal(TriggerJourneyStepHandler.ACTIVE_TRIGGERED_JOURNEY)).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private Journey journey(String intent, JourneyStep... steps) {
        return Journey.builder()
                .id((long) intent.hashCode())
                .name(intent)
                .triggerIntent(intent)
                .active(true)
                .steps(List.of(steps))
                .build();
    }

    private static JourneyStep childStub(int order) {
        return JourneyStep.builder().stepOrder(order).stepName("child-" + order)
                .actionType("CHILD_STUB").build();
    }

    private static JourneyStep triggerStep(String intent) {
        return triggerStepAt(2, intent);
    }

    private static JourneyStep triggerStepAt(int order, String intent) {
        return JourneyStep.builder().stepOrder(order).stepName("Run " + intent)
                .actionType("TRIGGER_JOURNEY").actionTarget(intent).build();
    }

    /** A parent mid-run, with its own intent already on the call stack as the engine seeds it. */
    private ExecutionContext parentContext() {
        ExecutionContext context = ExecutionContext.builder()
                .executionId("parent-exec")
                .accountId(ACCOUNT)
                .journeyId(9L)
                .status(ExecutionStatus.RUNNING)
                .variables(new HashMap<>())
                .build();
        variableContext.ensureStructure(context);
        variableContext.mergeInputs(context, Map.of("text", "hi"));
        context.setInternal(TriggerJourneyStepHandler.TRIGGERED_JOURNEY_STACK,
                new ArrayList<>(List.of("parent-flow")));
        return context;
    }

    private static final class StubLookupPort implements JourneyLookupPort {

        private final Map<String, Journey> journeys = new HashMap<>();
        private String accountId;

        void register(Journey journey) {
            journeys.put(journey.getTriggerIntent(), journey);
        }

        @Override
        public Journey findByTriggerIntent(String accountId, java.util.UUID assistantId, String intent) {
            this.accountId = accountId;
            return journeys.get(intent);
        }
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

    /** Minimal ObjectProvider, as JourneyEngineImplTest hand-rolls — Mockito is unusable here. */
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
