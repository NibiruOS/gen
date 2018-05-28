package org.nibiru.gen.processor;


import com.google.common.base.Splitter;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;

import javax.annotation.Nullable;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.lang.annotation.Annotation;
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
        for (TypeElement element : ElementFilter.typesIn(roundEnv.getElementsAnnotatedWith(annotationClass))) {
            try {
                for (TypeSpec typeSpec : generate(element)) {

                    JavaFileObject jfo = processingEnv.getFiler().createSourceFile(
                            element.getEnclosingElement().toString() + "." + typeSpec.name);

                    try (Writer file = jfo.openWriter()) {
                        JavaFile.builder(element.getEnclosingElement().toString(), typeSpec)
                                .build()
                                .writeTo(file);
                        file.flush();
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
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

    protected abstract Iterable<TypeSpec> generate(TypeElement element);
}
