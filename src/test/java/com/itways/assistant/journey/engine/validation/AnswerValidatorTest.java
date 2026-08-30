package com.itways.assistant.journey.engine.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules a USER_INPUT answer is held to on the server.
 *
 * <p>
 * These matter more than they look: until this class existed the same journey
 * was strict inside the web widget and completely unchecked on Telegram,
 * WhatsApp and any direct POST — the channels with no client to enforce
 * anything were exactly the ones enforcing nothing. Every case here mirrors a
 * rule {@code ui/form.ts} applies in the browser, so the two cannot drift into
 * disagreeing about what a valid answer is.
 */
@DisplayName("AnswerValidator")
class AnswerValidatorTest {

    private static Map<String, Object> field(String name, String type, Map<String, Object> validations) {
        return Map.of("name", name, "type", type, "label", name, "validations", validations);
    }

    @Nested
    @DisplayName("when nothing is declared")
    class Undeclared {

        @Test
        @DisplayName("a step with no fields accepts anything — FREE_TEXT is not a form")
        void noFieldsNoRules() {
            assertThat(AnswerValidator.validate("anything at all", null, null)).isEmpty();
            assertThat(AnswerValidator.validate(Map.of("x", 1), List.of(), null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("required")
    class Required {

        @Test
        @DisplayName("a missing, empty or whitespace value fails a required field")
        void missingValueFails() {
            Object fields = List.of(field("email", "text", Map.of("required", true)));

            assertThat(AnswerValidator.validate(Map.of(), fields, null))
                    .singleElement()
                    .extracting(AnswerValidator.FieldError::messageKey)
                    .isEqualTo("step.input.error.required");
            assertThat(AnswerValidator.validate(Map.of("email", "   "), fields, null)).hasSize(1);
        }

        @Test
        @DisplayName("required is read from the string 'true' the console persists, not only the boolean")
        void requiredAcceptsStringForm() {
            Object fields = List.of(field("email", "text", Map.of("required", "true")));

            assertThat(AnswerValidator.validate(Map.of(), fields, null)).hasSize(1);
        }

        @Test
        @DisplayName("a field a rule can hide is never required — the client legitimately omits it")
        void conditionalFieldsAreExempt() {
            // The engine cannot re-evaluate a form's visibility rules without the
            // form state that produced them, so enforcing required on a field a
            // rule governs would reject exactly the forms that use rules.
            Object fields = List.of(field("vatNumber", "text", Map.of("required", true)));
            Object rules = List.of(Map.of("if", "kind == 'business'",
                    "then", Map.of("field", "vatNumber", "action", "show")));

            assertThat(AnswerValidator.validate(Map.of(), fields, rules)).isEmpty();
        }
    }

    @Nested
    @DisplayName("formats")
    class Formats {

        @Test
        @DisplayName("an address without an @ or a domain is not an email")
        void emailShape() {
            Object fields = List.of(field("email", "email", Map.of()));

            assertThat(AnswerValidator.validate(Map.of("email", "sarah@example.com"), fields, null)).isEmpty();
            assertThat(AnswerValidator.validate(Map.of("email", "sarah at example"), fields, null))
                    .singleElement()
                    .extracting(AnswerValidator.FieldError::messageKey)
                    .isEqualTo("step.input.error.email");
        }

        @Test
        @DisplayName("a number field rejects text and honours min and max")
        void numberBounds() {
            Object fields = List.of(field("age", "number", Map.of("min", 18, "max", 120)));

            assertThat(AnswerValidator.validate(Map.of("age", 30), fields, null)).isEmpty();
            assertThat(AnswerValidator.validate(Map.of("age", "not a number"), fields, null))
                    .singleElement().extracting(AnswerValidator.FieldError::messageKey)
                    .isEqualTo("step.input.error.number");
            assertThat(AnswerValidator.validate(Map.of("age", 17), fields, null))
                    .singleElement().extracting(AnswerValidator.FieldError::messageKey)
                    .isEqualTo("step.input.error.min");
            assertThat(AnswerValidator.validate(Map.of("age", 121), fields, null))
                    .singleElement().extracting(AnswerValidator.FieldError::messageKey)
                    .isEqualTo("step.input.error.max");
        }

        @Test
        @DisplayName("a whole-number bound reads as 18 in the message, not 18.0")
        void boundsReadNaturally() {
            Object fields = List.of(field("age", "number", Map.of("min", 18)));

            assertThat(AnswerValidator.validate(Map.of("age", 3), fields, null))
                    .singleElement().extracting(e -> e.args()[0])
                    .isEqualTo("18");
        }

        @Test
        @DisplayName("length bounds apply to the text form of the value")
        void lengthBounds() {
            Object fields = List.of(field("code", "text", Map.of("minLength", 4, "maxLength", 6)));

            assertThat(AnswerValidator.validate(Map.of("code", "ABCD"), fields, null)).isEmpty();
            assertThat(AnswerValidator.validate(Map.of("code", "AB"), fields, null))
                    .singleElement().extracting(AnswerValidator.FieldError::messageKey)
                    .isEqualTo("step.input.error.minLength");
            assertThat(AnswerValidator.validate(Map.of("code", "ABCDEFGH"), fields, null))
                    .singleElement().extracting(AnswerValidator.FieldError::messageKey)
                    .isEqualTo("step.input.error.maxLength");
        }

        @Test
        @DisplayName("a pattern matches the whole answer, so four digits inside a sentence fail")
        void patternIsAnchored() {
            // Unanchored, [0-9]{4} would pass on "my pin is 1234 by the way",
            // which is not what an author writing that expression means.
            Object fields = List.of(field("pin", "text", Map.of("pattern", "[0-9]{4}")));

            assertThat(AnswerValidator.validate(Map.of("pin", "1234"), fields, null)).isEmpty();
            assertThat(AnswerValidator.validate(Map.of("pin", "my pin is 1234"), fields, null))
                    .singleElement().extracting(AnswerValidator.FieldError::messageKey)
                    .isEqualTo("step.input.error.pattern");
        }

        @Test
        @DisplayName("an uncompilable pattern is treated as satisfied rather than blocking every answer")
        void brokenPatternDoesNotTrapTheUser() {
            Object fields = List.of(field("pin", "text", Map.of("pattern", "[0-9")));

            assertThat(AnswerValidator.validate(Map.of("pin", "anything"), fields, null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("answers that are not maps")
    class ScalarAnswers {

        @Test
        @DisplayName("a plain-text reply fills a one-field form — messaging channels have no form to submit")
        void singleFieldTakesScalar() {
            Object fields = List.of(field("email", "email", Map.of("required", true)));

            assertThat(AnswerValidator.validate("sarah@example.com", fields, null)).isEmpty();
            assertThat(AnswerValidator.validate("nonsense", fields, null))
                    .singleElement().extracting(AnswerValidator.FieldError::messageKey)
                    .isEqualTo("step.input.error.email");
        }

        @Test
        @DisplayName("a scalar cannot answer a multi-field form, and guessing which field it meant is worse")
        void multiFieldRejectsScalar() {
            Object fields = List.of(
                    field("first", "text", Map.of()),
                    field("last", "text", Map.of()));

            assertThat(AnswerValidator.validate("Sarah", fields, null))
                    .singleElement().extracting(AnswerValidator.FieldError::messageKey)
                    .isEqualTo("step.input.error.structured");
        }
    }

    @Nested
    @DisplayName("reporting")
    class Reporting {

        @Test
        @DisplayName("every broken field is reported at once, so the user fixes the form in one pass")
        void reportsAllFailures() {
            Object fields = List.of(
                    field("email", "email", Map.of("required", true)),
                    field("age", "number", Map.of("min", 18)));

            List<AnswerValidator.FieldError> errors =
                    AnswerValidator.validate(Map.of("email", "nope", "age", 4), fields, null);

            assertThat(errors).hasSize(2)
                    .extracting(AnswerValidator.FieldError::field)
                    .containsExactlyInAnyOrder("email", "age");
        }

        @Test
        @DisplayName("an error carries the field name for highlighting and a label for the sentence")
        void errorNamesTheField() {
            Object fields = List.of(Map.of("name", "vat", "type", "text",
                    "label", "VAT number", "validations", Map.of("required", true)));

            assertThat(AnswerValidator.validate(Map.of(), fields, null))
                    .singleElement()
                    .satisfies(error -> {
                        assertThat(error.field()).isEqualTo("vat");
                        assertThat(error.label()).isEqualTo("VAT number");
                    });
        }
    }
}
