package org.nibiru.gen.service;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
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
                .addMethod(MethodSpec.methodBuilder("request")
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
                                "                : builder).build()")
                        .returns(HttpRequest.class)
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

        methodBuilder.addStatement("return service.invoke(request($S, "
                        + "$L, "
                        + "org.nibiru.mobile.core.api.http.HttpMethod.$L), "
                        + "$L.class)",
                path.value(),
                params.size() == 1
                        ? params.get(0).getSimpleName()
                        : "null",
                httpMethod(element),
                returnType.getTypeArguments().get(0));

        builder.addMethod(methodBuilder.build());
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
