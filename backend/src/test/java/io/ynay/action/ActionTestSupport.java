package io.ynay.action;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import io.ynay.security.YnayUser;
import io.ynay.support.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared base for the framework's DB-backed tests: full context + MockMvc + {@link TestActions}.
 * All subclasses share one cached application context.
 */
@AutoConfigureMockMvc
@Import(TestActions.class)
public abstract class ActionTestSupport extends IntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JsonMapper json;

    @Autowired
    protected ActionRegistry registry;

    /** Authenticates a MockMvc request as {@code user}. */
    protected static RequestPostProcessor as(YnayUser user) {
        TestingAuthenticationToken token = new TestingAuthenticationToken(user, null, "ROLE_USER");
        return authentication(token);
    }
}
