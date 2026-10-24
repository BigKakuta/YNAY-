package io.ynay.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ynay.action.ActionRegistry;
import io.ynay.agent.AgentService.ChatRequest;
import io.ynay.agent.AgentService.ChatResponse;
import io.ynay.agent.LlmClient.ChatMessage;
import io.ynay.budget.BudgetAccess;
import io.ynay.security.YnayUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** End-to-end tests of the tool loop against a scripted fake LLM server, for both providers. */
class AgentServiceTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID BUDGET = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID OTHER_BUDGET = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC);

    static FakeLlmServer server;
    final FakeActionRegistry registry = new FakeActionRegistry();
    final YnayUser user = new YnayUser(UUID.randomUUID(), "alice");

    @BeforeAll
    static void startServer() {
        server = new FakeLlmServer();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @BeforeEach
    void login() {
        server.reset();
        var auth = new TestingAuthenticationToken(user, null, "ROLE_USER");
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    AgentProperties props(String provider) {
        AgentProperties p = new AgentProperties();
        p.setProvider(provider);
        p.setApiKey("test-key");
        p.setBaseUrl(server.baseUrl() + (provider.equals("openai") ? "/v1" : ""));
        if (provider.equals("openai")) {
            p.setModel("qwen3:14b");
        }
        return p;
    }

    AgentService service(AgentProperties p) {
        var beans = new StaticListableBeanFactory();
        beans.addBean("registry", registry);
        return new AgentService(p, JSON, beans.getBeanProvider(ActionRegistry.class),
                beans.getBeanProvider(BudgetAccess.class), CLOCK, null);
    }

    static ChatRequest userSays(String text) {
        return new ChatRequest(BUDGET, List.of(new ChatMessage("user", text)));
    }

    static String anthropic(String stopReason, String... blocks) {
        return """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "stop_reason":"%s","content":[%s],
                 "usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}
                """.formatted(stopReason, String.join(",", blocks));
    }

    static String toolUse(String id, String name, String inputJson) {
        return "{\"type\":\"tool_use\",\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"input\":" + inputJson + "}";
    }

    static String text(String t) {
        return "{\"type\":\"text\",\"text\":" + JSON.writeValueAsString(t) + "}";
    }

    static final String THINKING = "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig-abc\"}";

    static String importInput(UUID budget, int lines) {
        List<String> txns = new ArrayList<>();
        for (int i = 0; i < lines; i++) {
            txns.add("{\"date\":\"2026-10-0" + (i + 1) + "\",\"amount\":-12340,\"payeeName\":\"Coffee\"}");
        }
        return "{" + (budget == null ? "" : "\"budgetId\":\"" + budget + "\",") + "\"accountId\":\""
                + FakeActionRegistry.ACCOUNT_ID + "\",\"transactions\":[" + String.join(",", txns) + "]}";
    }

    @Nested
    class Anthropic {

        @Test
        void runsToolLoopEnforcesBudgetAndEchoesTranscript() {
            server.respond(anthropic("tool_use", THINKING,
                            toolUse("t1", "list_accounts", "{\"budgetId\":\"" + BUDGET + "\"}")))
                    .respond(anthropic("tool_use",
                            toolUse("t2", "import_transactions", importInput(OTHER_BUDGET, 1)),
                            toolUse("t3", "import_transactions", importInput(null, 2))))
                    .respond(anthropic("end_turn", text("Imported 2 transactions into Checking.")));

            ChatResponse response = service(props("anthropic")).chat(userSays("Date,Amount\n10/01,-12.34"));

            assertThat(response.reply()).isEqualTo("Imported 2 transactions into Checking.");
            assertThat(response.toolCalls()).extracting(AgentService.ToolCallLog::name)
                    .containsExactly("list_accounts", "import_transactions", "import_transactions");
            assertThat(response.toolCalls()).extracting(AgentService.ToolCallLog::isError)
                    .containsExactly(false, true, false);
            assertThat(response.toolCalls().get(1).output().path("error").path("code").asString())
                    .isEqualTo("FORBIDDEN");
            assertThat(response.toolCalls().get(2).output().path("imported").asInt()).isEqualTo(2);

            // Only the allowed calls reached the registry, as the logged-in user, with the budget injected.
            assertThat(registry.invocations()).extracting(FakeActionRegistry.Invocation::name)
                    .containsExactly("list_accounts", "import_transactions");
            assertThat(registry.invocations()).allSatisfy(i -> {
                assertThat(i.userId()).isEqualTo(user.userId());
                assertThat(i.args().path("budgetId").asString()).isEqualTo(BUDGET.toString());
            });

            List<FakeLlmServer.Recorded> requests = server.requests();
            assertThat(requests).hasSize(3);
            FakeLlmServer.Recorded first = requests.getFirst();
            assertThat(first.path()).isEqualTo("/v1/messages");
            assertThat(first.header("x-api-key")).isEqualTo("test-key");
            assertThat(first.header("anthropic-version")).isEqualTo("2026-10-21");
            assertThat(first.header("anthropic-beta")).isEqualTo("server-side-fallback-2026-07-01");

            JsonNode body = first.body();
            assertThat(body.path("model").asString()).isEqualTo("claude-opus-5-5");
            assertThat(body.path("max_tokens").asInt()).isEqualTo(16000);
            assertThat(body.path("output_config").path("effort").asString()).isEqualTo("medium");
            assertThat(body.path("fallbacks").asString()).isEqualTo("default");
            assertThat(body.has("temperature")).isFalse();
            assertThat(body.has("thinking")).isFalse();
            assertThat(body.path("cache_control").path("type").asString()).isEqualTo("ephemeral");
            assertThat(body.path("system").get(0).path("text").asString()).isEqualTo(AgentPrompts.STABLE);
            assertThat(body.path("system").get(0).path("cache_control").path("type").asString()).isEqualTo("ephemeral");
            assertThat(body.path("system").get(1).path("text").asString())
                    .contains(BUDGET.toString()).contains("2026-10-24");
            assertThat(body.path("system").get(1).has("cache_control")).isFalse();
            assertThat(names(body.path("tools"), "name")).containsExactly(
                    "create_transaction", "get_budget", "import_transactions",
                    "list_accounts", "list_categories", "list_payees");
            assertThat(body.path("tools").get(0).path("input_schema").path("properties").has("budgetId")).isTrue();
            assertThat(body.path("messages")).hasSize(1);

            // Iteration 2: assistant content echoed verbatim (thinking signature included), then the result.
            JsonNode second = requests.get(1).body().path("messages");
            assertThat(second).hasSize(3);
            assertThat(second.get(1).path("role").asString()).isEqualTo("assistant");
            assertThat(second.get(1).path("content").get(0).path("signature").asString()).isEqualTo("sig-abc");
            JsonNode result1 = second.get(2).path("content").get(0);
            assertThat(result1.path("type").asString()).isEqualTo("tool_result");
            assertThat(result1.path("tool_use_id").asString()).isEqualTo("t1");
            assertThat(result1.path("content").asString()).contains("Checking");
            // Stable prefix is byte-identical between iterations (prompt caching).
            assertThat(requests.get(1).body().path("system")).isEqualTo(body.path("system"));
            assertThat(requests.get(1).body().path("tools")).isEqualTo(body.path("tools"));

            // Iteration 3: both results of the parallel calls in one user message.
            JsonNode third = requests.get(2).body().path("messages");
            JsonNode results = third.get(third.size() - 1).path("content");
            assertThat(results).hasSize(2);
            assertThat(results.get(0).path("tool_use_id").asString()).isEqualTo("t2");
            assertThat(results.get(0).path("is_error").asBoolean()).isTrue();
            assertThat(results.get(0).path("content").asString()).contains(BUDGET.toString());
            assertThat(results.get(1).path("tool_use_id").asString()).isEqualTo("t3");
            assertThat(results.get(1).has("is_error")).isFalse();
        }

        @Test
        void stopsAtIterationCap() {
            server.respondForever(anthropic("tool_use",
                    toolUse("t1", "list_accounts", "{\"budgetId\":\"" + BUDGET + "\"}")));
            AgentProperties p = props("anthropic");
            p.setMaxIterations(3);

            ChatResponse response = service(p).chat(userSays("import"));

            assertThat(server.requests()).hasSize(3);
            assertThat(response.toolCalls()).hasSize(3);
            assertThat(response.reply()).contains("Stopped after 3 model calls");
        }

        @Test
        void unknownAndExcludedToolsAreRejected() {
            server.respond(anthropic("tool_use",
                            toolUse("t1", "delete_budget", "{\"budgetId\":\"" + BUDGET + "\"}"),
                            toolUse("t2", "delete_transaction", "{\"budgetId\":\"" + BUDGET + "\"}"),
                            toolUse("t3", "create_api_token", "{}")))
                    .respond(anthropic("end_turn", text("ok")));

            ChatResponse response = service(props("anthropic")).chat(userSays("delete everything"));

            assertThat(response.toolCalls()).allSatisfy(c -> {
                assertThat(c.isError()).isTrue();
                assertThat(c.output().path("error").path("code").asString()).isEqualTo("NOT_AVAILABLE");
            });
            assertThat(registry.invocations()).isEmpty();
        }

        @Test
        void destructiveToolsCanBeEnabled() {
            server.respond(anthropic("end_turn", text("hi")));
            AgentProperties p = props("anthropic");
            p.setAllowDestructive(true);

            service(p).chat(userSays("hi"));

            assertThat(names(server.requests().getFirst().body().path("tools"), "name"))
                    .contains("delete_transaction")
                    .doesNotContain("delete_budget");
        }

        @Test
        void retriesOverloadedResponses() {
            server.respond(529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}",
                            Map.of("retry-after", "0"))
                    .respond(anthropic("end_turn", text("done")));

            assertThat(service(props("anthropic")).chat(userSays("hi")).reply()).isEqualTo("done");
            assertThat(server.requests()).hasSize(2);
        }

        @Test
        void providerErrorsBecome502() {
            server.respond(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                    + "\"message\":\"invalid x-api-key\"}}", Map.of());

            assertThatThrownBy(() -> service(props("anthropic")).chat(userSays("hi")))
                    .isInstanceOfSatisfying(AgentException.class, e -> {
                        assertThat(e.httpStatus()).isEqualTo(502);
                        assertThat(e.getMessage()).contains("HTTP 401").contains("invalid x-api-key")
                                .doesNotContain("test-key");
                    });
        }

        @Test
        void truncatedToolCallsAreNotExecuted() {
            server.respond(anthropic("max_tokens", toolUse("t1", "import_transactions", importInput(BUDGET, 3))))
                    .respond(anthropic("end_turn", text("I will send smaller batches.")));

            ChatResponse response = service(props("anthropic")).chat(userSays("big csv"));

            assertThat(registry.invocations()).isEmpty();
            JsonNode last = server.requests().get(1).body().path("messages");
            JsonNode result = last.get(last.size() - 1).path("content").get(0);
            assertThat(result.path("is_error").asBoolean()).isTrue();
            assertThat(result.path("content").asString()).contains("OUTPUT_TRUNCATED");
            assertThat(response.reply()).isEqualTo("I will send smaller batches.");
        }

        @Test
        void refusalEndsTheTurn() {
            server.respond(anthropic("refusal"));

            ChatResponse response = service(props("anthropic")).chat(userSays("hi"));

            assertThat(response.reply()).contains("declined");
            assertThat(server.requests()).hasSize(1);
        }

        @Test
        void historyIsNormalized() {
            server.respond(anthropic("end_turn", text("ok")));
            var request = new ChatRequest(BUDGET, List.of(
                    new ChatMessage("assistant", "Hi! Paste a statement."),
                    new ChatMessage("user", "part 1"),
                    new ChatMessage("user", "part 2"),
                    new ChatMessage("assistant", "Which account?"),
                    new ChatMessage("user", ""),
                    new ChatMessage("user", "Checking")));

            service(props("anthropic")).chat(request);

            JsonNode messages = server.requests().getFirst().body().path("messages");
            assertThat(names(messages, "role")).containsExactly("user", "assistant", "user");
            assertThat(messages.get(0).path("content").asString()).isEqualTo("part 1\n\npart 2");
        }

        @Test
        void fallbacksAndEffortFollowConfiguration() {
            server.respond(anthropic("end_turn", text("ok")));
            AgentProperties p = props("anthropic");
            p.setModel("claude-haiku-4-5");
            p.setEffort("");

            service(p).chat(userSays("hi"));

            FakeLlmServer.Recorded r = server.requests().getFirst();
            assertThat(r.body().path("model").asString()).isEqualTo("claude-haiku-4-5");
            assertThat(r.body().has("fallbacks")).isFalse();
            assertThat(r.body().has("output_config")).isFalse();
            assertThat(r.header("anthropic-beta")).isNull();
        }
    }

    @Nested
    class OpenAi {

        static String completion(String finish, String content, String... toolCalls) {
            return """
                    {"id":"chatcmpl-1","object":"chat.completion","model":"qwen3:14b",
                     "choices":[{"index":0,"finish_reason":"%s",
                       "message":{"role":"assistant","content":%s,"tool_calls":[%s]}}],
                     "usage":{"prompt_tokens":10,"completion_tokens":5}}
                    """.formatted(finish, content == null ? "null" : JSON.writeValueAsString(content),
                    String.join(",", toolCalls));
        }

        static String call(String id, String name, String argumentsJson) {
            return "{\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\"" + name
                    + "\",\"arguments\":" + JSON.writeValueAsString(argumentsJson) + "}}";
        }

        @Test
        void runsToolLoopEnforcesBudgetAndEchoesTranscript() {
            server.respond(completion("tool_calls", null,
                            call("c1", "list_accounts", "{\"budgetId\":\"" + BUDGET + "\"}")))
                    .respond(completion("tool_calls", "Importing now.",
                            call("c2", "import_transactions", importInput(OTHER_BUDGET, 1)),
                            call("c3", "import_transactions", "{not json"),
                            call("c4", "import_transactions", importInput(BUDGET, 2))))
                    .respond(completion("stop", "<think>count rows</think>Imported 2 transactions."));

            ChatResponse response = service(props("openai")).chat(userSays("Date,Amount\n10/01,-12.34"));

            assertThat(response.reply()).isEqualTo("Imported 2 transactions.");
            assertThat(response.toolCalls()).extracting(AgentService.ToolCallLog::isError)
                    .containsExactly(false, true, true, false);
            assertThat(registry.invocations()).extracting(FakeActionRegistry.Invocation::name)
                    .containsExactly("list_accounts", "import_transactions");
            assertThat(registry.invocations()).allSatisfy(i -> assertThat(i.userId()).isEqualTo(user.userId()));

            List<FakeLlmServer.Recorded> requests = server.requests();
            assertThat(requests).hasSize(3);
            FakeLlmServer.Recorded first = requests.getFirst();
            assertThat(first.path()).isEqualTo("/v1/chat/completions");
            assertThat(first.header("authorization")).isEqualTo("Bearer test-key");
            JsonNode body = first.body();
            assertThat(body.path("model").asString()).isEqualTo("qwen3:14b");
            assertThat(body.path("max_tokens").asInt()).isEqualTo(16000);
            JsonNode messages = body.path("messages");
            assertThat(messages.get(0).path("role").asString()).isEqualTo("system");
            assertThat(messages.get(0).path("content").asString())
                    .startsWith(AgentPrompts.STABLE.strip()).contains(BUDGET.toString());
            assertThat(body.path("tools").get(0).path("type").asString()).isEqualTo("function");
            assertThat(body.path("tools").get(0).path("function").path("parameters").path("properties")
                    .has("budgetId")).isTrue();
            List<String> toolNames = new ArrayList<>();
            body.path("tools").forEach(t -> toolNames.add(t.path("function").path("name").asString()));
            assertThat(toolNames).contains("import_transactions").doesNotContain("delete_budget", "list_budgets");

            JsonNode third = requests.get(2).body().path("messages");
            // system, user, assistant(c1), tool(c1), assistant(c2..c4), tool x3
            assertThat(names(third, "role")).containsExactly(
                    "system", "user", "assistant", "tool", "assistant", "tool", "tool", "tool");
            assertThat(third.get(2).path("tool_calls").get(0).path("id").asString()).isEqualTo("c1");
            assertThat(third.get(3).path("tool_call_id").asString()).isEqualTo("c1");
            assertThat(third.get(5).path("content").asString()).startsWith("ERROR: ").contains("FORBIDDEN");
            assertThat(third.get(6).path("content").asString()).startsWith("ERROR: ").contains("JSON object");
            assertThat(third.get(7).path("content").asString()).contains("\"imported\":2");
        }

        @Test
        void stopsAtIterationCap() {
            server.respondForever(completion("tool_calls", null,
                    call("c1", "list_payees", "{\"budgetId\":\"" + BUDGET + "\"}")));
            AgentProperties p = props("openai");
            p.setMaxIterations(2);

            ChatResponse response = service(p).chat(userSays("import"));

            assertThat(server.requests()).hasSize(2);
            assertThat(response.reply()).contains("Stopped after 2 model calls");
        }

        @Test
        void localServerNeedsNoApiKey() {
            server.respond(completion("stop", "hello"));
            AgentProperties p = props("openai");
            p.setApiKey("");

            assertThat(service(p).chat(userSays("hi")).reply()).isEqualTo("hello");
            assertThat(server.requests().getFirst().header("authorization")).isNull();
        }
    }

    @Nested
    class Validation {

        @Test
        void rejectsOversizedInput() {
            AgentProperties p = props("anthropic");
            p.setMaxInputChars(100);

            assertThatThrownBy(() -> service(p).chat(userSays("x".repeat(101))))
                    .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.httpStatus()).isEqualTo(413));
            assertThat(server.requests()).isEmpty();
        }

        @Test
        void rejectsBadHistory() {
            AgentService service = service(props("anthropic"));
            assertThatThrownBy(() -> service.chat(new ChatRequest(BUDGET, List.of())))
                    .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.httpStatus()).isEqualTo(400));
            assertThatThrownBy(() -> service.chat(new ChatRequest(BUDGET,
                    List.of(new ChatMessage("user", "a"), new ChatMessage("assistant", "b")))))
                    .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.httpStatus()).isEqualTo(400));
            assertThatThrownBy(() -> service.chat(new ChatRequest(BUDGET, List.of(new ChatMessage("system", "a")))))
                    .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.httpStatus()).isEqualTo(400));
            assertThatThrownBy(() -> service.chat(new ChatRequest(null, List.of(new ChatMessage("user", "a")))))
                    .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.httpStatus()).isEqualTo(400));
        }

        @Test
        void disabledWithoutConfiguration() {
            AgentProperties p = new AgentProperties();
            AgentService service = service(p);

            AgentService.Status status = service.status();
            assertThat(status.enabled()).isFalse();
            assertThat(status.provider()).isEqualTo("anthropic");
            assertThat(status.model()).isEqualTo("claude-opus-5-5");
            assertThat(status.reason()).contains("YNAY_AGENT_API_KEY");
            assertThatThrownBy(() -> service.chat(userSays("hi")))
                    .isInstanceOfSatisfying(AgentException.class, e -> {
                        assertThat(e.httpStatus()).isEqualTo(503);
                        assertThat(e.code()).isEqualTo("AGENT_DISABLED");
                    });
        }

        @Test
        void configurationProblems() {
            AgentProperties p = new AgentProperties();
            p.setProvider("openai");
            assertThat(AgentService.configurationProblem(p)).contains("YNAY_AGENT_MODEL");
            p.setModel("llama3.1:8b");
            assertThat(AgentService.configurationProblem(p)).contains("YNAY_AGENT_BASE_URL");
            p.setBaseUrl("http://ollama:11434/v1");
            assertThat(AgentService.configurationProblem(p)).isNull();
            p.setBaseUrl("ollama:11434");
            assertThat(AgentService.configurationProblem(p)).contains("http(s)");
            p.setBaseUrl("");
            p.setProvider("gemini");
            assertThat(AgentService.configurationProblem(p)).contains("unknown");
        }

        @Test
        void anthropicEndpointAcceptsBaseWithOrWithoutV1() {
            assertThat(AnthropicClient.messagesEndpoint("https://api.anthropic.com").toString())
                    .isEqualTo("https://api.anthropic.com/v1/messages");
            assertThat(AnthropicClient.messagesEndpoint("https://proxy.lan/v1/").toString())
                    .isEqualTo("https://proxy.lan/v1/messages");
        }
    }

    static List<String> names(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path(field).asString()));
        return out;
    }
}
