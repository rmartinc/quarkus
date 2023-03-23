package io.quarkus.rest.client.reactive.deployment;

import static io.quarkus.arc.processor.DotNames.STRING;
import static io.quarkus.gizmo.MethodDescriptor.ofMethod;
import static io.quarkus.rest.client.reactive.deployment.DotNames.CLIENT_HEADER_PARAM;
import static io.quarkus.rest.client.reactive.deployment.DotNames.CLIENT_HEADER_PARAMS;
import static io.quarkus.rest.client.reactive.deployment.DotNames.CLIENT_QUERY_PARAM;
import static io.quarkus.rest.client.reactive.deployment.DotNames.CLIENT_QUERY_PARAMS;
import static io.quarkus.rest.client.reactive.deployment.DotNames.REGISTER_CLIENT_HEADERS;
import static org.jboss.resteasy.reactive.client.impl.RestClientRequestContext.INVOKED_METHOD_PARAMETERS_PROP;
import static org.jboss.resteasy.reactive.client.impl.RestClientRequestContext.INVOKED_METHOD_PROP;
import static org.jboss.resteasy.reactive.common.processor.HashUtil.sha1;
import static org.objectweb.asm.Opcodes.ACC_STATIC;

import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.Configurable;
import jakarta.ws.rs.core.MultivaluedMap;

import org.eclipse.microprofile.rest.client.RestClientDefinitionException;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;
import org.eclipse.microprofile.rest.client.ext.DefaultClientHeadersFactoryImpl;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.client.impl.WebTargetImpl;
import org.jboss.resteasy.reactive.client.spi.ResteasyReactiveClientRequestContext;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.deployment.GeneratedClassGizmoAdaptor;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.gizmo.AssignableResultHandle;
import io.quarkus.gizmo.BytecodeCreator;
import io.quarkus.gizmo.CatchBlockCreator;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.ClassOutput;
import io.quarkus.gizmo.FieldCreator;
import io.quarkus.gizmo.FieldDescriptor;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;
import io.quarkus.gizmo.TryBlock;
import io.quarkus.jaxrs.client.reactive.deployment.JaxrsClientReactiveEnricher;
import io.quarkus.rest.client.reactive.ComputedParamContext;
import io.quarkus.rest.client.reactive.HeaderFiller;
import io.quarkus.rest.client.reactive.runtime.ClientQueryParamSupport;
import io.quarkus.rest.client.reactive.runtime.ComputedParamContextImpl;
import io.quarkus.rest.client.reactive.runtime.ConfigUtils;
import io.quarkus.rest.client.reactive.runtime.ExtendedHeaderFiller;
import io.quarkus.rest.client.reactive.runtime.HeaderFillerUtil;
import io.quarkus.rest.client.reactive.runtime.MicroProfileRestClientRequestFilter;
import io.quarkus.rest.client.reactive.runtime.NoOpHeaderFiller;
import io.quarkus.runtime.util.HashUtil;

/**
 * Alters client stub generation to add MicroProfile Rest Client features.
 *
 * Used mostly to handle the `@RegisterProvider` annotation that e.g. registers filters
 * and to add support for `@ClientHeaderParam` annotations for specifying (possibly) computed headers via annotations
 */
class MicroProfileRestClientEnricher implements JaxrsClientReactiveEnricher {
    private static final Logger log = Logger.getLogger(MicroProfileRestClientEnricher.class);

    public static final String DEFAULT_HEADERS_FACTORY = DefaultClientHeadersFactoryImpl.class.getName();

    private static final AnnotationInstance[] EMPTY_ANNOTATION_INSTANCES = new AnnotationInstance[0];

    private static final MethodDescriptor INVOCATION_BUILDER_PROPERTY_METHOD = MethodDescriptor.ofMethod(
            Invocation.Builder.class,
            "property", Invocation.Builder.class, String.class, Object.class);
    private static final MethodDescriptor LIST_ADD_METHOD = MethodDescriptor.ofMethod(List.class, "add", boolean.class,
            Object.class);
    private static final MethodDescriptor MAP_PUT_METHOD = MethodDescriptor.ofMethod(Map.class, "put", Object.class,
            Object.class, Object.class);

    private static final MethodDescriptor HEADER_FILLER_UTIL_SHOULD_ADD_HEADER = MethodDescriptor.ofMethod(
            HeaderFillerUtil.class, "shouldAddHeader",
            boolean.class, String.class, MultivaluedMap.class, ClientRequestContext.class);
    private static final MethodDescriptor WEB_TARGET_IMPL_QUERY_PARAMS = MethodDescriptor.ofMethod(WebTargetImpl.class,
            "queryParam", WebTargetImpl.class, String.class, Collection.class);

    private final Map<ClassInfo, String> interfaceMocks = new HashMap<>();

    @Override
    public void forClass(MethodCreator constructor, AssignableResultHandle webTargetBase,
            ClassInfo interfaceClass, IndexView index) {

        ResultHandle clientHeadersFactory = null;

        AnnotationInstance registerClientHeaders = interfaceClass.classAnnotation(REGISTER_CLIENT_HEADERS);

        if (registerClientHeaders != null) {
            String headersFactoryClass = registerClientHeaders.valueWithDefault(index)
                    .asClass().name().toString();

            if (!headersFactoryClass.equals(DEFAULT_HEADERS_FACTORY)) {
                // Arc.container().instance(...).get():
                ResultHandle containerHandle = constructor
                        .invokeStaticMethod(MethodDescriptor.ofMethod(Arc.class, "container", ArcContainer.class));
                ResultHandle instanceHandle = constructor.invokeInterfaceMethod(
                        MethodDescriptor.ofMethod(ArcContainer.class, "instance", InstanceHandle.class, Class.class,
                                Annotation[].class),
                        containerHandle, constructor.loadClassFromTCCL(headersFactoryClass),
                        constructor.newArray(Annotation.class, 0));
                clientHeadersFactory = constructor
                        .invokeInterfaceMethod(MethodDescriptor.ofMethod(InstanceHandle.class, "get", Object.class),
                                instanceHandle);
            } else {
                clientHeadersFactory = constructor
                        .newInstance(MethodDescriptor.ofConstructor(DEFAULT_HEADERS_FACTORY));
            }
        } else {
            clientHeadersFactory = constructor.loadNull();
        }

        ResultHandle restClientFilter = constructor.newInstance(
                MethodDescriptor.ofConstructor(MicroProfileRestClientRequestFilter.class, ClientHeadersFactory.class),
                clientHeadersFactory);

        constructor.assign(webTargetBase, constructor.invokeInterfaceMethod(
                MethodDescriptor.ofMethod(Configurable.class, "register", Configurable.class, Object.class),
                webTargetBase, restClientFilter));
    }

    @Override
    public void forWebTarget(MethodCreator methodCreator, IndexView index, ClassInfo interfaceClass, MethodInfo method,
            AssignableResultHandle webTarget, BuildProducer<GeneratedClassBuildItem> generatedClasses) {
        Map<String, QueryData> queryParamsByName = new HashMap<>();
        collectClientQueryParamData(interfaceClass, method, queryParamsByName);
        for (var headerEntry : queryParamsByName.entrySet()) {
            addQueryParam(method, methodCreator, headerEntry.getValue(), webTarget, generatedClasses, index);
        }
    }

    @Override
    public void forSubResourceWebTarget(MethodCreator methodCreator, IndexView index, ClassInfo rootInterfaceClass,
            ClassInfo subInterfaceClass, MethodInfo rootMethod, MethodInfo subMethod,
            AssignableResultHandle webTarget, BuildProducer<GeneratedClassBuildItem> generatedClasses) {

        Map<String, QueryData> queryParamsByName = new HashMap<>();
        collectClientQueryParamData(rootInterfaceClass, rootMethod, queryParamsByName);
        collectClientQueryParamData(subInterfaceClass, subMethod, queryParamsByName);
        for (var headerEntry : queryParamsByName.entrySet()) {
            addQueryParam(subMethod, methodCreator, headerEntry.getValue(), webTarget, generatedClasses, index);
        }
    }

    private void collectClientQueryParamData(ClassInfo interfaceClass, MethodInfo method,
            Map<String, QueryData> headerFillersByName) {
        AnnotationInstance classLevelHeader = interfaceClass.declaredAnnotation(CLIENT_QUERY_PARAM);
        if (classLevelHeader != null) {
            headerFillersByName.put(classLevelHeader.value("name").asString(),
                    new QueryData(classLevelHeader, interfaceClass));
        }
        putAllQueryAnnotations(headerFillersByName,
                interfaceClass,
                extractAnnotations(interfaceClass.declaredAnnotation(CLIENT_QUERY_PARAMS)));

        Map<String, QueryData> methodLevelHeadersByName = new HashMap<>();
        AnnotationInstance methodLevelHeader = method.annotation(CLIENT_QUERY_PARAM);
        if (methodLevelHeader != null) {
            methodLevelHeadersByName.put(methodLevelHeader.value("name").asString(),
                    new QueryData(methodLevelHeader, interfaceClass));
        }
        putAllQueryAnnotations(methodLevelHeadersByName, interfaceClass,
                extractAnnotations(method.annotation(CLIENT_QUERY_PARAMS)));

        headerFillersByName.putAll(methodLevelHeadersByName);
    }

    private void putAllQueryAnnotations(Map<String, QueryData> headerMap, ClassInfo interfaceClass,
            AnnotationInstance[] annotations) {
        for (AnnotationInstance annotation : annotations) {
            String name = annotation.value("name").asString();
            if (headerMap.put(name, new QueryData(annotation, interfaceClass)) != null) {
                throw new RestClientDefinitionException("Duplicate ClientQueryParam annotation for query parameter: " + name +
                        " on " + annotation.target());
            }
        }
    }

    private void addQueryParam(MethodInfo declaringMethod, MethodCreator methodCreator,
            QueryData queryData,
            AssignableResultHandle webTargetImpl, BuildProducer<GeneratedClassBuildItem> generatedClasses,
            IndexView index) {

        AnnotationInstance annotation = queryData.annotation;
        ClassInfo declaringClass = queryData.definingClass;

        String queryName = annotation.value("name").asString();
        ResultHandle queryNameHandle = methodCreator.load(queryName);

        ResultHandle isQueryParamPresent = methodCreator.invokeStaticMethod(
                MethodDescriptor.ofMethod(ClientQueryParamSupport.class, "isQueryParamPresent", boolean.class,
                        WebTargetImpl.class, String.class),
                webTargetImpl, queryNameHandle);
        BytecodeCreator creator = methodCreator.ifTrue(isQueryParamPresent).falseBranch();

        String[] values = annotation.value().asStringArray();

        if (values.length == 0) {
            log.warnv("Ignoring ClientQueryParam that specifies an empty array of header values for header {} on {}",
                    annotation.value("name").asString(), annotation.target());
            return;
        }

        if (values.length > 1 || !(values[0].startsWith("{") && values[0].endsWith("}"))) {
            boolean required = annotation.valueWithDefault(index, "required").asBoolean();
            ResultHandle valuesList = creator.newInstance(MethodDescriptor.ofConstructor(ArrayList.class));
            for (String value : values) {
                if (value.contains("${")) {
                    ResultHandle queryValueFromConfig = creator.invokeStaticMethod(
                            MethodDescriptor.ofMethod(ConfigUtils.class, "interpolate", String.class, String.class,
                                    boolean.class),
                            creator.load(value), creator.load(required));
                    creator.ifNotNull(queryValueFromConfig)
                            .trueBranch().invokeInterfaceMethod(LIST_ADD_METHOD, valuesList, queryValueFromConfig);
                } else {
                    creator.invokeInterfaceMethod(LIST_ADD_METHOD, valuesList, creator.load(value));
                }
            }

            creator.assign(webTargetImpl, creator.invokeVirtualMethod(WEB_TARGET_IMPL_QUERY_PARAMS, webTargetImpl,
                    queryNameHandle, valuesList));
        } else { // method call :O {some.package.ClassName.methodName} or {defaultMethodWithinThisInterfaceName}
            // if `!required` an exception on header filling does not fail the invocation:
            boolean required = annotation.valueWithDefault(index, "required").asBoolean();

            BytecodeCreator methodCallCreator = creator;
            TryBlock tryBlock = null;

            if (!required) {
                tryBlock = creator.tryBlock();
                methodCallCreator = tryBlock;
            }
            String methodName = values[0].substring(1, values[0].length() - 1); // strip curly braces

            MethodInfo queryValueMethod;
            ResultHandle queryValue;
            if (methodName.contains(".")) {
                // calling a static method
                int endOfClassName = methodName.lastIndexOf('.');
                String className = methodName.substring(0, endOfClassName);
                String staticMethodName = methodName.substring(endOfClassName + 1);

                ClassInfo clazz = index.getClassByName(DotName.createSimple(className));
                if (clazz == null) {
                    throw new RestClientDefinitionException(
                            "Class " + className + " used in ClientQueryParam on " + declaringClass + " not found");
                }
                queryValueMethod = findMethod(clazz, declaringClass, staticMethodName, CLIENT_QUERY_PARAM.toString());

                if (queryValueMethod.parametersCount() == 0) {
                    queryValue = methodCallCreator.invokeStaticMethod(queryValueMethod);
                } else if (queryValueMethod.parametersCount() == 1 && isString(queryValueMethod.parameterType(0))) {
                    queryValue = methodCallCreator.invokeStaticMethod(queryValueMethod, methodCallCreator.load(queryName));
                } else {
                    throw new RestClientDefinitionException(
                            "ClientQueryParam method " + declaringClass.toString() + "#" + staticMethodName
                                    + " has too many parameters, at most one parameter, header name, expected");
                }
            } else {
                // interface method
                String mockName = mockInterface(declaringClass, generatedClasses, index);
                ResultHandle interfaceMock = methodCallCreator.newInstance(MethodDescriptor.ofConstructor(mockName));

                queryValueMethod = findMethod(declaringClass, declaringClass, methodName, CLIENT_QUERY_PARAM.toString());

                if (queryValueMethod == null) {
                    throw new RestClientDefinitionException(
                            "ClientQueryParam method " + methodName + " not found on " + declaringClass);
                }

                if (queryValueMethod.parametersCount() == 0) {
                    queryValue = methodCallCreator.invokeInterfaceMethod(queryValueMethod, interfaceMock);
                } else if (queryValueMethod.parametersCount() == 1 && isString(queryValueMethod.parameterType(0))) {
                    queryValue = methodCallCreator.invokeInterfaceMethod(queryValueMethod, interfaceMock,
                            methodCallCreator.load(queryName));
                } else {
                    throw new RestClientDefinitionException(
                            "ClientQueryParam method " + declaringClass + "#" + methodName
                                    + " has too many parameters, at most one parameter, header name, expected");
                }

            }

            Type returnType = queryValueMethod.returnType();
            ResultHandle valuesList;
            if (returnType.kind() == Type.Kind.ARRAY && returnType.asArrayType().component().name().equals(STRING)) {
                // repack array to list
                valuesList = methodCallCreator.invokeStaticMethod(
                        MethodDescriptor.ofMethod(Arrays.class, "asList", List.class, Object[].class), queryValue);
            } else if (returnType.kind() == Type.Kind.CLASS && returnType.name().equals(STRING)) {
                valuesList = methodCallCreator.newInstance(MethodDescriptor.ofConstructor(ArrayList.class));
                methodCallCreator.invokeInterfaceMethod(LIST_ADD_METHOD, valuesList, queryValue);
            } else {
                throw new RestClientDefinitionException("Method " + declaringClass.toString() + "#" + methodName
                        + " has an unsupported return type for ClientQueryParam. " +
                        "Only String and String[] return types are supported");
            }
            methodCallCreator.assign(webTargetImpl,
                    methodCallCreator.invokeVirtualMethod(WEB_TARGET_IMPL_QUERY_PARAMS, webTargetImpl, queryNameHandle,
                            valuesList));

            if (!required) {
                CatchBlockCreator catchBlock = tryBlock.addCatch(Exception.class);
                ResultHandle log = catchBlock.invokeStaticMethod(
                        MethodDescriptor.ofMethod(Logger.class, "getLogger", Logger.class, String.class),
                        catchBlock.load(declaringClass.name().toString()));
                String errorMessage = String.format(
                        "Invoking query param generation method '%s' for '%s' on method '%s#%s' failed",
                        methodName, queryName, declaringClass.name(), declaringMethod.name());
                catchBlock.invokeVirtualMethod(
                        MethodDescriptor.ofMethod(Logger.class, "warn", void.class, Object.class, Throwable.class),
                        log,
                        catchBlock.load(errorMessage), catchBlock.getCaughtException());
            }
        }
    }

    @Override
    public void forSubResourceMethod(ClassCreator subClassCreator, MethodCreator subConstructor,
            MethodCreator subClinit, MethodCreator subMethodCreator, ClassInfo rootInterfaceClass,
            ClassInfo subInterfaceClass, MethodInfo subMethod, MethodInfo rootMethod,
            AssignableResultHandle invocationBuilder, // sub-level
            IndexView index, BuildProducer<GeneratedClassBuildItem> generatedClasses,
            int methodIndex, int subMethodIndex, FieldDescriptor javaMethodField) {

        addJavaMethodToContext(javaMethodField, subMethodCreator, invocationBuilder);

        Map<String, HeaderData> headerFillersByName = new HashMap<>();
        collectHeaderFillers(rootInterfaceClass, rootMethod, headerFillersByName);
        collectHeaderFillers(subInterfaceClass, subMethod, headerFillersByName);
        String subHeaderFillerName = subInterfaceClass.name().toString() + sha1(rootInterfaceClass.name().toString()) +
                "$$" + methodIndex + "$$" + subMethodIndex;
        createAndReturnHeaderFiller(subClassCreator, subConstructor, subMethodCreator, subMethod,
                invocationBuilder, index, generatedClasses, subMethodIndex, subHeaderFillerName, headerFillersByName);
    }

    @Override
    public void forMethod(ClassCreator classCreator, MethodCreator constructor,
            MethodCreator clinit, MethodCreator methodCreator, ClassInfo interfaceClass,
            MethodInfo method, AssignableResultHandle invocationBuilder, IndexView index,
            BuildProducer<GeneratedClassBuildItem> generatedClasses, int methodIndex, FieldDescriptor javaMethodField) {

        addJavaMethodToContext(javaMethodField, methodCreator, invocationBuilder);

        // header filler

        Map<String, HeaderData> headerFillersByName = new HashMap<>();

        collectHeaderFillers(interfaceClass, method, headerFillersByName);

        createAndReturnHeaderFiller(classCreator, constructor, methodCreator, method,
                invocationBuilder, index, generatedClasses, methodIndex,
                interfaceClass + "$$" + method.name() + "$$" + methodIndex, headerFillersByName);
    }

    private void createAndReturnHeaderFiller(ClassCreator classCreator, MethodCreator constructor,
            MethodCreator methodCreator, MethodInfo method,
            AssignableResultHandle invocationBuilder, IndexView index,
            BuildProducer<GeneratedClassBuildItem> generatedClasses, int methodIndex, String fillerClassName,
            Map<String, HeaderData> headerFillersByName) {
        FieldDescriptor headerFillerField = FieldDescriptor.of(classCreator.getClassName(),
                "headerFiller" + methodIndex, HeaderFiller.class);
        classCreator.getFieldCreator(headerFillerField).setModifiers(Modifier.PRIVATE | Modifier.FINAL);
        ResultHandle headerFiller;
        // create header filler for this method if headerFillersByName is not empty
        if (!headerFillersByName.isEmpty()) {
            GeneratedClassGizmoAdaptor classOutput = new GeneratedClassGizmoAdaptor(generatedClasses, true);
            try (ClassCreator headerFillerClass = ClassCreator.builder().className(fillerClassName)
                    .interfaces(ExtendedHeaderFiller.class)
                    .classOutput(classOutput)
                    .build()) {
                FieldCreator logField = headerFillerClass.getFieldCreator("log", Logger.class);
                logField.setModifiers(Modifier.FINAL | Modifier.STATIC | Modifier.PRIVATE);

                MethodCreator staticConstructor = headerFillerClass.getMethodCreator("<clinit>", void.class);
                staticConstructor.setModifiers(ACC_STATIC);
                ResultHandle log = staticConstructor.invokeStaticMethod(
                        MethodDescriptor.ofMethod(Logger.class, "getLogger", Logger.class, String.class),
                        staticConstructor.load(fillerClassName));
                staticConstructor.writeStaticField(logField.getFieldDescriptor(), log);
                staticConstructor.returnValue(null);

                MethodCreator fillHeaders = headerFillerClass
                        .getMethodCreator(
                                MethodDescriptor.ofMethod(HeaderFiller.class, "addHeaders", void.class,
                                        MultivaluedMap.class, ResteasyReactiveClientRequestContext.class));

                for (Map.Entry<String, HeaderData> headerEntry : headerFillersByName.entrySet()) {
                    addHeaderParam(method, fillHeaders, headerEntry.getValue(), generatedClasses,
                            fillerClassName, index);
                }
                fillHeaders.returnValue(null);

                headerFiller = constructor.newInstance(MethodDescriptor.ofConstructor(fillerClassName));
            }
        } else {
            headerFiller = constructor
                    .readStaticField(FieldDescriptor.of(NoOpHeaderFiller.class, "INSTANCE", NoOpHeaderFiller.class));
        }
        constructor.writeInstanceField(headerFillerField, constructor.getThis(), headerFiller);

        ResultHandle headerFillerAsObject = methodCreator.checkCast(
                methodCreator.readInstanceField(headerFillerField, methodCreator.getThis()), Object.class);
        methodCreator.assign(invocationBuilder,
                methodCreator.invokeInterfaceMethod(INVOCATION_BUILDER_PROPERTY_METHOD, invocationBuilder,
                        methodCreator.load(HeaderFiller.class.getName()), headerFillerAsObject));

        ResultHandle parametersList = null;
        if (method.parametersCount() == 0) {
            parametersList = methodCreator.invokeStaticMethod(ofMethod(
                    Collections.class, "emptyList", List.class));
        } else {
            ResultHandle parametersArray = methodCreator.newArray(Object.class,
                    method.parametersCount());
            for (int i = 0; i < method.parametersCount(); i++) {
                methodCreator.writeArrayValue(parametersArray, i, methodCreator.getMethodParam(i));
            }
            parametersList = methodCreator.invokeStaticMethod(
                    MethodDescriptor.ofMethod(Arrays.class, "asList", List.class, Object[].class), parametersArray);
        }
        methodCreator.assign(invocationBuilder,
                methodCreator.invokeInterfaceMethod(INVOCATION_BUILDER_PROPERTY_METHOD, invocationBuilder,
                        methodCreator.load(INVOKED_METHOD_PARAMETERS_PROP), parametersList));
    }

    private void collectHeaderFillers(ClassInfo interfaceClass, MethodInfo method,
            Map<String, HeaderData> headerFillersByName) {
        AnnotationInstance classLevelHeader = interfaceClass.classAnnotation(CLIENT_HEADER_PARAM);
        if (classLevelHeader != null) {
            headerFillersByName.put(classLevelHeader.value("name").asString(),
                    new HeaderData(classLevelHeader, interfaceClass));
        }
        putAllHeaderAnnotations(headerFillersByName,
                interfaceClass,
                extractAnnotations(interfaceClass.classAnnotation(CLIENT_HEADER_PARAMS)));

        Map<String, HeaderData> methodLevelHeadersByName = new HashMap<>();
        AnnotationInstance methodLevelHeader = method.annotation(CLIENT_HEADER_PARAM);
        if (methodLevelHeader != null) {
            methodLevelHeadersByName.put(methodLevelHeader.value("name").asString(),
                    new HeaderData(methodLevelHeader, interfaceClass));
        }
        putAllHeaderAnnotations(methodLevelHeadersByName, interfaceClass,
                extractAnnotations(method.annotation(CLIENT_HEADER_PARAMS)));

        headerFillersByName.putAll(methodLevelHeadersByName);
    }

    /**
     * create a field in the stub class to contain (interface) java.lang.reflect.Method corresponding to this method
     * MP Rest Client spec says it has to be in the request context, keeping it in a field we don't have to
     * initialize it on each call
     *
     * @param javaMethodField method reference in a static class field
     * @param methodCreator method for which we put the java.lang.reflect.Method to context (aka this method)
     * @param invocationBuilder Invocation.Builder in this method
     */
    private void addJavaMethodToContext(FieldDescriptor javaMethodField, MethodCreator methodCreator,
            AssignableResultHandle invocationBuilder) {
        ResultHandle javaMethod = methodCreator.readStaticField(javaMethodField);
        ResultHandle javaMethodAsObject = methodCreator.checkCast(javaMethod, Object.class);
        methodCreator.assign(invocationBuilder,
                methodCreator.invokeInterfaceMethod(INVOCATION_BUILDER_PROPERTY_METHOD, invocationBuilder,
                        methodCreator.load(INVOKED_METHOD_PROP), javaMethodAsObject));
    }

    private void putAllHeaderAnnotations(Map<String, HeaderData> headerMap, ClassInfo interfaceClass,
            AnnotationInstance[] annotations) {
        for (AnnotationInstance annotation : annotations) {
            String headerName = annotation.value("name").asString();
            if (headerMap.put(headerName, new HeaderData(annotation, interfaceClass)) != null) {
                throw new RestClientDefinitionException("Duplicate ClientHeaderParam annotation for header: " + headerName +
                        " on " + annotation.target());
            }
        }
    }

    // fillHeaders takes `MultivaluedMap<String, String>` as param and modifies it
    private void addHeaderParam(MethodInfo declaringMethod, MethodCreator fillHeadersCreator,
            HeaderData headerData,
            BuildProducer<GeneratedClassBuildItem> generatedClasses,
            String fillerClassName,
            IndexView index) {

        AnnotationInstance annotation = headerData.annotation;
        ClassInfo declaringClass = headerData.definingClass;

        String headerName = annotation.value("name").asString();

        String[] values = annotation.value().asStringArray();

        if (values.length == 0) {
            log.warnv("Ignoring ClientHeaderParam that specifies an empty array of header values for header {} on {}",
                    annotation.value("name").asString(), annotation.target());
            return;
        }

        ResultHandle headerMap = fillHeadersCreator.getMethodParam(0);
        ResultHandle requestContext = fillHeadersCreator.getMethodParam(1);

        // if headers are set here, they were set with @HeaderParam, which should take precedence of MP ways
        BytecodeCreator fillHeaders = fillHeadersCreator
                .ifTrue(fillHeadersCreator.invokeStaticMethod(HEADER_FILLER_UTIL_SHOULD_ADD_HEADER,
                        fillHeadersCreator.load(headerName), headerMap, requestContext))
                .trueBranch();

        if (values.length > 1 || !(values[0].startsWith("{") && values[0].endsWith("}"))) {
            boolean required = annotation.valueWithDefault(index, "required").asBoolean();
            ResultHandle headerList = fillHeaders.newInstance(MethodDescriptor.ofConstructor(ArrayList.class));
            for (String value : values) {
                if (value.contains("${")) {
                    ResultHandle headerValueFromConfig = fillHeaders.invokeStaticMethod(
                            MethodDescriptor.ofMethod(ConfigUtils.class, "interpolate", String.class, String.class,
                                    boolean.class),
                            fillHeaders.load(value), fillHeaders.load(required));
                    fillHeaders.ifNotNull(headerValueFromConfig)
                            .trueBranch().invokeInterfaceMethod(LIST_ADD_METHOD, headerList, headerValueFromConfig);
                } else {
                    fillHeaders.invokeInterfaceMethod(LIST_ADD_METHOD, headerList, fillHeaders.load(value));
                }
            }

            fillHeaders.invokeInterfaceMethod(MAP_PUT_METHOD, headerMap, fillHeaders.load(headerName), headerList);
        } else { // method call :O {some.package.ClassName.methodName} or {defaultMethodWithinThisInterfaceName}
            // if `!required` an exception on header filling does not fail the invocation:
            boolean required = annotation.valueWithDefault(index, "required").asBoolean();

            BytecodeCreator fillHeader = fillHeaders;
            TryBlock tryBlock = null;

            if (!required) {
                tryBlock = fillHeaders.tryBlock();
                fillHeader = tryBlock;
            }
            String methodName = values[0].substring(1, values[0].length() - 1); // strip curly braces

            MethodInfo headerFillingMethod;
            ResultHandle headerValue;
            if (methodName.contains(".")) {
                // calling a static method
                int endOfClassName = methodName.lastIndexOf('.');
                String className = methodName.substring(0, endOfClassName);
                String staticMethodName = methodName.substring(endOfClassName + 1);

                ClassInfo clazz = index.getClassByName(DotName.createSimple(className));
                if (clazz == null) {
                    throw new RestClientDefinitionException(
                            "Class " + className + " used in ClientHeaderParam on " + declaringClass + " not found");
                }
                headerFillingMethod = findMethod(clazz, declaringClass, staticMethodName, CLIENT_HEADER_PARAM.toString());

                if (headerFillingMethod.parametersCount() == 0) {
                    headerValue = fillHeader.invokeStaticMethod(headerFillingMethod);
                } else if (headerFillingMethod.parametersCount() == 1 && isString(headerFillingMethod.parameterType(0))) {
                    headerValue = fillHeader.invokeStaticMethod(headerFillingMethod, fillHeader.load(headerName));
                } else if (headerFillingMethod.parametersCount() == 1
                        && isComputedParamContext(headerFillingMethod.parameterType(0))) {
                    ResultHandle fillerParam = fillHeader
                            .newInstance(MethodDescriptor.ofConstructor(ComputedParamContextImpl.class, String.class,
                                    ClientRequestContext.class), fillHeader.load(headerName), requestContext);
                    headerValue = fillHeader.invokeStaticMethod(headerFillingMethod, fillerParam);
                } else {
                    throw new RestClientDefinitionException(
                            "ClientHeaderParam method " + declaringClass.toString() + "#" + staticMethodName
                                    + " has too many parameters, at most one parameter, header name, expected");
                }
            } else {
                // interface method
                String mockName = mockInterface(declaringClass, generatedClasses, index);
                ResultHandle interfaceMock = fillHeader.newInstance(MethodDescriptor.ofConstructor(mockName));

                headerFillingMethod = findMethod(declaringClass, declaringClass, methodName, CLIENT_HEADER_PARAM.toString());

                if (headerFillingMethod == null) {
                    throw new RestClientDefinitionException(
                            "ClientHeaderParam method " + methodName + " not found on " + declaringClass);
                }

                if (headerFillingMethod.parametersCount() == 0) {
                    headerValue = fillHeader.invokeInterfaceMethod(headerFillingMethod, interfaceMock);
                } else if (headerFillingMethod.parametersCount() == 1 && isString(headerFillingMethod.parameterType(0))) {
                    headerValue = fillHeader.invokeInterfaceMethod(headerFillingMethod, interfaceMock,
                            fillHeader.load(headerName));
                } else if (headerFillingMethod.parametersCount() == 1
                        && isComputedParamContext(headerFillingMethod.parameterType(0))) {
                    ResultHandle fillerParam = fillHeader
                            .newInstance(MethodDescriptor.ofConstructor(ComputedParamContextImpl.class, String.class,
                                    ClientRequestContext.class), fillHeader.load(headerName), requestContext);
                    headerValue = fillHeader.invokeInterfaceMethod(headerFillingMethod, interfaceMock,
                            fillerParam);
                } else {
                    throw new RestClientDefinitionException(
                            "ClientHeaderParam method " + declaringClass + "#" + methodName
                                    + " has too many parameters, at most one parameter, header name, expected");
                }

            }

            Type returnType = headerFillingMethod.returnType();
            ResultHandle headerList;
            if (returnType.kind() == Type.Kind.ARRAY && returnType.asArrayType().component().name().equals(STRING)) {
                // repack array to list
                headerList = fillHeader.invokeStaticMethod(
                        MethodDescriptor.ofMethod(Arrays.class, "asList", List.class, Object[].class), headerValue);
            } else if (returnType.kind() == Type.Kind.CLASS && returnType.name().equals(STRING)) {
                headerList = fillHeader.newInstance(MethodDescriptor.ofConstructor(ArrayList.class));
                fillHeader.invokeInterfaceMethod(LIST_ADD_METHOD, headerList, headerValue);
            } else {
                throw new RestClientDefinitionException("Method " + declaringClass.toString() + "#" + methodName
                        + " has an unsupported return type for ClientHeaderParam. " +
                        "Only String and String[] return types are supported");
            }
            fillHeader.invokeInterfaceMethod(MAP_PUT_METHOD, headerMap, fillHeader.load(headerName), headerList);

            if (!required) {
                CatchBlockCreator catchBlock = tryBlock.addCatch(Exception.class);
                ResultHandle log = catchBlock.readStaticField(FieldDescriptor.of(fillerClassName, "log", Logger.class));
                String errorMessage = String.format(
                        "Invoking header generation method '%s' for header '%s' on method '%s#%s' failed",
                        methodName, headerName, declaringClass.name(), declaringMethod.name());
                catchBlock.invokeVirtualMethod(
                        MethodDescriptor.ofMethod(Logger.class, "warn", void.class, Object.class, Throwable.class),
                        log,
                        catchBlock.load(errorMessage), catchBlock.getCaughtException());
            }
        }
    }

    private MethodInfo findMethod(ClassInfo declaringClass, ClassInfo restInterface, String methodName,
            String sourceAnnotationName) {
        MethodInfo result = null;
        for (MethodInfo method : declaringClass.methods()) {
            if (method.name().equals(methodName)) {
                if (result != null) {
                    throw new RestClientDefinitionException(String.format(
                            "Ambiguous %s definition, more than one method of name %s found on %s. Problematic interface: %s",
                            sourceAnnotationName, methodName, declaringClass, restInterface));
                } else {
                    result = method;
                }
            }
        }
        return result;
    }

    private static boolean isString(Type type) {
        return type.kind() == Type.Kind.CLASS && type.name().toString().equals(String.class.getName());
    }

    private static boolean isComputedParamContext(Type type) {
        return type.kind() == Type.Kind.CLASS && type.name().toString().equals(ComputedParamContext.class.getName());
    }

    private String mockInterface(ClassInfo declaringClass, BuildProducer<GeneratedClassBuildItem> generatedClass,
            IndexView index) {
        // we have an interface, we have to call a default method on it, we generate a (very simplistic) implementation:

        return interfaceMocks.computeIfAbsent(declaringClass, classInfo -> {
            String mockName = declaringClass.toString() + HashUtil.sha1(declaringClass.toString());
            ClassOutput classOutput = new GeneratedClassGizmoAdaptor(generatedClass, true);
            List<DotName> interfaceNames = declaringClass.interfaceNames();
            Set<MethodInfo> methods = new HashSet<>();
            for (DotName interfaceName : interfaceNames) {
                ClassInfo interfaceClass = index.getClassByName(interfaceName);
                methods.addAll(interfaceClass.methods());
            }
            methods.addAll(declaringClass.methods());

            try (ClassCreator classCreator = ClassCreator.builder().className(mockName).interfaces(declaringClass.toString())
                    .classOutput(classOutput)
                    .build()) {

                for (MethodInfo method : methods) {
                    if (Modifier.isAbstract(method.flags())) {
                        MethodCreator methodCreator = classCreator.getMethodCreator(MethodDescriptor.of(method));
                        methodCreator.throwException(IllegalStateException.class, "This should never be called");
                    }
                }
            }
            return mockName;
        });
    }

    private AnnotationInstance[] extractAnnotations(AnnotationInstance groupAnnotation) {
        if (groupAnnotation != null) {
            AnnotationValue annotationValue = groupAnnotation.value();
            if (annotationValue != null) {
                return annotationValue.asNestedArray();
            }
        }
        return EMPTY_ANNOTATION_INSTANCES;
    }

    /**
     * ClientHeaderParam annotations can be defined on a JAX-RS interface or a sub-client (sub-resource).
     * If we're filling headers for a sub-client, we need to know the defining class of the ClientHeaderParam
     * to properly resolve default methods of the "root" client
     */
    private static class HeaderData {
        private final AnnotationInstance annotation;
        private final ClassInfo definingClass;

        public HeaderData(AnnotationInstance annotation, ClassInfo definingClass) {
            this.annotation = annotation;
            this.definingClass = definingClass;
        }
    }

    /**
     * ClientQueryParam annotations can be defined on a JAX-RS interface or a sub-client (sub-resource).
     * If we're adding query params for a sub-client, we need to know the defining class of the ClientHeaderParam
     * to properly resolve default methods of the "root" client
     */
    private static class QueryData {
        private final AnnotationInstance annotation;
        private final ClassInfo definingClass;

        public QueryData(AnnotationInstance annotation, ClassInfo definingClass) {
            this.annotation = annotation;
            this.definingClass = definingClass;
        }
    }
}
