package io.ynay.action;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public method on a Spring bean as an <em>action</em>.
 *
 * <p>Every action is automatically exposed in two places through the same code path:
 * <ul>
 *   <li>as an MCP tool on {@code /mcp} (Streamable HTTP, JSON-RPC {@code tools/call}), and</li>
 *   <li>as {@code POST /api/actions/{name}} for the web frontend.</li>
 * </ul>
 *
 * <p>Contract for annotated methods:
 * <ul>
 *   <li>Zero parameters, or exactly one parameter whose type is a Java {@code record}
 *       (the action input). Its JSON Schema is derived from the record components;
 *       annotate components with {@link ActionParam} to document them.</li>
 *   <li>The return value is serialized with Jackson. Return a record/DTO, a list, or a map.</li>
 *   <li>Signal user-facing failures with {@link ActionException}.</li>
 *   <li>The caller's identity is available via {@link io.ynay.security.CurrentUser}.</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface McpAction {

    /** Tool name, snake_case, unique across the application (e.g. {@code create_transaction}). */
    String name();

    /** Description shown to MCP clients / LLMs. Be specific about units (amounts are milliunits). */
    String description();

    /** MCP tool annotation: the action does not modify state. */
    boolean readOnly() default false;

    /** MCP tool annotation: the action may irreversibly delete data. */
    boolean destructive() default false;

    /** MCP tool annotation: calling repeatedly with the same input has no additional effect. */
    boolean idempotent() default false;

    /**
     * Whether the action is listed on the MCP endpoint. Actions are MCP tools by default;
     * opt out only for actions that make no sense for an agent (e.g. minting API tokens).
     */
    boolean mcp() default true;
}
