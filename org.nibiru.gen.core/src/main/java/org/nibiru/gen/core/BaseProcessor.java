package org.nibiru.gen.core;


import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;

import javax.annotation.Nullable;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.util.Deque;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;

public abstract class BaseProcessor extends AbstractProcessor {
    private static final String[] FILE_SEARCH_PREFIXES = {
            "",
            "src/main/java/",
            "src/main/resources/",
    };
    private final Class<? extends Annotation> annotationClass;

    protected BaseProcessor(Class<? extends Annotation> annotationClass) {
        this.annotationClass = checkNotNull(annotationClass);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations,
                           RoundEnvironment roundEnv) {
        try {
            for (JavaFile javaFile : generate(roundEnv.getElementsAnnotatedWith(annotationClass))) {

                JavaFileObject jfo = processingEnv.getFiler()
                        .createSourceFile(javaFile.packageName
                                + "."
                                + javaFile.typeSpec.name);

                try (Writer file = jfo.openWriter()) {
                    javaFile.writeTo(file);
                    file.flush();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    @Nullable
    protected File findFile(String path) {
        try {
            File base = new File(processingEnv
                    .getFiler()
                    .getResource(StandardLocation.CLASS_OUTPUT, "", path)
                    .toUri());

            // Go up the same number of levesl as the specief path
            for (String dummy : Splitter.on('/').split(path)) {
                base = base.getParentFile();
            }

            File current = findFileWithPrefixes(base, path);

            while (current == null && base.getParentFile() != null) {
                base = base.getParentFile();
                current = findFileWithPrefixes(base, path);
            }

            return current;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    private File findFileWithPrefixes(File base, String path) {
        for (String prefix : FILE_SEARCH_PREFIXES) {
            File file = new File(base, prefix + path);
            if (file.exists()) {
                return file;
            }

        }
        return null;

    }

    protected void log(String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.MANDATORY_WARNING,
                "MESSAGE: " + message);
    }

    protected abstract Iterable<JavaFile> generate(Set<? extends Element> elements);

    protected static boolean isString(TypeMirror type) {
        return type instanceof DeclaredType
                && String.class
                .getName()
                .equals(type.toString());
    }

    protected static MethodSpec.Builder buildMethod(ExecutableElement executableElement) {
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(executableElement
                .getSimpleName().toString())
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(ClassName.get(executableElement.getReturnType()));
        for (VariableElement variableElement : executableElement.getParameters()) {
            methodBuilder.addParameter(ClassName.get(variableElement.asType()),
                    variableElement.getSimpleName().toString());
        }

        return methodBuilder;
    }

    protected static JavaFile buildJavaFile(TypeElement element,
                                            TypeSpec.Builder typeBuilder) {
        return JavaFile.builder(element.getEnclosingElement()
                        .toString(),
                typeBuilder.build())
                .build();
    }

    protected static String resolveRelativePaths(String path) {

        Deque<String> pathStack = Lists.newLinkedList();
        for (String segment : Splitter.on('/').split(path)) {
            if (segment.equals(".")) {
                // do nothing
            } else if (segment.equals("..")) {
                pathStack.removeLast();
            } else {
                pathStack.addLast(segment);
            }
        }

        return Joiner.on('/').join(pathStack);
    }
}
