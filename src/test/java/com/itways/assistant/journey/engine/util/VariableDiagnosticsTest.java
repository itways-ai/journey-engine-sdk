package com.itways.assistant.journey.engine.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The diagnostics stack is engine-global (thread-local) mutable state with a
 * manual open/close protocol. TRIGGER_JOURNEY nests child runs on the same
 * thread, so the frame-per-step isolation below is what keeps a child journey's
 * unresolved variables off its parent's TRIGGER_JOURNEY step.
 */
@DisplayName("VariableDiagnostics")
class VariableDiagnosticsTest {

    @AfterEach
    void cleanThread() {
        VariableDiagnostics.reset();
    }

    @Test
    @DisplayName("recording lands in the innermost frame only")
    void nestedFramesIsolate() {
        VariableDiagnostics.open();               // parent step frame
        VariableDiagnostics.recordUnresolved("parent.path");

        VariableDiagnostics.open();               // child step frame (nested run)
        VariableDiagnostics.recordUnresolved("child.path");
        assertThat(VariableDiagnostics.close()).containsExactly("child.path");

        // The parent frame is untouched by the child's recording.
        assertThat(VariableDiagnostics.close()).containsExactly("parent.path");
    }

    @Test
    @DisplayName("recording without an open frame is a silent no-op")
    void noFrameNoOp() {
        VariableDiagnostics.recordUnresolved("anything");
        // Placeholder resolution outside step execution (schema previews, tests)
        // must cost nothing and leak nothing.
        VariableDiagnostics.open();
        assertThat(VariableDiagnostics.close()).isEmpty();
    }

    @Test
    @DisplayName("duplicate paths collapse and insertion order is kept")
    void deduplicatedOrdered() {
        VariableDiagnostics.open();
        VariableDiagnostics.recordUnresolved("b");
        VariableDiagnostics.recordUnresolved("a");
        VariableDiagnostics.recordUnresolved(" b ");
        assertThat(VariableDiagnostics.close()).containsExactly("b", "a");
    }

    @Test
    @DisplayName("null and blank paths are ignored")
    void blankIgnored() {
        VariableDiagnostics.open();
        VariableDiagnostics.recordUnresolved(null);
        VariableDiagnostics.recordUnresolved("  ");
        assertThat(VariableDiagnostics.close()).isEmpty();
    }

    @Test
    @DisplayName("close without open returns empty rather than throwing")
    void closeWithoutOpen() {
        assertThat(VariableDiagnostics.close()).isEmpty();
    }

    @Test
    @DisplayName("reset discards every frame on the thread")
    void resetDiscardsAll() {
        VariableDiagnostics.open();
        VariableDiagnostics.open();
        VariableDiagnostics.recordUnresolved("x");
        VariableDiagnostics.reset();
        // After a reset the stack is empty: a fresh frame sees nothing.
        VariableDiagnostics.open();
        assertThat(VariableDiagnostics.close()).isEmpty();
    }
}
