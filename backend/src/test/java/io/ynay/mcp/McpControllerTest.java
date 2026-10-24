package io.ynay.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.ynay.action.ActionTestSupport;
import io.ynay.security.YnayUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

class McpControllerTest extends ActionTestSupport {

    private YnayUser user;

    @BeforeEach
    void setUp() {
        user = createUser("mcp-" + System.nanoTime());
    }

    @Test
    void fullHandshake() throws Exception {
        // initialize
        JsonNode init = call("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                 "capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""");
        assertThat(init.get("id").intValue()).isEqualTo(1);
        JsonNode result = init.get("result");
        assertThat(result.get("protocolVersion").stringValue()).isEqualTo("2025-06-18");
        assertThat(result.at("/capabilities/tools/listChanged").booleanValue()).isFalse();
        assertThat(result.at("/serverInfo/name").stringValue()).isEqualTo("ynay");
        assertThat(result.at("/serverInfo/version").stringValue()).isNotBlank();
        assertThat(result.get("instructions").stringValue()).contains("milliunits", "list_budgets", "YYYY-MM");

        // initialized notification
        mvc.perform(mcp("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                        .header("MCP-Protocol-Version", "2025-06-18"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        // tools/list
        JsonNode list = call("{\"jsonrpc\":\"2.0\",\"id\":\"two\",\"method\":\"tools/list\"}");
        assertThat(list.get("id").stringValue()).isEqualTo("two");
        JsonNode tools = list.at("/result/tools");
        JsonNode echo = findTool(tools, "test_echo");
        assertThat(echo.get("title").stringValue()).isEqualTo("Test echo");
        assertThat(echo.get("description").stringValue()).isEqualTo("Returns its input");
        assertThat(echo.at("/inputSchema/type").stringValue()).isEqualTo("object");
        assertThat(echo.at("/inputSchema/properties/id/format").stringValue()).isEqualTo("uuid");
        assertThat(echo.at("/annotations/readOnlyHint").booleanValue()).isTrue();
        assertThat(echo.at("/annotations/destructiveHint").booleanValue()).isFalse();
        assertThat(echo.at("/annotations/idempotentHint").booleanValue()).isTrue();
        assertThat(echo.at("/annotations/openWorldHint").booleanValue()).isFalse();
        assertThat(echo.at("/annotations/title").stringValue()).isEqualTo("Test echo");
        assertThat(findTool(tools, "test_void").at("/annotations/readOnlyHint").booleanValue()).isFalse();
        assertThat(findTool(tools, "test_void").at("/annotations/idempotentHint").booleanValue()).isTrue();
        assertThat(findTool(tools, "test_web_only")).isNull();

        // tools/call success (object result)
        JsonNode whoami = call("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"test_whoami","arguments":{}}}""");
        JsonNode callResult = whoami.get("result");
        assertThat(callResult.get("isError").booleanValue()).isFalse();
        assertThat(callResult.at("/structuredContent/userId").stringValue()).isEqualTo(user.userId().toString());
        assertThat(callResult.at("/content/0/type").stringValue()).isEqualTo("text");
        assertThat(json.readTree(callResult.at("/content/0/text").stringValue()))
                .isEqualTo(callResult.get("structuredContent"));

        // tools/call success (non-object result is wrapped)
        JsonNode numbers = call("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"test_numbers"}}""");
        assertThat(numbers.at("/result/structuredContent")).isEqualTo(json.readTree("{\"result\":[1,2,3]}"));

        // tools/call with an action error -> isError result
        JsonNode invalid = call("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"test_echo","arguments":{"name":"x"}}}""");
        assertThat(invalid.has("error")).isFalse();
        assertThat(invalid.at("/result/isError").booleanValue()).isTrue();
        assertThat(invalid.at("/result/content/0/text").stringValue()).contains("Missing required parameter 'id'");
        assertThat(invalid.at("/result/structuredContent").isMissingNode()).isTrue();

        // unexpected failure -> isError with a generic message
        JsonNode boom = call("""
                {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"test_boom"}}""");
        assertThat(boom.at("/result/isError").booleanValue()).isTrue();
        assertThat(boom.at("/result/content/0/text").stringValue()).isEqualTo("Internal server error");

        // unknown tool and web-only actions -> JSON-RPC error -32602
        JsonNode unknown = call("""
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"nope"}}""");
        assertThat(unknown.at("/error/code").intValue()).isEqualTo(-32602);
        assertThat(unknown.at("/error/message").stringValue()).isEqualTo("Unknown tool: nope");
        JsonNode webOnly = call("""
                {"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"test_web_only"}}""");
        assertThat(webOnly.at("/error/code").intValue()).isEqualTo(-32602);
    }

    @Test
    void negotiatesProtocolVersion() throws Exception {
        for (String version : McpServer.SUPPORTED_VERSIONS) {
            assertThat(call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\""
                    + version + "\"}}").at("/result/protocolVersion").stringValue()).isEqualTo(version);
        }
        assertThat(call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}")
                .at("/result/protocolVersion").stringValue()).isEqualTo("2025-11-25");
        assertThat(call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}")
                .at("/result/protocolVersion").stringValue()).isEqualTo("2025-11-25");
    }

    @Test
    void pingAndUnknownMethods() throws Exception {
        assertThat(call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").get("result"))
                .isEqualTo(json.readTree("{}"));
        JsonNode unknown = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/list\"}");
        assertThat(unknown.at("/error/code").intValue()).isEqualTo(-32601);
        assertThat(unknown.get("id").intValue()).isEqualTo(2);
        JsonNode badParams = call("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\",\"params\":[1]}");
        assertThat(badParams.at("/error/code").intValue()).isEqualTo(-32602);
    }

    @Test
    void protocolErrors() throws Exception {
        expectError("{not json", 400, -32700);
        expectError("", 400, -32600);
        expectError("[]", 400, -32600);
        expectError("42", 400, -32600);
        expectError("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}", 200, -32600);
        expectError("{\"jsonrpc\":\"2.0\",\"id\":1}", 200, -32600);
        expectError("{\"jsonrpc\":\"2.0\",\"id\":{\"x\":1},\"method\":\"ping\"}", 400, -32600);
        expectError("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}", 400, -32600);
    }

    @Test
    void batches() throws Exception {
        JsonNode reply = json.readTree(mvc.perform(mcp("""
                        [{"jsonrpc":"2.0","id":1,"method":"ping"},
                         {"jsonrpc":"2.0","method":"notifications/initialized"},
                         {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"test_void"}},
                         {"jsonrpc":"2.0","id":3,"method":"nope"}]"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(reply.isArray()).isTrue();
        assertThat(reply.size()).isEqualTo(3);
        assertThat(reply.get(0).get("id").intValue()).isEqualTo(1);
        assertThat(reply.get(1).at("/result/structuredContent/ok").booleanValue()).isTrue();
        assertThat(reply.get(2).at("/error/code").intValue()).isEqualTo(-32601);

        mvc.perform(mcp("[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]"))
                .andExpect(status().isAccepted());
    }

    @Test
    void clientResponsesAreAccepted() throws Exception {
        mvc.perform(mcp("{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{}}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void getAndDeleteAreNotAllowed() throws Exception {
        mvc.perform(get("/mcp").with(as(user)).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"));
        mvc.perform(delete("/mcp").with(as(user)).with(csrf()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void validatesOrigin() throws Exception {
        mvc.perform(mcp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(mcp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").header("Origin", "null"))
                .andExpect(status().isForbidden());
        // the configured public URL (default http://localhost:8080)
        mvc.perform(mcp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").header("Origin", "http://localhost:8080"))
                .andExpect(status().isOk());
        // same-origin on a loopback host (MockMvc requests go to http://localhost)
        mvc.perform(mcp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").header("Origin", "http://localhost"))
                .andExpect(status().isOk());
    }

    @Test
    void unsupportedProtocolVersionHeaderIsTolerated() throws Exception {
        mvc.perform(mcp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").header("MCP-Protocol-Version", "2099-01-01"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------- helpers

    private MockHttpServletRequestBuilder mcp(String body) {
        return post("/mcp").with(as(user)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(body);
    }

    private JsonNode call(String body) throws Exception {
        ResultActions result = mvc.perform(mcp(body))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        JsonNode reply = json.readTree(result.andReturn().getResponse().getContentAsString());
        assertThat(reply.get("jsonrpc").stringValue()).isEqualTo("2.0");
        return reply;
    }

    private void expectError(String body, int httpStatus, int code) throws Exception {
        String response = mvc.perform(mcp(body))
                .andExpect(status().is(httpStatus))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(response).at("/error/code").intValue()).as(body).isEqualTo(code);
    }

    private static JsonNode findTool(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (tool.get("name").stringValue().equals(name)) {
                return tool;
            }
        }
        return null;
    }
}
