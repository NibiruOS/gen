package org.nibiru.gen.processor.resource;

import com.google.common.collect.Maps;
import com.google.common.io.ByteStreams;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import org.nibiru.gen.api.resource.Resource;
import org.nibiru.gen.processor.BaseProcessor;

import javax.annotation.Nullable;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
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
        for (ExecutableElement element : ElementFilter.methodsIn(elements)) {
            TypeElement typeElement = (TypeElement) element.getEnclosingElement();

            TypeSpec.Builder builder = types.computeIfAbsent(typeElement,
                    (type) -> TypeSpec.classBuilder(type.getSimpleName()
                            + "Impl")
                            .addModifiers(Modifier.PUBLIC)
                            .addSuperinterface(ClassName.get(type)));

            String resourcePath = typeElement.getEnclosingElement()
                    .toString()
                    .replaceAll("\\.", "/")
                    + "/"
                    + element.getAnnotation(Resource.class).value();

            File resourceFile = findFile(resourcePath);

            MethodSpec.Builder methodBuilder = buildMethod(element);

            buildByteArray(methodBuilder, resourceFile);
            builder.addMethod(methodBuilder.build());
        }
        return types.entrySet()
                .stream()
                .map(e -> buildJavaFile(e.getKey(),
                        e.getValue()))
                .collect(Collectors.toList());
    }

    private void buildByteArray(MethodSpec.Builder methodBuilder,
                                @Nullable File resourceFile) {
        if (resourceFile != null) {
            try (InputStream in = new FileInputStream(resourceFile)) {
                methodBuilder.addStatement("byte[] b = new byte[" + resourceFile.length() + "]");
                int n = 0;
                for (byte b : ByteStreams.toByteArray(in)) {
                    methodBuilder.addStatement("b[" + n + "] = " + b);
                    n++;
                }
                methodBuilder.addStatement("return b");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            methodBuilder.addStatement("return null");
        }
    }
}
