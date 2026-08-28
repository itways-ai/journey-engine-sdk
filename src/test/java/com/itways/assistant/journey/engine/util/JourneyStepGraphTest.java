package com.itways.assistant.journey.engine.util;

import com.itways.assistant.journey.engine.model.JourneyStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Both the engine's execution ordering and journey-service's save-time
 * validation sort through this class. A cycle that slips past it corrupts
 * stored journeys; an ordering change silently reorders execution.
 */
@DisplayName("JourneyStepGraph")
class JourneyStepGraphTest {

    private static JourneyStep step(int order, Integer parentOrder) {
        return JourneyStep.builder().stepOrder(order).parentOrder(parentOrder).build();
    }

    private static JourneyStep rejoin(int order, List<Integer> parents) {
        return JourneyStep.builder().stepOrder(order).parentOrders(parents).build();
    }

    @Nested
    @DisplayName("resolveInboundParents")
    class InboundParents {

        @Test
        @DisplayName("prefers parentOrders over the legacy single parentOrder")
        void parentOrdersWin() {
            JourneyStep step = JourneyStep.builder()
                    .stepOrder(5).parentOrder(1).parentOrders(List.of(2, 3)).build();
            assertThat(JourneyStepGraph.resolveInboundParents(step)).containsExactly(2, 3);
        }

        @Test
        @DisplayName("falls back to a positive legacy parentOrder")
        void legacyFallback() {
            assertThat(JourneyStepGraph.resolveInboundParents(step(5, 2))).containsExactly(2);
        }

        @Test
        @DisplayName("zero, negative, and absent parents mean a root step")
        void rootStep() {
            assertThat(JourneyStepGraph.resolveInboundParents(step(1, null))).isEmpty();
            assertThat(JourneyStepGraph.resolveInboundParents(step(1, 0))).isEmpty();
            assertThat(JourneyStepGraph.resolveInboundParents(step(1, -1))).isEmpty();
        }

        @Test
        @DisplayName("only parentOrders makes a step a rejoin node")
        void rejoinDetection() {
            assertThat(JourneyStepGraph.isRejoinStep(rejoin(4, List.of(2, 3)))).isTrue();
            assertThat(JourneyStepGraph.isRejoinStep(step(4, 2))).isFalse();
            assertThat(JourneyStepGraph.isRejoinStep(
                    JourneyStep.builder().stepOrder(4).parentOrders(Collections.emptyList()).build())).isFalse();
        }
    }

    @Nested
    @DisplayName("sortSteps")
    class Sorting {

        @Test
        @DisplayName("orders every step after its parents regardless of input order")
        void topologicalOrder() {
            JourneyStep s1 = step(1, null);
            JourneyStep s2 = step(2, 1);
            JourneyStep s3 = step(3, 1);
            JourneyStep s4 = rejoin(4, List.of(2, 3));

            List<JourneyStep> sorted = JourneyStepGraph.sortSteps(List.of(s4, s3, s2, s1));

            assertThat(sorted.indexOf(s1)).isLessThan(sorted.indexOf(s2));
            assertThat(sorted.indexOf(s1)).isLessThan(sorted.indexOf(s3));
            assertThat(sorted.indexOf(s2)).isLessThan(sorted.indexOf(s4));
            assertThat(sorted.indexOf(s3)).isLessThan(sorted.indexOf(s4));
            assertThat(sorted).hasSize(4);
        }

        @Test
        @DisplayName("a reference to a nonexistent parent is ignored rather than fatal")
        void danglingParentIgnored() {
            // Step 2 claims parent 99, which does not exist. The edge is dropped
            // and the step still schedules — save-time validation, not the engine,
            // is where dangling references are rejected.
            List<JourneyStep> sorted = JourneyStepGraph.sortSteps(List.of(step(1, null), step(2, 99)));
            assertThat(sorted).hasSize(2);
        }

        @Test
        @DisplayName("a cycle aborts with the offending step orders named")
        void cycleThrows() {
            JourneyStep a = step(1, 2);
            JourneyStep b = step(2, 1);
            assertThatThrownBy(() -> JourneyStepGraph.sortSteps(List.of(a, b)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cycle")
                    .hasMessageContaining("1")
                    .hasMessageContaining("2");
        }

        @Test
        @DisplayName("a self-referencing step is a cycle")
        void selfReference() {
            assertThatThrownBy(() -> JourneyStepGraph.sortSteps(List.of(step(1, 1))))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("an empty list sorts to an empty list")
        void emptyInput() {
            assertThat(JourneyStepGraph.sortSteps(List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("hasCycle")
    class CycleCheck {

        @Test
        @DisplayName("detects a cycle through the resolver form used by save-time validation")
        void detectsCycle() {
            // 1 -> 2 -> 3 -> 1
            assertThat(JourneyStepGraph.hasCycle(3, order -> switch (order) {
                case 1 -> List.of(3);
                case 2 -> List.of(1);
                default -> List.of(2);
            })).isTrue();
        }

        @Test
        @DisplayName("a diamond (branch and rejoin) is not a cycle")
        void diamondIsAcyclic() {
            assertThat(JourneyStepGraph.hasCycle(4, order -> switch (order) {
                case 2, 3 -> List.of(1);
                case 4 -> List.of(2, 3);
                default -> List.of();
            })).isFalse();
        }

        @Test
        @DisplayName("out-of-range parent references are ignored")
        void outOfRangeIgnored() {
            assertThat(JourneyStepGraph.hasCycle(2, order -> order == 2 ? List.of(99) : List.of()))
                    .isFalse();
        }
    }
}
