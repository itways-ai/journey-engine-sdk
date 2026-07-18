package com.itways.assistant.journey.engine.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

class GraalSmokeTest {

    @Test
    void plainEvalWorks() {
        try (Context js = Context.create("js")) {
            Value r = js.eval("js", "1 + 1");
            assertEquals(2, r.asInt());
        }
    }

    @Test
    void jsonParseAndPropertyAccess() {
        String ctx = "{\"steps\":{\"3\":{\"output\":{\"status\":\"CANCELLED\"}}}}";
        String code = """
                (function() {
                  var __ctx = JSON.parse(__ctxJson);
                  var steps = __ctx.steps;
                  var data = steps['3'].output;
                  return String(data.status).toUpperCase();
                })();
                """;
        try (Context js = Context.newBuilder("js").allowHostAccess(HostAccess.ALL).build()) {
            js.getBindings("js").putMember("__ctxJson", ctx);
            Value r = js.eval("js", code);
            assertEquals("CANCELLED", r.asString());
        }
    }
}
