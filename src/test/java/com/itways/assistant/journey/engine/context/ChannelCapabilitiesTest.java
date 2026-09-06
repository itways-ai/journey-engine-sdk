package com.itways.assistant.journey.engine.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.ExecutionStatus;

/**
 * A channel's limits ride in as a reserved start-param and must end up where
 * a journey cannot see them: the widget, the console and every existing
 * caller declare nothing and must keep behaving as if the channel could do
 * everything, while a phone call that declares {@code form=false} must be
 * honoured by the handlers and passed on to any journey it triggers.
 */
@DisplayName("ChannelCapabilities")
class ChannelCapabilitiesTest {

	private static ExecutionContext context() {
		ExecutionContext context = ExecutionContext.builder().variables(new HashMap<>())
				.status(ExecutionStatus.RUNNING).build();
		new VariableContext().ensureStructure(context);
		return context;
	}

	@Nested
	@DisplayName("lifting")
	class Lifting {

		@Test
		@DisplayName("a declaration in the params moves into internals and out of every author-visible surface")
		void liftsFromParams() {
			ExecutionContext context = context();
			Map<String, Object> params = new HashMap<>();
			params.put(ChannelCapabilities.PARAM_CAPABILITIES, Map.of("form", false, "links", "false", "transfer", true));

			ChannelCapabilities.lift(context, params);

			assertThat(ChannelCapabilities.supportsForm(context)).isFalse();
			assertThat(ChannelCapabilities.supports(context, ChannelCapabilities.LINKS)).isFalse();
			assertThat(ChannelCapabilities.supports(context, ChannelCapabilities.TRANSFER)).isTrue();
			assertThat(params).doesNotContainKey(ChannelCapabilities.PARAM_CAPABILITIES);
			assertThat(context.getVariables()).doesNotContainKey(ChannelCapabilities.PARAM_CAPABILITIES);
		}

		@Test
		@DisplayName("a declaration that leaked into the variable map is lifted from there too")
		void liftsFromVariables() {
			ExecutionContext context = context();
			context.getVariables().put(ChannelCapabilities.PARAM_CAPABILITIES, Map.of("form", false));

			ChannelCapabilities.lift(context, null);

			assertThat(ChannelCapabilities.supportsForm(context)).isFalse();
			assertThat(context.getVariables()).doesNotContainKey(ChannelCapabilities.PARAM_CAPABILITIES);
		}

		@Test
		@DisplayName("the reserved param never becomes an entity, even before lift runs")
		void reservedFromEntities() {
			ExecutionContext context = context();
			Map<String, Object> params = new HashMap<>();
			params.put(ChannelCapabilities.PARAM_CAPABILITIES, Map.of("form", false));
			params.put("city", "Amman");

			new VariableContext().mergeInputs(context, params);

			@SuppressWarnings("unchecked")
			Map<String, Object> inputs = (Map<String, Object>) context.getVariables().get("inputs");
			@SuppressWarnings("unchecked")
			Map<String, Object> entities = (Map<String, Object>) inputs.get("entities");
			assertThat(entities).containsKey("city").doesNotContainKey(ChannelCapabilities.PARAM_CAPABILITIES);
		}
	}

	@Nested
	@DisplayName("defaults")
	class Defaults {

		@Test
		@DisplayName("a channel that declared nothing can do everything — every existing caller is unchanged")
		void undeclaredMeansCapable() {
			ExecutionContext context = context();

			ChannelCapabilities.lift(context, new HashMap<>());

			assertThat(ChannelCapabilities.of(context)).isEmpty();
			assertThat(ChannelCapabilities.supportsForm(context)).isTrue();
			assertThat(ChannelCapabilities.supports(context, ChannelCapabilities.ATTACHMENTS)).isTrue();
			assertThat(ChannelCapabilities.supports(null, ChannelCapabilities.FORM)).isTrue();
		}

		@Test
		@DisplayName("a declaration lists what is missing: an unmentioned capability is still present")
		void unmentionedIsPresent() {
			ExecutionContext context = context();
			Map<String, Object> params = new HashMap<>();
			params.put(ChannelCapabilities.PARAM_CAPABILITIES, Map.of("form", false));

			ChannelCapabilities.lift(context, params);

			assertThat(ChannelCapabilities.supports(context, ChannelCapabilities.LINKS)).isTrue();
		}
	}

	@Test
	@DisplayName("a triggered child inherits the parent's declaration through its start-params")
	void inherit() {
		ExecutionContext parent = context();
		Map<String, Object> params = new HashMap<>();
		params.put(ChannelCapabilities.PARAM_CAPABILITIES, Map.of("form", false));
		ChannelCapabilities.lift(parent, params);
		Map<String, Object> childParams = new HashMap<>();

		ChannelCapabilities.inherit(childParams, parent);
		ExecutionContext child = context();
		ChannelCapabilities.lift(child, childParams);

		assertThat(ChannelCapabilities.supportsForm(child)).isFalse();

		Map<String, Object> nothing = new HashMap<>();
		ChannelCapabilities.inherit(nothing, context());
		assertThat(nothing).isEmpty();
	}
}
