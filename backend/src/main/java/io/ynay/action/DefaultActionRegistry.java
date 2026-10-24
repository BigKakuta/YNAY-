package io.ynay.action;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.ValueInstantiationException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Discovers {@link McpAction} methods on all singleton beans once the context has started,
 * validates their contract and invokes them with JSON arguments inside a transaction.
 *
 * <p>Startup fails (with a message naming the offending method) on duplicate action names,
 * invalid method signatures or unsupported input component types.
 */
@Component
public class DefaultActionRegistry implements ActionRegistry, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(DefaultActionRegistry.class);

    /** snake_case, and a valid MCP tool name. */
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    private static final InputModel.RecordModel NO_PARAMETERS = new InputModel.RecordModel(Void.class, List.of());

    private final ApplicationContext context;
    private final TransactionTemplate transactions;
    private final JsonMapper outputMapper;
    private final JsonMapper inputMapper;

    private volatile Map<String, RegisteredAction> actions;

    public DefaultActionRegistry(ApplicationContext context, PlatformTransactionManager transactionManager,
                                 JsonMapper jsonMapper) {
        this.context = context;
        this.transactions = new TransactionTemplate(transactionManager);
        this.outputMapper = jsonMapper;
        // Same configuration as the application's mapper, but strict about input.
        this.inputMapper = jsonMapper.rebuild()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                // Nulls / absent values for required fields are rejected by InputModel with a clear
                // message; optional primitives simply fall back to their default (0 / false).
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, RegisteredAction> scanned = actions();
        log.info("Registered {} actions: {}", scanned.size(), String.join(", ", scanned.keySet()));
    }

    @Override
    public List<ActionDescriptor> list() {
        return actions().values().stream().map(RegisteredAction::descriptorCopy).toList();
    }

    @Override
    public Optional<ActionDescriptor> find(String name) {
        return Optional.ofNullable(name == null ? null : actions().get(name)).map(RegisteredAction::descriptorCopy);
    }

    @Override
    public JsonNode invoke(String name, JsonNode arguments) {
        RegisteredAction action = name == null ? null : actions().get(name);
        if (action == null) {
            throw ActionException.notFound("Action '" + name + "'");
        }
        Object input = readInput(action, arguments);
        try {
            return transactions.execute(status -> toJson(action, call(action, input)));
        } catch (ActionException e) {
            log.debug("Action {} failed: {} {}", name, e.code(), e.getMessage());
            throw e;
        } catch (DuplicateKeyException e) {
            log.info("Action {} hit a unique constraint", name, e);
            throw ActionException.conflict("A conflicting record already exists");
        } catch (DataIntegrityViolationException e) {
            log.info("Action {} violated a data constraint", name, e);
            throw ActionException.invalid("The request violates a data constraint (e.g. a referenced id does not exist)");
        } catch (AccessDeniedException e) {
            throw ActionException.forbidden("Access denied");
        } catch (AuthenticationException e) {
            throw new ActionException(ActionException.Code.UNAUTHENTICATED, "Not authenticated");
        }
    }

    // ------------------------------------------------------------------ input

    private Object readInput(RegisteredAction action, JsonNode arguments) {
        JsonNode args = arguments == null || arguments.isNull() || arguments.isMissingNode()
                ? JsonNodeFactory.instance.objectNode()
                : arguments;
        InputModel.RecordModel model = action.model() == null ? NO_PARAMETERS : action.model();
        List<String> errors = new ArrayList<>();
        model.validate(args, "", errors);
        if (!errors.isEmpty()) {
            throw ActionException.invalid(String.join("; ", errors));
        }
        if (action.inputType() == null) {
            return null;
        }
        try {
            return inputMapper.treeToValue(args, action.inputType());
        } catch (JacksonException e) {
            throw translate(e, model);
        }
    }

    private static RuntimeException translate(JacksonException e, InputModel.RecordModel model) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ActionException actionException) {
                return actionException;
            }
        }
        String path = path(e);
        String subject = path.isEmpty() ? "Invalid arguments" : "Invalid value for '" + path + "'";
        if (e instanceof ValueInstantiationException && e.getCause() != null && e.getCause().getMessage() != null) {
            // A compact record constructor rejected the value (e.g. IllegalArgumentException).
            return ActionException.invalid(subject + ": " + e.getCause().getMessage());
        }
        if (e instanceof MismatchedInputException) {
            InputModel target = locate(model, e.getPath());
            if (target != null) {
                return ActionException.invalid(subject + ": expected " + target.expected());
            }
        }
        return ActionException.invalid(subject + ": " + e.getOriginalMessage());
    }

    private static String path(JacksonException e) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference ref : e.getPath()) {
            if (ref.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(ref.getPropertyName());
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    /** The model of the value at a Jackson path, or {@code null} if it cannot be resolved. */
    private static InputModel locate(InputModel.RecordModel root, List<JacksonException.Reference> refs) {
        InputModel current = root;
        for (JacksonException.Reference ref : refs) {
            if (current instanceof InputModel.RecordModel record && ref.getPropertyName() != null) {
                current = record.fields().stream()
                        .filter(f -> f.name().equals(ref.getPropertyName()))
                        .map(InputModel.FieldModel::type)
                        .findFirst().orElse(null);
            } else if (current instanceof InputModel.ListModel list && ref.getIndex() >= 0) {
                current = list.element();
            } else {
                return null;
            }
        }
        return current == root ? null : current;
    }

    // ------------------------------------------------------------- invocation

    private static Object call(RegisteredAction action, Object input) {
        try {
            return action.inputType() == null
                    ? action.method().invoke(action.bean())
                    : action.method().invoke(action.bean(), input);
        } catch (InvocationTargetException e) {
            Throwable target = e.getTargetException();
            if (target instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (target instanceof Error error) {
                throw error;
            }
            throw new UndeclaredThrowableException(target, "Action " + action.descriptor().name() + " failed");
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot invoke action " + action.descriptor().name(), e);
        }
    }

    private JsonNode toJson(RegisteredAction action, Object result) {
        if (action.returnsVoid()) {
            ObjectNode ok = JsonNodeFactory.instance.objectNode();
            ok.put("ok", true);
            return ok;
        }
        if (result == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        return outputMapper.valueToTree(result);
    }

    // --------------------------------------------------------------- scanning

    private Map<String, RegisteredAction> actions() {
        Map<String, RegisteredAction> current = actions;
        if (current == null) {
            synchronized (this) {
                current = actions;
                if (current == null) {
                    current = scan();
                    actions = current;
                }
            }
        }
        return current;
    }

    private Map<String, RegisteredAction> scan() {
        Map<String, RegisteredAction> found = new TreeMap<>();
        for (String beanName : context.getBeanNamesForType(Object.class, false, false)) {
            if (ScopedProxyUtils.isScopedTarget(beanName)) {
                continue;
            }
            Class<?> type;
            try {
                type = context.getType(beanName, false);
            } catch (RuntimeException e) {
                continue;
            }
            if (type == null) {
                continue;
            }
            Object bean = null;
            Class<?> userType = ClassUtils.getUserClass(type);
            if (Proxy.isProxyClass(type)) {
                bean = context.getBean(beanName);
                userType = AopUtils.getTargetClass(bean);
            }
            Map<Method, McpAction> methods = MethodIntrospector.selectMethods(userType,
                    (MethodIntrospector.MetadataLookup<McpAction>) method ->
                            AnnotatedElementUtils.findMergedAnnotation(method, McpAction.class));
            if (methods.isEmpty()) {
                continue;
            }
            if (bean == null) {
                bean = context.getBean(beanName);
            }
            for (Map.Entry<Method, McpAction> entry : methods.entrySet()) {
                RegisteredAction action = register(beanName, bean, entry.getKey(), entry.getValue());
                RegisteredAction existing = found.putIfAbsent(action.descriptor().name(), action);
                if (existing != null) {
                    throw new IllegalStateException("Duplicate @McpAction name '" + action.descriptor().name()
                            + "' on " + existing.source() + " and " + action.source());
                }
            }
        }
        return Collections.unmodifiableMap(found);
    }

    private static RegisteredAction register(String beanName, Object bean, Method method, McpAction annotation) {
        String source = method.getDeclaringClass().getName() + "#" + method.getName() + " (bean '" + beanName + "')";
        if (!NAME.matcher(annotation.name()).matches()) {
            throw invalidAction(source, "name '" + annotation.name() + "' must be snake_case ([a-z][a-z0-9_]*, max 64 chars)");
        }
        if (annotation.description().isBlank()) {
            throw invalidAction(source, "description must not be blank");
        }
        if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
            throw invalidAction(source, "must be a public instance method");
        }
        Class<?> inputType = null;
        InputModel.RecordModel model = null;
        ObjectNode schema;
        if (method.getParameterCount() > 1) {
            throw invalidAction(source, "must take zero parameters or exactly one record parameter");
        }
        if (method.getParameterCount() == 1) {
            inputType = method.getParameterTypes()[0];
            if (!inputType.isRecord()) {
                throw invalidAction(source, "parameter type " + inputType.getName() + " must be a record");
            }
            try {
                model = InputModel.forRecord(inputType);
            } catch (IllegalArgumentException e) {
                throw invalidAction(source, e.getMessage());
            }
            schema = model.schema();
        } else {
            schema = InputModel.emptyObjectSchema();
        }
        Method invocable;
        try {
            invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
        } catch (IllegalStateException e) {
            throw invalidAction(source, e.getMessage());
        }
        ReflectionUtils.makeAccessible(invocable);
        boolean returnsVoid = method.getReturnType() == void.class || method.getReturnType() == Void.class;
        ActionDescriptor descriptor = new ActionDescriptor(annotation.name(), annotation.description().strip(), schema,
                annotation.readOnly(), annotation.destructive(), annotation.idempotent(), annotation.mcp());
        return new RegisteredAction(descriptor, bean, invocable, inputType, model, returnsVoid, source);
    }

    private static IllegalStateException invalidAction(String source, String problem) {
        return new IllegalStateException("Invalid @McpAction " + source + ": " + problem);
    }

    private record RegisteredAction(ActionDescriptor descriptor, Object bean, Method method, Class<?> inputType,
                                    InputModel.RecordModel model, boolean returnsVoid, String source) {

        /** Descriptor with a private copy of the schema, so callers cannot corrupt the registry. */
        ActionDescriptor descriptorCopy() {
            ActionDescriptor d = descriptor;
            return new ActionDescriptor(d.name(), d.description(), d.inputSchema().deepCopy(), d.readOnly(),
                    d.destructive(), d.idempotent(), d.mcp());
        }
    }
}
