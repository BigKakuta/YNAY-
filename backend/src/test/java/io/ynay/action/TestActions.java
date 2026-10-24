package io.ynay.action;

import io.ynay.security.CurrentUser;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Test-only actions exercising the framework. A {@link TestComponent}, so it is not picked up by
 * component scanning; tests opt in with {@code @Import(TestActions.class)}.
 */
@TestComponent
public class TestActions {

    public enum Color { RED, GREEN }

    public record Line(@ActionParam("Amount in milliunits") long amount,
                       @ActionParam(value = "Memo", required = false) String memo) {
    }

    public record EchoInput(
            @ActionParam("An id") UUID id,
            @ActionParam("A name") String name,
            @ActionParam("A date") LocalDate date,
            @ActionParam("A month") YearMonth month,
            @ActionParam(value = "A color", required = false) Color color,
            @ActionParam(value = "A flag", required = false) boolean flag,
            @ActionParam(value = "A count", required = false) Integer count,
            @ActionParam(value = "Lines", required = false) List<Line> lines) {
    }

    public record NameInput(@ActionParam("Username") String username) {
    }

    public record CheckedInput(@ActionParam("Positive number") int value) {
        public CheckedInput {
            if (value <= 0) {
                throw new IllegalArgumentException("must be positive");
            }
        }
    }

    private final JdbcClient jdbc;

    public TestActions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @McpAction(name = "test_echo", description = "Returns its input", readOnly = true)
    public EchoInput echo(EchoInput in) {
        return in;
    }

    @McpAction(name = "test_whoami", description = "Returns the caller", readOnly = true)
    public Map<String, Object> whoami() {
        return Map.of("userId", CurrentUser.id(), "username", CurrentUser.get().username());
    }

    @McpAction(name = "test_void", description = "Does nothing", idempotent = true)
    public void doNothing() {
    }

    @McpAction(name = "test_numbers", description = "Returns a list", readOnly = true)
    public List<Integer> numbers() {
        return List.of(1, 2, 3);
    }

    @McpAction(name = "test_insert_then_fail", description = "Inserts a user, then fails with CONFLICT")
    public void insertThenFail(NameInput in) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into app_user (id, oidc_issuer, oidc_subject, username) values (?, 'test', ?, ?)")
                .params(id, id.toString(), in.username())
                .update();
        throw ActionException.conflict("Rolled back on purpose");
    }

    @McpAction(name = "test_insert", description = "Inserts a user")
    public Map<String, Object> insert(NameInput in) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into app_user (id, oidc_issuer, oidc_subject, username) values (?, 'test', ?, ?)")
                .params(id, id.toString(), in.username())
                .update();
        return Map.of("id", id);
    }

    @McpAction(name = "test_checked", description = "Record constructor validation")
    public int checked(CheckedInput in) {
        return in.value();
    }

    @McpAction(name = "test_boom", description = "Fails unexpectedly")
    public void boom() {
        throw new IllegalStateException("secret internal detail");
    }

    @McpAction(name = "test_web_only", description = "Not exposed over MCP", mcp = false, destructive = true)
    public Map<String, Boolean> webOnly() {
        return Map.of("web", true);
    }
}
