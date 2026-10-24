package io.ynay.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OriginPolicyTest {

    private final OriginPolicy policy = new OriginPolicy("https://YNAY.example.com/",
            " http://localhost:6274, ,not a url,https://tools.example:8443 ");

    @Test
    void normalizesConfiguredOrigins() {
        assertThat(policy.allowedOrigins()).containsExactlyInAnyOrder(
                "https://ynay.example.com", "http://localhost:6274", "https://tools.example:8443");
    }

    @Test
    void allowsAbsentPublicAndConfiguredOrigins() {
        String self = "https://ynay.example.com:443";
        assertThat(policy.isAllowed(null, self)).isTrue();
        assertThat(policy.isAllowed("https://ynay.example.com", self)).isTrue();
        assertThat(policy.isAllowed("https://ynay.example.com:443", self)).isTrue();
        assertThat(policy.isAllowed("http://localhost:6274", self)).isTrue();
        assertThat(policy.isAllowed("https://tools.example:8443", self)).isTrue();
    }

    @Test
    void rejectsForeignAndOpaqueOrigins() {
        String self = "https://ynay.example.com:443";
        assertThat(policy.isAllowed("https://evil.example", self)).isFalse();
        assertThat(policy.isAllowed("http://ynay.example.com", self)).isFalse();
        assertThat(policy.isAllowed("null", self)).isFalse();
        assertThat(policy.isAllowed("file://", self)).isFalse();
    }

    @Test
    void sameOriginOnlyCountsForLoopbackHosts() {
        assertThat(policy.isAllowed("http://localhost:8080", "http://localhost:8080")).isTrue();
        assertThat(policy.isAllowed("http://127.0.0.1:9000", "http://127.0.0.1:9000")).isTrue();
        // DNS rebinding: attacker page and forged Host are "same origin"
        assertThat(policy.isAllowed("http://rebind.evil:8080", "http://rebind.evil:8080")).isFalse();
    }
}
