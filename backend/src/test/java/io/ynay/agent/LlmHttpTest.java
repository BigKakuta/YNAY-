package io.ynay.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LlmHttpTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void unreachableServerGivesAReadableReason() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort(); // closed again: nothing listens there
        }
        LlmHttp http = new LlmHttp(JSON, Duration.ofSeconds(2), Duration.ofSeconds(2), 0, Duration.ZERO);
        URI uri = URI.create("http://localhost:" + port + "/v1/chat/completions");

        assertThatThrownBy(() -> http.postJson(uri, Map.of(), JSON.createObjectNode(), "LLM server"))
                .isInstanceOf(LlmHttp.LlmException.class)
                .hasMessageStartingWith("Could not reach LLM server at " + uri + ": ")
                .hasMessageNotContaining("null");
    }

    @Test
    void describeFallsBackToCausesAndTheExceptionType() {
        assertThat(LlmHttp.describe(new ConnectException())).isEqualTo("ConnectException");
        assertThat(LlmHttp.describe(new IOException(null, new ConnectException("Connection refused"))))
                .isEqualTo("Connection refused (ConnectException)");
    }
}
