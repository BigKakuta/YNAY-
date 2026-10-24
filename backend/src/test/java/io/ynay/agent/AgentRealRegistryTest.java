package io.ynay.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.ynay.action.ActionDescriptor;
import io.ynay.action.ActionRegistry;
import io.ynay.agent.LlmClient.ToolCall;
import io.ynay.agent.LlmClient.ToolSpec;
import io.ynay.security.YnayUser;
import io.ynay.support.IntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/** The agent's tool filtering and execution against the real action catalogue. */
class AgentRealRegistryTest extends IntegrationTest {

    @Autowired
    ActionRegistry registry;

    @Autowired
    JsonMapper json;

    UUID newBudget(YnayUser owner) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into budget (id, name) values (?, 'Home')").param(id).update();
        jdbc.sql("insert into budget_member (budget_id, user_id, role) values (?, ?, 'OWNER')")
                .params(id, owner.userId()).update();
        return id;
    }

    @Test
    void offersOnlyBudgetScopedNonDestructiveMcpActions() {
        AgentProperties props = new AgentProperties();
        List<ToolSpec> specs = new AgentToolbox(registry, json, props).specs();
        Set<String> offered = specs.stream().map(ToolSpec::name).collect(Collectors.toSet());

        assertThat(offered).isNotEmpty();
        assertThat(specs).extracting(ToolSpec::name).isSorted();
        for (ActionDescriptor d : registry.list()) {
            boolean expected = d.mcp() && !d.destructive() && AgentToolbox.isBudgetScoped(d)
                    && !props.getExcludedTools().contains(d.name());
            assertThat(offered.contains(d.name())).as(d.name()).isEqualTo(expected);
        }
        // Schemas are passed through verbatim.
        for (ToolSpec spec : specs) {
            assertThat(spec.inputSchema()).isEqualTo(registry.find(spec.name()).orElseThrow().inputSchema());
        }
    }

    @Test
    void executesRealActionsInsideTheConversationBudgetOnly() {
        YnayUser alice = loginAsNewUser();
        UUID budget = newBudget(alice);
        UUID otherBudget = newBudget(createUser("bob-" + UUID.randomUUID().toString().substring(0, 8)));
        AgentToolbox toolbox = new AgentToolbox(registry, json, new AgentProperties());

        var ok = toolbox.execute(new ToolCall("1", "report_income_vs_expense",
                json.readTree("{\"fromMonth\":\"2026-09\",\"toMonth\":\"2026-10\"}"), ""), budget);
        assertThat(ok.result().isError()).as(ok.result().content()).isFalse();
        assertThat(ok.log().input().path("budgetId").asString()).isEqualTo(budget.toString());

        var crossBudget = toolbox.execute(new ToolCall("2", "report_income_vs_expense",
                json.readTree("{\"budgetId\":\"" + otherBudget + "\",\"fromMonth\":\"2026-09\",\"toMonth\":\"2026-10\"}"),
                ""), budget);
        assertThat(crossBudget.result().isError()).isTrue();
        assertThat(crossBudget.log().output().path("error").path("code").asString()).isEqualTo("FORBIDDEN");

        // Even as the conversation budget, a budget the user is not a member of stays invisible.
        var notMember = toolbox.execute(new ToolCall("3", "report_income_vs_expense",
                json.readTree("{\"fromMonth\":\"2026-09\",\"toMonth\":\"2026-10\"}"), ""), otherBudget);
        assertThat(notMember.result().isError()).isTrue();
        assertThat(notMember.log().output().path("error").path("code").asString()).isEqualTo("NOT_FOUND");

        var invalid = toolbox.execute(new ToolCall("4", "report_income_vs_expense",
                json.readTree("{\"fromMonth\":\"October\"}"), ""), budget);
        assertThat(invalid.result().isError()).isTrue();
        assertThat(invalid.log().output().path("error").path("code").asString()).isEqualTo("INVALID_INPUT");
    }
}
