package com.itways.assistant.journey.engine.handler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.ChannelCapabilities;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.language.DecisionWords;
import com.itways.assistant.journey.engine.language.EngineMessages;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.ExecutionStatus;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;

/**
 * A STRUCTURED step on a channel with no form. Until this existed every
 * non-web channel was refused with "structured input required" on any form
 * with more than one field — WhatsApp and Telegram callers included. Now the
 * form is asked one field at a time, each answer validated by its own field's
 * rules, and the assembled map stored exactly as a widget's submission would
 * be, so nothing downstream can tell the channels apart.
 */
@DisplayName("UserInputStepHandler, one field at a time")
class UserInputFieldByFieldTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final VariableContext variableContext = new VariableContext();
	private final EngineMessages messages = new EngineMessages();
	private final UserInputStepHandler handler = new UserInputStepHandler(
			new EngineUtils(objectMapper), variableContext,
			new StepOutputSchemaHelper(objectMapper), messages, decisionWords());

	private static DecisionWords decisionWords() {
		DecisionWords words = new DecisionWords(new EngineMessages());
		words.load();
		return words;
	}

	private static final String FORM = """
			{"inputMode":"STRUCTURED","allowResubmit":false,"rules":[],
			 "fields":[
			   {"name":"title","label":"Task title","type":"text","validations":{"required":true}},
			   {"name":"priority","label":"Priority","type":"select",
			    "options":[{"label":"High","value":"3"},{"label":"Low","value":"1"}]},
			   {"name":"email","label":"Email","type":"email","validations":{"email":true}}
			 ]}""";

	private static JourneyStep step(String apiConfig) {
		JourneyStep step = new JourneyStep();
		step.setStepOrder(2);
		step.setStepName("Task details");
		step.setActionType("USER_INPUT");
		step.setMessage("Let us create the task.");
		step.setApiConfig(apiConfig);
		return step;
	}

	private ExecutionContext voiceContext() {
		ExecutionContext context = ExecutionContext.builder()
				.variables(new HashMap<>()).status(ExecutionStatus.RUNNING).build();
		variableContext.ensureStructure(context);
		Map<String, Object> params = new HashMap<>();
		params.put(ChannelCapabilities.PARAM_CAPABILITIES, Map.of("form", false));
		ChannelCapabilities.lift(context, params);
		return context;
	}

	private StepResult answer(JourneyStep step, ExecutionContext context, Object answer) {
		variableContext.getInputs(context).put("answer", answer);
		return handler.execute(step, context);
	}

	@Nested
	@DisplayName("the happy path")
	class HappyPath {

		@Test
		@DisplayName("asks each field in turn, matches a spoken choice to its value, and stores the whole form")
		void walksTheForm() {
			JourneyStep step = step(FORM);
			ExecutionContext context = voiceContext();

			StepResult first = handler.execute(step, context);
			assertThat(first.getStatus()).isEqualTo("WAITING");
			assertThat(first.getMessage()).isEqualTo("Let us create the task. 1 of 3: what is the Task title?");
			assertThat(first.getMetadata()).containsEntry("subStatus", "FIELD_BY_FIELD")
					.containsEntry("fieldIndex", 0).containsEntry("fieldCount", 3);

			StepResult second = answer(step, context, "Buy milk");
			assertThat(second.getStatus()).isEqualTo("WAITING");
			assertThat(second.getMessage()).isEqualTo("2 of 3: what is the Priority? The options are: High, Low.");

			StepResult third = answer(step, context, "high");
			assertThat(third.getStatus()).isEqualTo("WAITING");
			assertThat(third.getMessage()).isEqualTo("3 of 3: what is the Email?");

			StepResult done = answer(step, context, "sarah@example.com");
			assertThat(done.getStatus()).isEqualTo("SUCCESS");
			assertThat(done.getData()).isEqualTo(Map.of("title", "Buy milk", "priority", "3", "email", "sarah@example.com"));
			assertThat(context.getStepResults().get(2)).isEqualTo(done.getData());
			assertThat(context.getInternal(UserInputStepHandler.PARTIAL_PREFIX + 2)).isNull();
			assertThat(variableContext.getInputs(context)).doesNotContainKey("answer");
		}

		@Test
		@DisplayName("a form that arrives whole — a widget after all — takes the normal path")
		void wholeFormStillAccepted() {
			JourneyStep step = step(FORM);
			ExecutionContext context = voiceContext();

			StepResult done = answer(step, context, Map.of("title", "x", "priority", "1", "email", "a@b.co"));

			assertThat(done.getStatus()).isEqualTo("SUCCESS");
		}

		@Test
		@DisplayName("a channel that declared nothing is not asked field by field")
		void webUnchanged() {
			JourneyStep step = step(FORM);
			ExecutionContext context = ExecutionContext.builder()
					.variables(new HashMap<>()).status(ExecutionStatus.RUNNING).build();
			variableContext.ensureStructure(context);

			StepResult first = handler.execute(step, context);

			assertThat(first.getStatus()).isEqualTo("WAITING");
			assertThat(first.getMetadata()).containsEntry("subStatus", "DIRECT_FORM");
		}
	}

	@Nested
	@DisplayName("validation")
	class Validation {

		@Test
		@DisplayName("a bad answer re-asks the same field with the complaint; a good one moves on")
		void reasksOnError() {
			JourneyStep step = step(FORM);
			ExecutionContext context = voiceContext();
			handler.execute(step, context);
			answer(step, context, "Buy milk");
			answer(step, context, "low");

			StepResult bad = answer(step, context, "not-an-email");
			assertThat(bad.getStatus()).isEqualTo("WAITING");
			assertThat(bad.getMessage()).startsWith(messages.get(context.resolvedLanguage(), "step.userInput.fixErrors", "").trim())
					.endsWith("3 of 3: what is the Email?");
			assertThat(bad.getMetadata()).containsEntry("fieldIndex", 2);

			StepResult good = answer(step, context, "ok@example.com");
			assertThat(good.getStatus()).isEqualTo("SUCCESS");
		}

		@Test
		@DisplayName("a blank answer is not an answer")
		void blankReasks() {
			JourneyStep step = step(FORM);
			ExecutionContext context = voiceContext();
			handler.execute(step, context);

			StepResult blank = answer(step, context, "   ");

			assertThat(blank.getStatus()).isEqualTo("WAITING");
			assertThat(blank.getMessage()).contains(messages.get(context.resolvedLanguage(), "step.userInput.empty"))
					.endsWith("1 of 3: what is the Task title?");
		}

		@Test
		@DisplayName("three bad answers to one field end the run, and nothing partial is left behind")
		void givesUp() {
			JourneyStep step = step(FORM);
			ExecutionContext context = voiceContext();
			handler.execute(step, context);
			answer(step, context, "Buy milk");
			answer(step, context, "low");
			answer(step, context, "bad1");
			answer(step, context, "bad2");

			StepResult gaveUp = answer(step, context, "bad3");

			assertThat(gaveUp.getStatus()).isEqualTo("ERROR");
			assertThat(gaveUp.getUserMessage())
					.isEqualTo(messages.get(context.resolvedLanguage(), "step.userInput.tooManyAttempts"));
			assertThat(context.getInternal(UserInputStepHandler.PARTIAL_PREFIX + 2)).isNull();
		}
	}

	@Nested
	@DisplayName("what cannot be asked")
	class Unaskable {

		@Test
		@DisplayName("a file field ends the run with the localized explanation")
		void fileFieldUnsupported() {
			JourneyStep step = step("""
					{"inputMode":"STRUCTURED","fields":[
					  {"name":"title","label":"Title","type":"text"},
					  {"name":"scan","label":"Scan","type":"file"}]}""");
			ExecutionContext context = voiceContext();
			handler.execute(step, context);

			StepResult result = answer(step, context, "Contract");

			assertThat(result.getStatus()).isEqualTo("ERROR");
			assertThat(result.getUserMessage())
					.isEqualTo(messages.get(context.resolvedLanguage(), "step.userInput.fileUnsupported"));
		}

		@Test
		@DisplayName("conditional fields are skipped — the engine cannot evaluate their rules")
		void conditionalSkipped() {
			JourneyStep step = step("""
					{"inputMode":"STRUCTURED",
					 "rules":[{"when":{"field":"kind","equals":"other"},"then":{"field":"detail","show":true}}],
					 "fields":[
					   {"name":"kind","label":"Kind","type":"text"},
					   {"name":"detail","label":"Detail","type":"text"},
					   {"name":"who","label":"Who","type":"text"}]}""");
			ExecutionContext context = voiceContext();

			StepResult first = handler.execute(step, context);
			assertThat(first.getMetadata()).containsEntry("fieldCount", 2);
			answer(step, context, "bug");
			StepResult done = answer(step, context, "Sarah");

			assertThat(done.getStatus()).isEqualTo("SUCCESS");
			assertThat(done.getData()).isEqualTo(Map.of("kind", "bug", "who", "Sarah"));
		}

		@Test
		@DisplayName("a one-field form is not a sequence: the existing scalar path handles it")
		void singleFieldUnchanged() {
			JourneyStep step = step("""
					{"inputMode":"STRUCTURED","fields":[{"name":"title","label":"Title","type":"text"}]}""");
			ExecutionContext context = voiceContext();

			StepResult first = handler.execute(step, context);
			assertThat(first.getMetadata()).containsEntry("subStatus", "DIRECT_FORM");

			StepResult done = answer(step, context, "Buy milk");
			assertThat(done.getStatus()).isEqualTo("SUCCESS");
			assertThat(done.getData()).isEqualTo("Buy milk");
		}
	}

	@Test
	@DisplayName("the catalog says which channels a type works on — USER_INPUT is adapted on voice")
	void catalogAnnotated() {
		var registry = new com.itways.assistant.journey.engine.service.StepOutputRegistry(List.of(handler));

		var definitions = registry.getCatalog();

		assertThat(definitions).hasSize(1);
		assertThat(definitions.get(0).getChannels()).containsEntry("voice", "ADAPTED");
		assertThat(com.itways.assistant.journey.engine.util.ChannelSupport.voice("DOCUMENT_INSIGHT")).isEqualTo("UNSUPPORTED");
		assertThat(com.itways.assistant.journey.engine.util.ChannelSupport.voice("RESPONSE")).isEqualTo("NATIVE");
		assertThat(com.itways.assistant.journey.engine.util.ChannelSupport.voice("SOMETHING_NEW")).isEqualTo("NATIVE");
	}
}
