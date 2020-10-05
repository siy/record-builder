/**
 * Copyright 2019 Jordan Zimmerman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.soabase.recordbuilder.processor;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import io.soabase.recordbuilder.core.IgnoreDefaultMethod;
import io.soabase.recordbuilder.core.RecordBuilder;
import io.soabase.recordbuilder.core.RecordBuilderMetaData;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.soabase.recordbuilder.processor.ElementUtils.getBuilderName;
import static io.soabase.recordbuilder.processor.RecordBuilderProcessor.generatedRecordInterfaceAnnotation;

class InternalRecordInterfaceProcessor {
    private final ProcessingEnvironment processingEnv;
    private final String packageName;
    private final TypeSpec recordType;
    private final List<ExecutableElement> recordComponents;
    private final TypeElement iface;
    private final ClassType recordClassType;

    private static final String FAKE_METHOD_NAME = "__FAKE__";

    InternalRecordInterfaceProcessor(ProcessingEnvironment processingEnv, TypeElement iface, boolean addRecordBuilder, RecordBuilderMetaData metaData, Optional<String> packageNameOpt) {
        this.processingEnv = processingEnv;
        packageName = packageNameOpt.orElseGet(() -> ElementUtils.getPackageName(iface));
        recordComponents = getRecordComponents(iface);
        this.iface = iface;

        ClassType ifaceClassType = ElementUtils.getClassType(iface, iface.getTypeParameters());
        recordClassType = ElementUtils.getClassType(packageName, getBuilderName(iface, metaData, ifaceClassType, metaData.interfaceSuffix()), iface.getTypeParameters());
        List<TypeVariableName> typeVariables = iface.getTypeParameters().stream().map(TypeVariableName::get).collect(Collectors.toList());

        MethodSpec methodSpec = generateArgumentList();

        TypeSpec.Builder builder = TypeSpec.classBuilder(recordClassType.name())
                .addSuperinterface(iface.asType())
                .addMethod(methodSpec)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(generatedRecordInterfaceAnnotation)
                .addTypeVariables(typeVariables);

        if (addRecordBuilder) {
            ClassType builderClassType = ElementUtils.getClassType(packageName, getBuilderName(iface, metaData, recordClassType, metaData.suffix()) + "." + metaData.withClassName(), iface.getTypeParameters());
            builder.addAnnotation(RecordBuilder.class);
            builder.addSuperinterface(builderClassType.typeName());
        }

        recordType = builder.build();
    }

    boolean isValid()
    {
        return !recordComponents.isEmpty();
    }

    TypeSpec recordType() {
        return recordType;
    }

    String packageName() {
        return packageName;
    }

    ClassType recordClassType() {
        return recordClassType;
    }

    String toRecord(String classSource)
    {
        // javapoet does yet support records - so a class was created and we can reshape it
        // The class will look something like this:
        /*
            // Auto generated by io.soabase.recordbuilder.core.RecordBuilder: https://github.com/Randgalt/record-builder
            package io.soabase.recordbuilder.test;

            import io.soabase.recordbuilder.core.RecordBuilder;
            import javax.annotation.processing.Generated;

            @Generated("io.soabase.recordbuilder.core.RecordInterface")
            @RecordBuilder
            public class MyRecord implements MyInterface {
                void __FAKE__(String name, int age) {
                }
            }
        */
        Pattern pattern = Pattern.compile("(.*)(implements.*)(\\{)(.*" + FAKE_METHOD_NAME + ")(\\(.*\\))(.*)", Pattern.MULTILINE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(classSource);
        if (!matcher.find() || matcher.groupCount() != 6) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Internal error generating record. Group count: " + matcher.groupCount(), iface);
        }

        String declaration = matcher.group(1).trim().replace("class", "record");
        String implementsSection = matcher.group(2).trim();
        String argumentList = matcher.group(5).trim();
        return declaration + argumentList + " " + implementsSection + " {}";
    }

    private MethodSpec generateArgumentList()
    {
        MethodSpec.Builder builder = MethodSpec.methodBuilder(FAKE_METHOD_NAME);
        recordComponents.forEach(element -> {
            ParameterSpec parameterSpec = ParameterSpec.builder(ClassName.get(element.getReturnType()), element.getSimpleName().toString()).build();
            builder.addTypeVariables(element.getTypeParameters().stream().map(TypeVariableName::get).collect(Collectors.toList()));
            builder.addParameter(parameterSpec);
        });
        return builder.build();
    }

    private List<ExecutableElement> getRecordComponents(TypeElement iface) {
        List<ExecutableElement> components = new ArrayList<>();
        try {
            getRecordComponents(iface, components, new HashSet<>(), new HashSet<>());
            if (components.isEmpty()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Annotated interface has no component methods", iface);
            }
        } catch (IllegalInterface e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage(), iface);
            components = Collections.emptyList();
        }
        return components;
    }

    private static class IllegalInterface extends RuntimeException
    {
        public IllegalInterface(String message) {
            super(message);
        }
    }

    private void getRecordComponents(TypeElement iface, Collection<? super ExecutableElement> components, Set<String> visitedSet, Set<String> usedNames) {
        if (!visitedSet.add(iface.getQualifiedName().toString())) {
            return;
        }

        iface.getEnclosedElements().stream()
                .filter(element -> (element.getKind() == ElementKind.METHOD) && !(element.getModifiers().contains(Modifier.STATIC)))
                .map(element -> ((ExecutableElement) element))
                .filter(element -> {
                    if (element.isDefault()) {
                        return element.getAnnotation(IgnoreDefaultMethod.class) == null;
                    }
                    return true;
                })
                .peek(element -> {
                    if (!element.getParameters().isEmpty() || element.getReturnType().getKind() == TypeKind.VOID) {
                        throw new IllegalInterface(String.format("Non-static, non-default methods must take no arguments and must return a value. Bad method: %s.%s()", iface.getSimpleName(), element.getSimpleName()));
                    }
                    if (!element.getTypeParameters().isEmpty()) {
                        throw new IllegalInterface(String.format("Interface methods cannot have type parameters. Bad method: %s.%s()", iface.getSimpleName(), element.getSimpleName()));
                    }
                })
                .filter(element -> usedNames.add(element.getSimpleName().toString()))
                .collect(Collectors.toCollection(() -> components));
        iface.getInterfaces().forEach(parentIface -> {
            TypeElement parentIfaceElement = (TypeElement) processingEnv.getTypeUtils().asElement(parentIface);
            getRecordComponents(parentIfaceElement, components, visitedSet, usedNames);
        });
    }
}
