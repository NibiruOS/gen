package org.nibiru.gen.processor.i18n;

import com.google.common.collect.Lists;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import org.nibiru.gen.api.i18n.Messages;
import org.nibiru.gen.processor.BaseProcessor;

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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@SupportedAnnotationTypes("org.nibiru.gen.api.i18n.Messages")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class MessagesProcessor
        extends BaseProcessor {
    private static String PROPERTIES_EXTENSION = ".properties";
    private static Pattern ARG_PATTERN = Pattern.compile("\\{(\\d+)\\}");

    public MessagesProcessor() {
        super(Messages.class);
    }

    @Override
    protected Iterable<JavaFile> generate(Set<? extends Element> elements) {
        List<JavaFile> types = Lists.newArrayList();
        for (TypeElement element : ElementFilter.typesIn(elements)) {
            File baseFile = findFile(
                    element.getQualifiedName()
                            .toString()
                            .replaceAll("\\.", "/")
                            + PROPERTIES_EXTENSION);

            if (baseFile != null) {
                File directory = baseFile.getParentFile();
                if (directory != null) {
                    types.addAll(Arrays.stream(directory.listFiles())
                            .filter((File f) -> f.getName().startsWith(element.getSimpleName().toString())
                                    && f.getName().endsWith(PROPERTIES_EXTENSION))
                            .map((f) -> build(element, f))
                            .collect(Collectors.toList()));
                }
            }
        }
        return types;
    }

    private JavaFile build(TypeElement element,
                           File file) {

        try {
            ResourceBundle resourceBundle = new PropertyResourceBundle(new FileInputStream(file));

            String code = file.getName()
                    .substring(element.getSimpleName().length(),
                            file.getName().length() - PROPERTIES_EXTENSION.length());

            String typeName = element.getSimpleName()
                    + "Impl"
                    + code;

            TypeSpec.Builder typeBuilder = TypeSpec.classBuilder(typeName)
                    .addModifiers(Modifier.PUBLIC)
                    .addSuperinterface(ClassName.get(element));

            for (ExecutableElement executableElement : ElementFilter
                    .methodsIn(element.getEnclosedElements())) {
                typeBuilder.addMethod(buildMethod(executableElement)
                        .addStatement("return $L",
                                buildStringExpression(resourceBundle.getString(executableElement
                                                .getSimpleName().toString()),
                                        executableElement))
                        .build());
            }

            return buildJavaFile(element, typeBuilder);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String buildStringExpression(String expression,
                                         ExecutableElement executableElement) {
        Matcher matcher = ARG_PATTERN.matcher(expression);

        StringBuilder sb = new StringBuilder();
        int start = 0;
        sb.append('"');
        while (matcher.find()) {
            sb.append(expression.substring(start, matcher.start()));

            String argStr = matcher.group(1);
            boolean isValid;
            int arg = 0;
            try {
                arg = Integer.parseInt(argStr);
                isValid = arg >= 0
                        && arg < executableElement.getParameters().size();
            } catch (NumberFormatException e) {
                isValid = false;
            }

            if (isValid) {
                sb.append("\"+");
                sb.append(executableElement.getParameters()
                        .get(arg)
                        .getSimpleName()
                        .toString());
                sb.append("+\"");
            } else {
                sb.append("???");
                sb.append(argStr);
                sb.append("???");
            }

            start = matcher.end();
        }
        sb.append(expression.substring(start));
        sb.append('"');

        return sb.toString();
    }
}
