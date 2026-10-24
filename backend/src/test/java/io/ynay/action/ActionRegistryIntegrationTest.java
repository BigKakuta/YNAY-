package io.ynay.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ynay.security.YnayUser;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Registry against the real application context (Spring's JsonMapper, JDBC transactions). */
class ActionRegistryIntegrationTest extends ActionTestSupport {

    @Test
    void registersTestActionsAlongsideApplicationActions() {
        assertThat(registry.list()).extracting(ActionDescriptor::name).contains("test_echo", "test_whoami");
        assertThat(registry.list()).extracting(ActionDescriptor::name).isSorted();
    }

    @Test
    void roundTripsAllInputTypesWithTheApplicationMapper() {
        loginAsNewUser();
        JsonNode in = json.readTree("""
                {"id":"8a5f1c6e-6f43-4a51-9d7e-3f1f0e7c9a10","name":"n","date":"2026-10-24","month":"2026-10",
                 "color":"GREEN","flag":true,"count":7,"lines":[{"amount":-12340,"memo":"m"},{"amount":5}]}""");
        JsonNode out = registry.invoke("test_echo", in);
        // re-parse: valueToTree yields LongNode for longs, readTree IntNode
        assertThat(json.readTree(out.toString())).isEqualTo(json.readTree("""
                {"id":"8a5f1c6e-6f43-4a51-9d7e-3f1f0e7c9a10","name":"n","date":"2026-10-24","month":"2026-10",
                 "color":"GREEN","flag":true,"count":7,"lines":[{"amount":-12340,"memo":"m"},{"amount":5,"memo":null}]}"""));
    }

    @Test
    void runsAsTheCurrentUser() {
        YnayUser user = loginAsNewUser();
        JsonNode out = registry.invoke("test_whoami", null);
        assertThat(out.get("userId").stringValue()).isEqualTo(user.userId().toString());
        assertThat(out.get("username").stringValue()).isEqualTo(user.username());
    }

    @Test
    void unauthenticatedCallerGetsUnauthenticated() {
        assertThatThrownBy(() -> registry.invoke("test_whoami", null))
                .isInstanceOfSatisfying(ActionException.class,
                        e -> assertThat(e.code()).isEqualTo(ActionException.Code.UNAUTHENTICATED));
    }

    @Test
    void commitsOnSuccessAndRollsBackOnFailure() {
        loginAsNewUser();
        String kept = "kept-" + UUID.randomUUID();
        registry.invoke("test_insert", json.readTree("{\"username\":\"" + kept + "\"}"));
        assertThat(countUsers(kept)).isEqualTo(1);

        String rolledBack = "gone-" + UUID.randomUUID();
        assertThatThrownBy(() -> registry.invoke("test_insert_then_fail",
                json.readTree("{\"username\":\"" + rolledBack + "\"}")))
                .isInstanceOfSatisfying(ActionException.class,
                        e -> assertThat(e.code()).isEqualTo(ActionException.Code.CONFLICT));
        assertThat(countUsers(rolledBack)).isZero();
    }

    @Test
    void recordConstructorValidationBecomesInvalidInput() {
        assertThatThrownBy(() -> registry.invoke("test_checked", json.readTree("{\"value\":-1}")))
                .isInstanceOfSatisfying(ActionException.class, e -> {
                    assertThat(e.code()).isEqualTo(ActionException.Code.INVALID_INPUT);
                    assertThat(e.getMessage()).isEqualTo("Invalid arguments: must be positive");
                });
        assertThat(registry.invoke("test_checked", json.readTree("{\"value\":2}")).intValue()).isEqualTo(2);
    }

    @Test
    void badFormatsAreInvalidInput() {
        loginAsNewUser();
        assertThatThrownBy(() -> registry.invoke("test_echo", json.readTree("""
                {"id":"8a5f1c6e-6f43-4a51-9d7e-3f1f0e7c9a10","name":"n","date":"24.10.2026","month":"2026-10"}""")))
                .isInstanceOfSatisfying(ActionException.class, e -> {
                    assertThat(e.code()).isEqualTo(ActionException.Code.INVALID_INPUT);
                    assertThat(e.getMessage()).isEqualTo("Invalid value for 'date': expected a date (YYYY-MM-DD)");
                });
        assertThatThrownBy(() -> registry.invoke("test_echo", json.readTree("""
                {"id":"8a5f1c6e-6f43-4a51-9d7e-3f1f0e7c9a10","name":"n","date":"2026-10-24","month":"2026-10",
                 "lines":[{"amount":"lots"}]}""")))
                .isInstanceOfSatisfying(ActionException.class, e ->
                        assertThat(e.getMessage()).isEqualTo("Invalid value for 'lines[0].amount': expected an integer"));
    }

    private int countUsers(String username) {
        return jdbc.sql("select count(*) from app_user where username = ?").param(username).query(Integer.class).single();
    }
}
