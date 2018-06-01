package org.nibiru.gen.service;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.squareup.javapoet.*;
import org.nibiru.async.core.api.promise.Promise;
import org.nibiru.gen.core.BaseProcessor;
import org.nibiru.mobile.core.api.http.HttpMethod;
import org.nibiru.mobile.core.api.http.HttpRequest;
import org.nibiru.mobile.core.api.service.RemoteService;

import javax.annotation.Nullable;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.inject.Inject;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.ws.rs.*;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

@SupportedAnnotationTypes("javax.ws.rs.Path")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class ServiceProcessor
        extends BaseProcessor {
    private static final String FIELD_INIT = "this.$L = com.google.common.base.Preconditions.checkNotNull($L)";
    private static final Class<?> REMOTE_SERVICE_TYPE = RemoteService.class;
    private static final String REMOTE_SERVICE_NAME = "service";
    private static final TypeName REQUEST_BUILDER_INTERCEPTOR_TYPE = ParameterizedTypeName.get(Function.class,
            HttpRequest.Builder.class,
            HttpRequest.Builder.class);
    private static final String REQUEST_BUILDER_INTERCEPTOR_NAME = "requestBuilderInterceptor";
    private static final Map<Class<? extends Annotation>, HttpMethod> ANNOTATION_TO_HTTP_METHOD =
            ImmutableMap.of(DELETE.class, HttpMethod.DELETE,
                    GET.class, HttpMethod.GET,
                    HEAD.class, HttpMethod.HEAD,
                    POST.class, HttpMethod.POST,
                    PUT.class, HttpMethod.PUT);
    private static final String GETTER_PREFIX = "get";

    public ServiceProcessor() {
        super(Path.class);
    }

    @Override
    protected Iterable<JavaFile> generate(Set<? extends Element> elements) {
        Map<TypeElement, TypeSpec.Builder> types = Maps.newHashMap();

        for (ExecutableElement executableElement : ElementFilter.methodsIn(elements)) {
            TypeElement typeElement = (TypeElement) executableElement.getEnclosingElement();

            TypeSpec.Builder builder = types.computeIfAbsent(typeElement,
                    this::buildServiceClass);


            buildServiceMethod(executableElement,
                    builder);
        }

        return types.entrySet()
                .stream()
                .map(e -> buildJavaFile(e.getKey(),
                        e.getValue()))
                .collect(Collectors.toList());
    }

    private TypeSpec.Builder buildServiceClass(TypeElement type) {
        return TypeSpec.classBuilder(type.getSimpleName()
                + "Impl")
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(ClassName.get(type))
                .addField(FieldSpec.builder(REMOTE_SERVICE_TYPE,
                        REMOTE_SERVICE_NAME,
                        Modifier.PRIVATE,
                        Modifier.FINAL)
                        .build())
                .addField(FieldSpec.builder(REQUEST_BUILDER_INTERCEPTOR_TYPE,
                        REQUEST_BUILDER_INTERCEPTOR_NAME,
                        Modifier.PRIVATE,
                        Modifier.FINAL)
                        .addAnnotation(Nullable.class)
                        .build())
                .addMethod(MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PUBLIC)
                        .addAnnotation(Inject.class)
                        .addParameter(REMOTE_SERVICE_TYPE, REMOTE_SERVICE_NAME)
                        .addParameter(ParameterSpec.builder(REQUEST_BUILDER_INTERCEPTOR_TYPE, REQUEST_BUILDER_INTERCEPTOR_NAME)
                                .addAnnotation(Nullable.class)
                                .build())
                        .addStatement(FIELD_INIT, REMOTE_SERVICE_NAME, REMOTE_SERVICE_NAME)
                        .addStatement(FIELD_INIT, REQUEST_BUILDER_INTERCEPTOR_NAME, REQUEST_BUILDER_INTERCEPTOR_NAME)
                        .build())
                .addMethod(MethodSpec.methodBuilder("requestBuilder")
                        .addModifiers(Modifier.PRIVATE)
                        .addParameter(String.class, "path")
                        .addParameter(ParameterSpec.builder(Object.class, "requestDto")
                                .addAnnotation(Nullable.class)
                                .build())
                        .addParameter(HttpMethod.class, "method")
                        .addStatement("HttpRequest.Builder builder = service.requestBuilder(path, requestDto)\n" +
                                "                .method(method)\n" +
                                "                .contentType(com.google.common.net.MediaType.JSON_UTF_8);\n" +
                                "        return (requestBuilderInterceptor != null\n" +
                                "                ? requestBuilderInterceptor.apply(builder)\n" +
                                "                : builder)")
                        .returns(HttpRequest.Builder.class)
                        .build());
    }

    private void buildServiceMethod(ExecutableElement element,
                                    TypeSpec.Builder builder) {
        List<? extends VariableElement> params = element.getParameters();
        checkArgument(params.size() <= 1, "Service method must have 0 or 1 parameters");


        MethodSpec.Builder methodBuilder = buildMethod(element);

        Path path = element.getAnnotation(Path.class);
        checkState(path != null, "Method must be annotated with %s", Path.class);


        TypeMirror returnTm = element.getReturnType();
        checkState(returnTm instanceof DeclaredType, "Method must return a class instance");
        DeclaredType returnType = (DeclaredType) returnTm;

        checkState(returnType.toString().startsWith(Promise.class.getName() + "<"),
                "Method must return an instance of %s", Promise.class);

        HttpMethod httpMethod = httpMethod(element);

        DeclaredType returnDt = (DeclaredType) returnType.getTypeArguments().get(0);
        String dtoReturnType = returnDt.getTypeArguments().isEmpty()
                ? returnDt + ".class"
                : "org.nibiru.mobile.core.api.serializer.TypeLiteral.create("
                + Splitter.on('<').split(returnDt.toString()).iterator().next()
                + ".class, "
                + Joiner.on(',').join(returnDt.getTypeArguments()
                .stream()
                .map((o) -> o + ".class")
                .collect(Collectors.toList()))
                + ")";

        if (httpMethod == HttpMethod.POST || httpMethod == HttpMethod.PUT) {
            methodBuilder.addStatement("return service.invoke(requestBuilder($S, "
                            + "$L, "
                            + "org.nibiru.mobile.core.api.http.HttpMethod.$L).build(), "
                            + "$L)",
                    path.value(),
                    params.size() == 1
                            ? name(params.get(0))
                            : "null",
                    httpMethod,
                    dtoReturnType);
        } else {
            Map<String, String> urlParams = Maps.newHashMap();
            for (VariableElement arg : element.getParameters()) {
                TypeMirror argType = arg.asType();
                if (argType.getKind().isPrimitive()) {
                    urlParams.put(name(arg), stringExpression(name(arg), argType));
                } else {
                    TypeElement typeElement = (TypeElement) arg;
                    ElementFilter.fieldsIn(typeElement.getEnclosedElements())
                            .stream()
                            .filter((v) -> v.getModifiers()
                                    .contains(Modifier.PUBLIC))
                            .forEach((v) -> urlParams.put(name(v),
                                    stringExpression(name(arg) + "." + name(v), v.asType())));
                    ElementFilter.methodsIn(typeElement.getEnclosedElements())
                            .stream()
                            .filter((m) -> m.getModifiers()
                                    .contains(Modifier.PUBLIC)
                                    && name(m).startsWith(GETTER_PREFIX))
                            .forEach((m) -> urlParams.put(getterName(m),
                                    stringExpression(name(arg) + "." + name(m) + "()", m.getReturnType())));
                }
            }

            methodBuilder.addStatement("return service.invoke(requestBuilder($S, "
                            + "null, "
                            + "org.nibiru.mobile.core.api.http.HttpMethod.$L)"
                            + "$L"
                            + ".build(), "
                            + "$L)",
                    path.value(),
                    httpMethod,
                    Joiner.on("").join(urlParams.entrySet()
                            .stream()
                            .map((e) -> ".queryParam(\"" + e.getKey() + "\", " + e.getValue() + ")\n")
                            .collect(Collectors.toList())),
                    dtoReturnType);

        }

        builder.addMethod(methodBuilder.build());
    }

    private String name(Element element) {
        return element.getSimpleName()
                .toString();
    }

    private String getterName(Element element) {
        String name = name(element).substring(GETTER_PREFIX.length());
        return name.substring(0, 1).toLowerCase() + name.substring(1);
    }

    private String stringExpression(String expr, TypeMirror type) {
        return isString(type)
                ? expr
                : "String.valueOf(" + expr + ")";
    }

    private HttpMethod httpMethod(ExecutableElement element) {
        for (Map.Entry<Class<? extends Annotation>, HttpMethod> entry
                : ANNOTATION_TO_HTTP_METHOD.entrySet()) {
            if (element.getAnnotation(entry.getKey()) != null) {
                return entry.getValue();
            }
        }
        throw new IllegalStateException("Method must be annotated with at least one HTTP method: " +
                Joiner.on(", ").join(Iterables
                        .transform(ANNOTATION_TO_HTTP_METHOD.keySet(), Object::toString)));
    }
}
