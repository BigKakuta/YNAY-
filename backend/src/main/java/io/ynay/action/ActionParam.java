package io.ynay.action;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documents a component of an action input record. Used to build the JSON Schema
 * published to MCP clients and to validate required fields.
 */
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ActionParam {

    /** Human/LLM readable description of the parameter. */
    String value() default "";

    /** Whether the parameter must be present (non-null). */
    boolean required() default true;
}
