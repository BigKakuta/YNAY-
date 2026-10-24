package io.ynay.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the in-app import agent ({@code ynay.agent.*}). The environment variables
 * ({@code YNAY_AGENT_*}) are mapped in {@code ynay-agent.properties}.
 */
@ConfigurationProperties("ynay.agent")
public class AgentProperties {

    /** {@code anthropic} or {@code openai} (any OpenAI-compatible Chat Completions server). */
    private String provider = "anthropic";

    /** API key. Required for {@code anthropic} and for {@code openai} on the default base URL. */
    private String apiKey = "";

    /** Model id; blank means the provider default (none for {@code openai}). */
    private String model = "";

    /** API base URL; blank means the provider default. */
    private String baseUrl = "";

    /** Maximum number of model calls per chat turn (each may request several tool calls). */
    private int maxIterations = 25;

    /** {@code max_tokens} per model call. */
    private int maxTokens = 16000;

    /**
     * Anthropic only: {@code output_config.effort} ({@code low}, {@code medium}, {@code high},
     * {@code xhigh}, {@code max}). Blank omits the field (needed for models without effort support).
     */
    private String effort = "medium";

    /**
     * Anthropic only: let the API re-run a request that a safety classifier declined on Anthropic's
     * recommended fallback model ({@code fallbacks: "default"}). Only sent for models that support it.
     */
    private boolean refusalFallback = true;

    /** Timeout of a single model call. Imports with large statements can take minutes. */
    private Duration requestTimeout = Duration.ofMinutes(10);

    private Duration connectTimeout = Duration.ofSeconds(30);

    /** Retries for 429/5xx/connection errors per model call. */
    private int maxRetries = 2;

    /** Upper bound for the total characters of all messages in one chat request. */
    private int maxInputChars = 200_000;

    /** Upper bound for the characters of one tool result fed back to the model. */
    private int maxToolResultChars = 100_000;

    /** Expose actions marked {@code destructive} (deletes) to the agent. */
    private boolean allowDestructive = false;

    /** Actions never exposed to the agent, in addition to non-MCP ones. */
    private List<String> excludedTools = new ArrayList<>(List.of(
            "list_budgets", "create_budget", "update_budget", "delete_budget",
            "share_budget", "remove_budget_member"));

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public String getEffort() {
        return effort;
    }

    public void setEffort(String effort) {
        this.effort = effort;
    }

    public boolean isRefusalFallback() {
        return refusalFallback;
    }

    public void setRefusalFallback(boolean refusalFallback) {
        this.refusalFallback = refusalFallback;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getMaxInputChars() {
        return maxInputChars;
    }

    public void setMaxInputChars(int maxInputChars) {
        this.maxInputChars = maxInputChars;
    }

    public int getMaxToolResultChars() {
        return maxToolResultChars;
    }

    public void setMaxToolResultChars(int maxToolResultChars) {
        this.maxToolResultChars = maxToolResultChars;
    }

    public boolean isAllowDestructive() {
        return allowDestructive;
    }

    public void setAllowDestructive(boolean allowDestructive) {
        this.allowDestructive = allowDestructive;
    }

    public List<String> getExcludedTools() {
        return excludedTools;
    }

    public void setExcludedTools(List<String> excludedTools) {
        this.excludedTools = excludedTools;
    }
}
