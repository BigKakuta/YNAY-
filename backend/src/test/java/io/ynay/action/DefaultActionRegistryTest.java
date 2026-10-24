package io.ynay.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Registry scanning, contract validation and invocation without a database. */
class DefaultActionRegistryTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RecordingTransactionManager txManager = new RecordingTransactionManager();

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(JsonMapper.class, () -> mapper)
                .withBean(PlatformTransactionManager.class, () -> txManager)
                .withBean(DefaultActionRegistry.class);
    }

    // ------------------------------------------------------------ fixtures

    public enum Mood { HAPPY, SAD }

    public record GreetInput(@ActionParam("Who to greet") String name,
                             @ActionParam(value = "Mood", required = false) Mood mood,
                             @ActionParam(value = "Times", required = false) int times,
                             @ActionParam(value = "Month", required = false) YearMonth month,
                             @ActionParam(value = "Ids", required = false) List<UUID> ids) {
    }

    public static class GreetActions {
        final List<GreetInput> calls = new ArrayList<>();

        @McpAction(name = "greet", description = "Greets someone", readOnly = true)
        public Map<String, Object> greet(GreetInput in) {
            calls.add(in);
            return Map.of("greeting", "Hello " + in.name(), "times", in.times());
        }

        @McpAction(name = "noop", description = "Nothing", idempotent = true, mcp = false)
        public void noop() {
        }

        @McpAction(name = "fail", description = "Fails", destructive = true)
        public void fail() throws Exception {
            throw new Exception("checked");
        }

        @McpAction(name = "not_found", description = "Fails with NOT_FOUND")
        public String notFound() {
            throw ActionException.notFound("Thing");
        }

        @McpAction(name = "nothing", description = "Returns null")
        public String nothing() {
            return null;
        }

        public void notAnAction() {
        }
    }

    public interface Api {
        String hello();
    }

    public static class ApiImpl implements Api {
        @Override
        @McpAction(name = "hello", description = "Says hello")
        public String hello() {
            return "hi";
        }
    }

    public static class OtherGreet {
        @McpAction(name = "greet", description = "Duplicate")
        public void greet() {
        }
    }

    public record A(String a) {
    }

    public static class TwoParams {
        @McpAction(name = "two", description = "Two params")
        public void two(A a, A b) {
        }
    }

    public static class NotARecord {
        @McpAction(name = "plain", description = "Plain param")
        public void plain(String s) {
        }
    }

    public static class BadName {
        @McpAction(name = "Bad-Name", description = "Bad name")
        public void bad() {
        }
    }

    public record MapInput(Map<String, String> values) {
    }

    public static class BadType {
        @McpAction(name = "bad_type", description = "Unsupported type")
        public void bad(MapInput in) {
        }
    }

    public static class NonPublic {
        @McpAction(name = "hidden", description = "Not public")
        void hidden() {
        }
    }

    // --------------------------------------------------------------- tests

    @Test
    void discoversActionsOnPlainCglibAndJdkProxiedBeans() {
        runner()
                .withBean("greetActions", GreetActions.class, () -> cglibProxy(new GreetActions()))
                .withBean("api", Api.class, () -> jdkProxy(new ApiImpl()))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    ActionRegistry registry = ctx.getBean(ActionRegistry.class);
                    assertThat(registry.list()).extracting(ActionDescriptor::name)
                            .containsExactly("fail", "greet", "hello", "noop", "not_found", "nothing");

                    ActionDescriptor greet = registry.find("greet").orElseThrow();
                    assertThat(greet.description()).isEqualTo("Greets someone");
                    assertThat(greet.readOnly()).isTrue();
                    assertThat(greet.destructive()).isFalse();
                    assertThat(greet.mcp()).isTrue();
                    assertThat(greet.title()).isEqualTo("Greet");
                    assertThat(greet.inputSchema().get("required").toString()).isEqualTo("[\"name\"]");
                    assertThat(registry.find("noop").orElseThrow().mcp()).isFalse();
                    assertThat(registry.find("fail").orElseThrow().destructive()).isTrue();
                    assertThat(registry.find("noop").orElseThrow().inputSchema())
                            .isEqualTo(InputModel.emptyObjectSchema());
                    assertThat(registry.find("missing")).isEmpty();

                    assertThat(registry.invoke("hello", null).stringValue()).isEqualTo("hi");
                    assertThat(registry.invoke("greet", json("{\"name\":\"Ann\"}")))
                            .isEqualTo(json("{\"greeting\":\"Hello Ann\",\"times\":0}"));
                });
    }

    @Test
    void descriptorsAreDefensiveCopies() {
        runner().withBean(GreetActions.class).run(ctx -> {
            ActionRegistry registry = ctx.getBean(ActionRegistry.class);
            registry.find("greet").orElseThrow().inputSchema().removeAll();
            registry.list().getFirst().inputSchema().removeAll();
            assertThat(registry.find("greet").orElseThrow().inputSchema().has("properties")).isTrue();
            assertThat(registry.find("fail").orElseThrow().inputSchema().has("properties")).isTrue();
        });
    }

    @Test
    void invokesInsideATransaction() {
        runner().withBean(GreetActions.class).run(ctx -> {
            ActionRegistry registry = ctx.getBean(ActionRegistry.class);
            registry.invoke("noop", null);
            assertThat(txManager.commits).isEqualTo(1);
            assertThatThrownBy(() -> registry.invoke("not_found", null)).isInstanceOf(ActionException.class);
            assertThat(txManager.rollbacks).isEqualTo(1);
        });
    }

    @Test
    void invocationResults() {
        runner().withBean(GreetActions.class).run(ctx -> {
            ActionRegistry registry = ctx.getBean(ActionRegistry.class);
            assertThat(registry.invoke("noop", null)).isEqualTo(json("{\"ok\":true}"));
            assertThat(registry.invoke("noop", json("{}"))).isEqualTo(json("{\"ok\":true}"));
            assertThat(registry.invoke("nothing", null).isNull()).isTrue();

            GreetActions bean = ctx.getBean(GreetActions.class);
            registry.invoke("greet", json("""
                    {"name":"Bo","mood":"SAD","times":3,"month":"2026-10",
                     "ids":["8a5f1c6e-6f43-4a51-9d7e-3f1f0e7c9a10"]}"""));
            assertThat(bean.calls.getLast()).isEqualTo(new GreetInput("Bo", Mood.SAD, 3, YearMonth.of(2026, 10),
                    List.of(UUID.fromString("8a5f1c6e-6f43-4a51-9d7e-3f1f0e7c9a10"))));
            registry.invoke("greet", json("{\"name\":\"Cy\",\"times\":null}"));
            assertThat(bean.calls.getLast().times()).isZero();
        });
    }

    @Test
    void invocationErrors() {
        runner().withBean(GreetActions.class).run(ctx -> {
            ActionRegistry registry = ctx.getBean(ActionRegistry.class);
            assertError(() -> registry.invoke("missing", null), ActionException.Code.NOT_FOUND, "Action 'missing' not found");
            assertError(() -> registry.invoke("not_found", null), ActionException.Code.NOT_FOUND, "Thing not found");
            assertError(() -> registry.invoke("greet", null), ActionException.Code.INVALID_INPUT,
                    "Missing required parameter 'name'");
            assertError(() -> registry.invoke("greet", json("{\"name\":null}")), ActionException.Code.INVALID_INPUT,
                    "Missing required parameter 'name'");
            assertError(() -> registry.invoke("greet", json("{\"name\":\"a\",\"nmae\":1}")),
                    ActionException.Code.INVALID_INPUT, "Unknown parameter 'nmae' (expected: name, mood, times, month, ids)");
            assertError(() -> registry.invoke("noop", json("{\"x\":1}")), ActionException.Code.INVALID_INPUT,
                    "Unknown parameter 'x' (this action takes no parameters)");
            assertError(() -> registry.invoke("greet", json("\"Ann\"")), ActionException.Code.INVALID_INPUT,
                    "Arguments must be a JSON object");
            assertError(() -> registry.invoke("greet", json("{\"name\":\"a\",\"month\":\"October\"}")),
                    ActionException.Code.INVALID_INPUT, "Invalid value for 'month': expected a month (YYYY-MM)");
            assertError(() -> registry.invoke("greet", json("{\"name\":\"a\",\"ids\":[\"nope\"]}")),
                    ActionException.Code.INVALID_INPUT, "Invalid value for 'ids[0]': expected a UUID");
            assertError(() -> registry.invoke("greet", json("{\"name\":\"a\",\"times\":1.5}")),
                    ActionException.Code.INVALID_INPUT, "Invalid value for 'times': expected an integer");
            assertError(() -> registry.invoke("greet", json("{\"name\":\"a\",\"mood\":\"MEH\"}")),
                    ActionException.Code.INVALID_INPUT, "Invalid value for 'mood': expected one of HAPPY, SAD");
            assertThatThrownBy(() -> registry.invoke("fail", null))
                    .isNotInstanceOf(ActionException.class)
                    .hasRootCauseMessage("checked");
        });
    }

    @Test
    void failsStartupOnDuplicateNames() {
        runner().withBean(GreetActions.class).withBean(OtherGreet.class).run(ctx ->
                assertThat(ctx).getFailure()
                        .hasStackTraceContaining("Duplicate @McpAction name 'greet'")
                        .hasStackTraceContaining("GreetActions#greet")
                        .hasStackTraceContaining("OtherGreet#greet"));
    }

    @Test
    void failsStartupOnInvalidContracts() {
        assertStartupFailure(TwoParams.class, "must take zero parameters or exactly one record parameter");
        assertStartupFailure(NotARecord.class, "parameter type java.lang.String must be a record");
        assertStartupFailure(BadName.class, "name 'Bad-Name' must be snake_case");
        assertStartupFailure(BadType.class, "Unsupported action input type java.util.Map");
        assertStartupFailure(NonPublic.class, "must be a public instance method");
    }

    // ------------------------------------------------------------- helpers

    private void assertStartupFailure(Class<?> beanClass, String message) {
        runner().withBean(beanClass).run(ctx ->
                assertThat(ctx).getFailure()
                        .hasStackTraceContaining("Invalid @McpAction " + beanClass.getName())
                        .hasStackTraceContaining(message));
    }

    private static void assertError(Runnable call, ActionException.Code code, String message) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ActionException.class, e -> {
                    assertThat(e.code()).isEqualTo(code);
                    assertThat(e.getMessage()).isEqualTo(message);
                });
    }

    private JsonNode json(String text) {
        return mapper.readTree(text);
    }

    @SuppressWarnings("unchecked")
    private static <T> T cglibProxy(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        return (T) factory.getProxy();
    }

    private static Api jdkProxy(ApiImpl target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(Api.class);
        return (Api) factory.getProxy();
    }

    /** Counts commits and rollbacks; no real resources. */
    static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        int commits;
        int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}
