package io.ynay.action.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.ynay.action.ActionTestSupport;
import io.ynay.security.YnayUser;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ActionControllerTest extends ActionTestSupport {

    private YnayUser user;

    @BeforeEach
    void setUp() {
        user = createUser("web-" + System.nanoTime());
    }

    @Test
    void listsActionDescriptors() throws Exception {
        mvc.perform(get("/api/actions").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[?(@.name == 'test_echo')].description").value("Returns its input"))
                .andExpect(jsonPath("$[?(@.name == 'test_echo')].title").value("Test echo"))
                .andExpect(jsonPath("$[?(@.name == 'test_echo')].readOnly").value(true))
                .andExpect(jsonPath("$[?(@.name == 'test_echo')].destructive").value(false))
                .andExpect(jsonPath("$[?(@.name == 'test_echo')].idempotent").value(false))
                .andExpect(jsonPath("$[?(@.name == 'test_echo')].mcp").value(true))
                .andExpect(jsonPath("$[?(@.name == 'test_echo')].inputSchema.properties.month.pattern")
                        .value("^\\d{4}-\\d{2}$"))
                .andExpect(jsonPath("$[?(@.name == 'test_web_only')].mcp").value(false));
    }

    @Test
    void invokesActionWithJsonBody() throws Exception {
        mvc.perform(post("/api/actions/test_echo").with(as(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"8a5f1c6e-6f43-4a51-9d7e-3f1f0e7c9a10","name":"n","date":"2026-10-24","month":"2026-10"}"""))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.name").value("n"))
                .andExpect(jsonPath("$.month").value("2026-10"))
                .andExpect(jsonPath("$.flag").value(false));
    }

    @Test
    void emptyBodyIsAllowed() throws Exception {
        mvc.perform(post("/api/actions/test_whoami").with(as(user)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.userId().toString()));
        mvc.perform(post("/api/actions/test_void").with(as(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        mvc.perform(post("/api/actions/test_numbers").with(as(user)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().json("[1,2,3]"));
    }

    @Test
    void mapsActionErrorsToStatusAndErrorBody() throws Exception {
        mvc.perform(post("/api/actions/no_such_action").with(as(user)).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Action 'no_such_action' not found"));
        mvc.perform(post("/api/actions/test_echo").with(as(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"n\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.message", Matchers.containsString("Missing required parameter 'id'")));
        mvc.perform(post("/api/actions/test_insert_then_fail").with(as(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.message").value("Rolled back on purpose"));
    }

    @Test
    void malformedJsonIsInvalidInput() throws Exception {
        mvc.perform(post("/api/actions/test_echo").with(as(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.message").value("Request body is not valid JSON"));
    }

    @Test
    void unexpectedExceptionsAreGeneric500() throws Exception {
        mvc.perform(post("/api/actions/test_boom").with(as(user)).with(csrf()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL"))
                .andExpect(jsonPath("$.error.message").value("Internal server error"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("secret"))));
    }

    @Test
    void webOnlyActionsAreInvokable() throws Exception {
        mvc.perform(post("/api/actions/test_web_only").with(as(user)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.web").value(true));
    }
}
