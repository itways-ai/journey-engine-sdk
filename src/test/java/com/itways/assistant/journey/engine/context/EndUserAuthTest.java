package com.itways.assistant.journey.engine.context;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EndUserAuth is the security boundary that keeps a live bearer token out of
 * run history, the variable picker, CODE_SCRIPT and DATA_MAP prompts. If lift()
 * ever leaves the raw key behind, the credential is serialised everywhere the
 * variable map goes — so the stripping contract is pinned from every direction.
 */
@DisplayName("EndUserAuth")
class EndUserAuthTest {

    private static ExecutionContext freshContext() {
        return ExecutionContext.builder().variables(new HashMap<>()).build();
    }

    @Test
    @DisplayName("lifts a token found in the variable map into internals and strips the raw key")
    void liftsFromVariables() {
        ExecutionContext context = freshContext();
        context.getVariables().put(EndUserAuth.PARAM_USER_TOKEN, "jwt-abc");

        EndUserAuth.lift(context, null);

        assertThat(EndUserAuth.token(context)).isEqualTo("jwt-abc");
        assertThat(context.getVariables()).doesNotContainKey(EndUserAuth.PARAM_USER_TOKEN);
    }

    @Test
    @DisplayName("lifts a token supplied only in params, and strips it from the params too")
    void liftsFromParams() {
        ExecutionContext context = freshContext();
        Map<String, Object> params = new HashMap<>();
        params.put(EndUserAuth.PARAM_USER_TOKEN, "jwt-xyz");

        EndUserAuth.lift(context, params);

        assertThat(EndUserAuth.token(context)).isEqualTo("jwt-xyz");
        // Params are what ExecuterImpl serialises for the WAITING park; the raw
        // token must not survive there either.
        assertThat(params).doesNotContainKey(EndUserAuth.PARAM_USER_TOKEN);
    }

    @Test
    @DisplayName("the variable-map token wins over the params copy")
    void variableMapWins() {
        ExecutionContext context = freshContext();
        context.getVariables().put(EndUserAuth.PARAM_USER_TOKEN, "from-variables");
        Map<String, Object> params = new HashMap<>();
        params.put(EndUserAuth.PARAM_USER_TOKEN, "from-params");

        EndUserAuth.lift(context, params);

        assertThat(EndUserAuth.token(context)).isEqualTo("from-variables");
        assertThat(params).doesNotContainKey(EndUserAuth.PARAM_USER_TOKEN);
    }

    @Test
    @DisplayName("a blank or non-string token is not lifted, but the key is still stripped")
    void blankTokenStrippedNotLifted() {
        ExecutionContext context = freshContext();
        context.getVariables().put(EndUserAuth.PARAM_USER_TOKEN, "   ");

        EndUserAuth.lift(context, null);

        assertThat(EndUserAuth.token(context)).isNull();
        assertThat(context.getVariables()).doesNotContainKey(EndUserAuth.PARAM_USER_TOKEN);
    }

    @Test
    @DisplayName("anonymous runs lift nothing and token() reports null")
    void anonymousRun() {
        ExecutionContext context = freshContext();
        EndUserAuth.lift(context, new HashMap<>());
        assertThat(EndUserAuth.token(context)).isNull();
    }

    @Test
    @DisplayName("a resumed turn's fresh token replaces the one captured at start")
    void freshTokenReplacesOld() {
        // Hosts rotate short-lived JWTs (Vikunja's lives ~10 minutes); a resumed
        // run must adopt the new token rather than keep the stale one.
        ExecutionContext context = freshContext();
        context.getVariables().put(EndUserAuth.PARAM_USER_TOKEN, "turn-1");
        EndUserAuth.lift(context, null);

        Map<String, Object> resumeParams = new HashMap<>();
        resumeParams.put(EndUserAuth.PARAM_USER_TOKEN, "turn-2");
        EndUserAuth.lift(context, resumeParams);

        assertThat(EndUserAuth.token(context)).isEqualTo("turn-2");
    }
}
