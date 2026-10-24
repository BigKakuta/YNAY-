package io.ynay.security;

import io.ynay.action.ActionException;
import io.ynay.action.ActionParam;
import io.ynay.action.McpAction;
import io.ynay.security.token.ApiToken;
import io.ynay.security.token.ApiTokenService;
import io.ynay.security.token.CreatedApiToken;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Identity and personal API token actions. Token management is web-only ({@code mcp = false}). */
@Component
public class SecurityActions {

    private final AppUserRepository users;
    private final ApiTokenService tokens;

    public SecurityActions(AppUserRepository users, ApiTokenService tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    @McpAction(name = "whoami",
            description = "Return the signed-in YNAY user (userId, username, displayName, email).",
            readOnly = true, idempotent = true)
    public Me whoami() {
        UUID id = CurrentUser.id();
        return users.findMe(id).orElseThrow(() -> ActionException.notFound("User " + id));
    }

    @McpAction(name = "list_api_tokens",
            description = "List your personal API tokens for MCP clients (metadata only, never the secret).",
            readOnly = true, idempotent = true, mcp = false)
    public List<ApiToken> listApiTokens() {
        return tokens.list(CurrentUser.id());
    }

    public record CreateApiTokenInput(
            @ActionParam("Name to recognise the token by, e.g. \"Claude Desktop\"") String name,
            @ActionParam(value = "Days until the token expires (1-3650); omit for no expiry", required = false)
            Integer expiresInDays) {
    }

    @McpAction(name = "create_api_token",
            description = "Create a personal API token (ynay_pat_...) for MCP clients. The plaintext token is "
                    + "returned only in this response; store it now.",
            mcp = false)
    public CreatedApiToken createApiToken(CreateApiTokenInput in) {
        return tokens.create(CurrentUser.id(), in.name(), in.expiresInDays());
    }

    public record RevokeApiTokenInput(@ActionParam("Id of the token to revoke") UUID tokenId) {
    }

    @McpAction(name = "revoke_api_token",
            description = "Revoke one of your personal API tokens. It stops working immediately.",
            destructive = true, idempotent = false, mcp = false)
    public Map<String, Boolean> revokeApiToken(RevokeApiTokenInput in) {
        tokens.revoke(CurrentUser.id(), in.tokenId());
        return Map.of("revoked", true);
    }
}
