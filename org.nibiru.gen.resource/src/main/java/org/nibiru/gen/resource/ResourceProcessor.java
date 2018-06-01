package org.nibiru.gen.resource;

import com.google.common.base.Charsets;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;
import com.squareup.javapoet.*;
import org.nibiru.gen.api.resource.Resource;
import org.nibiru.gen.core.BaseProcessor;

import javax.annotation.Nullable;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@SupportedAnnotationTypes("org.nibiru.gen.api.resource.Resource")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class ResourceProcessor
        extends BaseProcessor {
    public ResourceProcessor() {
        super(Resource.class);
    }

    @Override
    protected Iterable<JavaFile> generate(Set<? extends Element> elements) {
        Map<TypeElement, TypeSpec.Builder> types = Maps.newHashMap();
        Map<String, JavaFile> byteArrayResources = Maps.newHashMap();
        Map<String, JavaFile> stringResources = Maps.newHashMap();
        for (ExecutableElement executableElement : ElementFilter.methodsIn(elements)) {
            TypeElement typeElement = (TypeElement) executableElement.getEnclosingElement();

            TypeSpec.Builder builder = types.computeIfAbsent(typeElement,
                    (type) -> TypeSpec.classBuilder(type.getSimpleName()
                            + "Impl")
                            .addModifiers(Modifier.PUBLIC)
                            .addSuperinterface(ClassName.get(type)));

            String resourcePath = resolveRelativePaths(typeElement.getEnclosingElement()
                    .toString()
                    .replaceAll("\\.", "/")
                    + "/"
                    + executableElement.getAnnotation(Resource.class).value());

            TypeMirror returnType = executableElement.getReturnType();

            JavaFile resource;
            String propertysufix;
            if (isByteArray(returnType)) {
                resource = byteArrayResources.computeIfAbsent(resourcePath,
                        this::buildByteResourceType);
                propertysufix = "";
            } else if (isString(returnType)) {
                resource = stringResources.computeIfAbsent(resourcePath,
                        this::buildStringResourceType);
                propertysufix = ".toString()";
            } else {
                throw new IllegalStateException("Invalid return type for resource: "
                        + returnType
                        + ". It must be byte[] or String");
            }
            buildResourceMethod(executableElement,
                    builder,
                    resource.typeSpec,
                    resourcePath,
                    propertysufix);
        }

        return FluentIterable.concat(types.entrySet()
                        .stream()
                        .map(e -> buildJavaFile(e.getKey(),
                                e.getValue()))
                        .collect(Collectors.toList()),
                byteArrayResources.values(),
                stringResources.values())
                .filter(Objects::nonNull)
                .toList();
    }

    private boolean isByteArray(TypeMirror type) {
        return type instanceof ArrayType
                && ((ArrayType) type).getComponentType()
                .getKind() == TypeKind.BYTE;
    }

    private JavaFile buildByteResourceType(String resourcePath) {
        File resourceFile = findFile(resourcePath);
        if (resourceFile != null) {
            try (InputStream in = new FileInputStream(resourceFile)) {
                TypeSpec.Builder builder = TypeSpec.classBuilder("b" + Hashing.sha256()
                        .hashString(resourceFile.getName(), Charsets.UTF_8))
                        .addModifiers(Modifier.PUBLIC);

                builder.addField(FieldSpec.builder(TypeName.get(byte[].class),
                        "data",
                        Modifier.PUBLIC,
                        Modifier.FINAL,
                        Modifier.STATIC)
                        .initializer("new byte[" + resourceFile.length() + "]")
                        .build());


                CodeBlock.Builder staticInit = CodeBlock.builder();
                MethodSpec.Builder currentMethod = MethodSpec.methodBuilder("i0")
                        .addModifiers(Modifier.STATIC)
                        .addModifiers(Modifier.PRIVATE);

                int init = 0;
                int n = 0;
                int size = 0;
                for (byte b : ByteStreams.toByteArray(in)) {
                    currentMethod.addStatement("data[" + n + "] = " + b);
                    n++;
                    size++;
                    if (size > 1000) {
                        init++;
                        MethodSpec methodSpec = currentMethod.build();
                        staticInit.add(methodSpec.name + "();");
                        builder.addMethod(methodSpec);
                        size = 0;
                        currentMethod = MethodSpec.methodBuilder("i" + init)
                                .addModifiers(Modifier.STATIC)
                                .addModifiers(Modifier.PRIVATE);
                    }
                }
                MethodSpec methodSpec = currentMethod.build();
                staticInit.add(methodSpec.name + "();");
                builder.addMethod(methodSpec);
                builder.addStaticBlock(staticInit
                        .build());

                return JavaFile.builder(getPackage(resourcePath), builder.build())
                        .build();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            return null;
        }
    }

    private JavaFile buildStringResourceType(String resourcePath) {
        File resourceFile = findFile(resourcePath);
        if (resourceFile != null) {
            try (InputStream in = new FileInputStream(resourceFile)) {
                TypeSpec.Builder builder = TypeSpec.classBuilder("s" + Hashing.sha256()
                        .hashString(resourceFile.getName(), Charsets.UTF_8))
                        .addModifiers(Modifier.PUBLIC);

                builder.addField(FieldSpec.builder(TypeName.get(StringBuilder.class),
                        "data",
                        Modifier.PUBLIC,
                        Modifier.FINAL,
                        Modifier.STATIC)
                        .initializer("new StringBuilder()")
                        .build());


                CodeBlock.Builder staticInit = CodeBlock.builder();
                MethodSpec.Builder currentMethod = MethodSpec.methodBuilder("i0")
                        .addModifiers(Modifier.STATIC)
                        .addModifiers(Modifier.PRIVATE);

                int init = 0;
                int n = 0;
                int size = 0;
                for (char b : BaseEncoding.base64()
                        .encode(ByteStreams.toByteArray(in))
                        .toCharArray()) {
                    currentMethod.addStatement("data.append('" + b + "')");
                    n++;
                    size++;
                    if (size > 1000) {
                        init++;
                        MethodSpec methodSpec = currentMethod.build();
                        staticInit.add(methodSpec.name + "();");
                        builder.addMethod(methodSpec);
                        size = 0;
                        currentMethod = MethodSpec.methodBuilder("i" + init)
                                .addModifiers(Modifier.STATIC)
                                .addModifiers(Modifier.PRIVATE);
                    }
                }
                MethodSpec methodSpec = currentMethod.build();
                staticInit.add(methodSpec.name + "();");
                builder.addMethod(methodSpec);
                builder.addStaticBlock(staticInit
                        .build());

                return JavaFile.builder(getPackage(resourcePath), builder.build())
                        .build();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            return null;
        }
    }

    private void buildResourceMethod(ExecutableElement element,
                                     TypeSpec.Builder builder,
                                     @Nullable TypeSpec resourceType,
                                     @Nullable String resourcePath,
                                     String propertySufix) {
        MethodSpec.Builder methodBuilder = buildMethod(element);

        if (resourceType != null && resourcePath != null) {
            builder.addMethod(methodBuilder.addStatement("return "
                    + getPackage(resourcePath) + "."
                    + resourceType.name
                    + ".data"
                    + propertySufix)
                    .build());
        } else {
            builder.addMethod(methodBuilder.addStatement("return null")
                    .build());
        }
    }

    private String getPackage(String path) {
        return path
                .substring(0, path.lastIndexOf('/'))
                .replaceAll("/", ".");
    }
}
