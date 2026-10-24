package io.ynay.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.ynay.security.YnayUser;
import io.ynay.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** {@code /api/agent} through the full application (security filters, Postgres) with fake LLM and actions. */
@AutoConfigureMockMvc
@Import(AgentControllerTest.FakeActions.class)
class AgentControllerTest extends IntegrationTest {

    static final FakeLlmServer llm = new FakeLlmServer();

    @DynamicPropertySource
    static void agentProperties(DynamicPropertyRegistry registry) {
        registry.add("ynay.agent.provider", () -> "anthropic");
        registry.add("ynay.agent.api-key", () -> "test-key");
        registry.add("ynay.agent.base-url", llm::baseUrl);
        registry.add("ynay.agent.max-input-chars", () -> "1000");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeActions {
        @Bean
        @Primary
        FakeActionRegistry fakeActionRegistry() {
            return new FakeActionRegistry();
        }
    }

    @AfterAll
    static void stopLlm() {
        llm.close();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeActionRegistry registry;

    YnayUser user;
    UUID budgetId;

    @BeforeEach
    void setUp() {
        llm.reset();
        registry.reset();
        user = createUser("agent-" + UUID.randomUUID().toString().substring(0, 8));
        budgetId = UUID.randomUUID();
        jdbc.sql("insert into budget (id, name) values (?, 'Home')").param(budgetId).update();
        jdbc.sql("insert into budget_member (budget_id, user_id, role) values (?, ?, 'OWNER')")
                .params(budgetId, user.userId()).update();
    }

    Authentication auth(YnayUser u) {
        var auth = new TestingAuthenticationToken(u, null, "ROLE_USER");
        auth.setAuthenticated(true);
        return auth;
    }

    String chatBody(UUID budget, String text) {
        return "{\"budgetId\":\"" + budget + "\",\"messages\":[{\"role\":\"user\",\"content\":"
                + AgentServiceTest.JSON.writeValueAsString(text) + "}]}";
    }

    @Test
    void statusReportsConfiguration() throws Exception {
        mvc.perform(get("/api/agent/status").with(authentication(auth(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.provider").value("anthropic"))
                .andExpect(jsonPath("$.model").value("claude-opus-5-5"))
                .andExpect(jsonPath("$.reason").doesNotExist());
    }

    @Test
    void chatRunsToolsAsTheLoggedInUser() throws Exception {
        llm.respond(AgentServiceTest.anthropic("tool_use",
                        AgentServiceTest.toolUse("t1", "list_accounts", "{\"budgetId\":\"" + budgetId + "\"}")))
                .respond(AgentServiceTest.anthropic("end_turn", AgentServiceTest.text("Which account?")));

        mvc.perform(post("/api/agent/chat").with(authentication(auth(user))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatBody(budgetId, "10/01/2026,Coffee,-3.50")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Which account?"))
                .andExpect(jsonPath("$.toolCalls[0].name").value("list_accounts"))
                .andExpect(jsonPath("$.toolCalls[0].isError").value(false))
                .andExpect(jsonPath("$.toolCalls[0].input.budgetId").value(budgetId.toString()))
                .andExpect(jsonPath("$.toolCalls[0].output[0].name").value("Checking"));

        assertThat(registry.invocations()).singleElement()
                .satisfies(i -> assertThat(i.userId()).isEqualTo(user.userId()));
        assertThat(llm.requests()).hasSize(2);
    }

    @Test
    void budgetsOfOtherUsersAreNotFound() throws Exception {
        YnayUser stranger = createUser("stranger-" + UUID.randomUUID().toString().substring(0, 8));

        mvc.perform(post("/api/agent/chat").with(authentication(auth(stranger))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatBody(budgetId, "hi")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(llm.requests()).isEmpty();
    }

    @Test
    void oversizedConversationIsRejected() throws Exception {
        mvc.perform(post("/api/agent/chat").with(authentication(auth(user))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatBody(budgetId, "x".repeat(1001))))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"));

        assertThat(llm.requests()).isEmpty();
    }

    @Test
    void malformedRequestIsRejected() throws Exception {
        mvc.perform(post("/api/agent/chat").with(authentication(auth(user))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"budgetId\":\"not-a-uuid\",\"messages\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void upstreamFailureIs502() throws Exception {
        llm.respond(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}",
                Map.of());

        mvc.perform(post("/api/agent/chat").with(authentication(auth(user))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatBody(budgetId, "hi")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("LLM_ERROR"));
    }
}
