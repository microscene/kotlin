/*
 * Copyright 2010-2013 JetBrains s.r.o.
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

package org.jetbrains.jet.descriptors.serialization;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.MessageLite;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.NullableFunction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.ConfigurationKind;
import org.jetbrains.jet.JetTestUtils;
import org.jetbrains.jet.cli.jvm.compiler.JetCoreEnvironment;
import org.jetbrains.jet.descriptors.serialization.descriptors.AnnotationDeserializer;
import org.jetbrains.jet.descriptors.serialization.descriptors.DeserializedClassDescriptor;
import org.jetbrains.jet.di.InjectorForJavaDescriptorResolver;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.descriptors.annotations.AnnotationDescriptor;
import org.jetbrains.jet.lang.descriptors.impl.NamespaceDescriptorImpl;
import org.jetbrains.jet.lang.resolve.BindingTraceContext;
import org.jetbrains.jet.lang.resolve.DescriptorUtils;
import org.jetbrains.jet.lang.resolve.java.JavaDescriptorResolver;
import org.jetbrains.jet.lang.resolve.lazy.KotlinTestWithEnvironment;
import org.jetbrains.jet.lang.resolve.lazy.LazyResolveTestUtil;
import org.jetbrains.jet.lang.resolve.lazy.storage.LockBasedStorageManager;
import org.jetbrains.jet.lang.resolve.lazy.storage.MemoizedFunctionToNullable;
import org.jetbrains.jet.lang.resolve.lazy.storage.MemoizedFunctionToNullableImpl;
import org.jetbrains.jet.lang.resolve.name.FqName;
import org.jetbrains.jet.lang.resolve.name.FqNameUnsafe;
import org.jetbrains.jet.lang.resolve.name.Name;
import org.jetbrains.jet.lang.resolve.scopes.JetScope;
import org.jetbrains.jet.lang.resolve.scopes.WritableScope;
import org.jetbrains.jet.lang.types.lang.KotlinBuiltIns;
import org.jetbrains.jet.test.util.NamespaceComparator;

import java.io.*;
import java.util.*;

import static org.jetbrains.jet.descriptors.serialization.descriptors.AnnotationDeserializer.UNSUPPORTED;
import static org.jetbrains.jet.lang.resolve.java.resolver.DeserializedResolverUtils.naiveKotlinFqName;

public abstract class AbstractDescriptorSerializationTest extends KotlinTestWithEnvironment {

    public static final Name TEST_PACKAGE_NAME = Name.identifier("test");

    public static final AnnotationDeserializer DUMMY_ANNOTATION_DESERIALIZER = new AnnotationDeserializer() {
        @NotNull
        @Override
        public List<AnnotationDescriptor> loadClassAnnotations(@NotNull ClassDescriptor descriptor, @NotNull ProtoBuf.Class classProto) {
            // This is a hack for tests: only data annotations are present in test data so far
            AnnotationDescriptor annotationDescriptor = new AnnotationDescriptor();
            annotationDescriptor.setAnnotationType(KotlinBuiltIns.getInstance().getDataClassAnnotation().getDefaultType());
            return Collections.singletonList(annotationDescriptor);
        }

        @NotNull
        @Override
        public List<AnnotationDescriptor> loadCallableAnnotations(
                @NotNull ClassOrNamespaceDescriptor container, @NotNull ProtoBuf.Callable callableProto
        ) {
            throw new UnsupportedOperationException(); // TODO
        }

        @NotNull
        @Override
        public List<AnnotationDescriptor> loadValueParameterAnnotations(@NotNull ProtoBuf.Callable.ValueParameter parameterProto) {
            throw new UnsupportedOperationException(); // TODO
        }
    };

    @Override
    protected JetCoreEnvironment createEnvironment() {
        return createEnvironmentWithMockJdk(ConfigurationKind.JDK_ONLY);
    }

    protected void doTest(String path) throws IOException {
        File ktFile = new File(path);
        ModuleDescriptor moduleDescriptor = LazyResolveTestUtil.resolveEagerly(Collections.singletonList(
                JetTestUtils.createFile(ktFile.getName(), FileUtil.loadFile(ktFile), getProject())
        ), getEnvironment());

        NamespaceDescriptor testNamespace = moduleDescriptor.getNamespace(FqName.topLevel(TEST_PACKAGE_NAME));
        assert testNamespace != null;

        JavaDescriptorResolver javaDescriptorResolver = new InjectorForJavaDescriptorResolver(
                getProject(), new BindingTraceContext(), moduleDescriptor).getJavaDescriptorResolver();

        Collection<DeclarationDescriptor> initial = new ArrayList<DeclarationDescriptor>();
        initial.addAll(testNamespace.getMemberScope().getAllDescriptors());
        initial.addAll(testNamespace.getMemberScope().getObjectDescriptors());

        NamespaceDescriptor deserialized = serializeAndDeserialize(javaDescriptorResolver, initial);

        NamespaceComparator.validateAndCompareNamespaces(testNamespace, deserialized, NamespaceComparator.RECURSIVE, null);
    }

    private static NamespaceDescriptor serializeAndDeserialize(
            JavaDescriptorResolver javaDescriptorResolver,
            Collection<DeclarationDescriptor> initial
    ) throws IOException {
        List<ClassDescriptor> classes = Lists.newArrayList();
        List<CallableMemberDescriptor> callables = Lists.newArrayList();
        for (DeclarationDescriptor descriptor : initial) {
            if (descriptor instanceof ClassDescriptor) {
                ClassDescriptor classDescriptor = (ClassDescriptor) descriptor;
                classes.add(classDescriptor);
            }
            else if (descriptor instanceof CallableMemberDescriptor) {
                CallableMemberDescriptor memberDescriptor = (CallableMemberDescriptor) descriptor;
                callables.add(memberDescriptor);
            }
            else {
                fail("Unsupported descriptor type: " + descriptor);
            }
        }
        return getDeserializedDescriptors(javaDescriptorResolver, classes, callables);
    }

    private static NamespaceDescriptor getDeserializedDescriptors(
            JavaDescriptorResolver javaDescriptorResolver,
            List<ClassDescriptor> classes,
            List<CallableMemberDescriptor> callables
    ) throws IOException {
        ByteArrayOutputStream serializedCallables = new ByteArrayOutputStream();
        Map<ClassDescriptor, byte[]> serializedClasses = Maps.newHashMap();
        serialize(classes, callables, serializedClasses, serializedCallables);

        final Map<String, ClassMetadata> classMetadata = Maps.newHashMap();

        for (Map.Entry<ClassDescriptor, byte[]> entry : serializedClasses.entrySet()) {
            ClassDescriptor classDescriptor = entry.getKey();
            byte[] bytes = entry.getValue();

            ByteArrayInputStream in = new ByteArrayInputStream(bytes);

            ProtoBuf.SimpleNameTable simpleNames = ProtoBuf.SimpleNameTable.parseDelimitedFrom(in);
            ProtoBuf.QualifiedNameTable qualifiedNames = ProtoBuf.QualifiedNameTable.parseDelimitedFrom(in);
            ProtoBuf.Class proto = ProtoBuf.Class.parseFrom(in);

            classMetadata.put(naiveKotlinFqName(classDescriptor).asString(), new ClassMetadata(simpleNames, qualifiedNames, proto));
        }

        NamespaceDescriptorImpl namespace = JetTestUtils.createTestNamespace(TEST_PACKAGE_NAME);
        DescriptorFinder javaDescriptorFinder = new JavaDescriptorFinder(javaDescriptorResolver);

        DescriptorFinderImpl descriptorFinder = new DescriptorFinderImpl(
                javaDescriptorFinder, namespace,
                new NullableFunction<String, ClassMetadata>() {
                    @Nullable
                    @Override
                    public ClassMetadata fun(String fqName) {
                        return classMetadata.get(fqName);
                    }
                }
        );

        for (ClassDescriptor classDescriptor : classes) {
            ClassId classId = new ClassId(DescriptorUtils.getFQName(classDescriptor.getContainingDeclaration()).toSafe(),
                                          FqNameUnsafe.topLevel(classDescriptor.getName()));
            ClassDescriptor descriptor = descriptorFinder.findClass(classId);
            assert descriptor != null : "Class not loaded: " + classId;
            if (descriptor.getKind().isObject()) {
                namespace.getMemberScope().addObjectDescriptor(descriptor);
            }
            else {
                namespace.getMemberScope().addClassifierDescriptor(descriptor);
            }
        }

        ByteArrayInputStream in = new ByteArrayInputStream(serializedCallables.toByteArray());
        ProtoBuf.SimpleNameTable simpleNames = ProtoBuf.SimpleNameTable.parseDelimitedFrom(in);
        ProtoBuf.QualifiedNameTable qualifiedNames = ProtoBuf.QualifiedNameTable.parseDelimitedFrom(in);

        List<ProtoBuf.Callable> callableProtos = Lists.newArrayList();
        for (int i = 0; i < callables.size(); i++) {
            ProtoBuf.Callable proto = ProtoBuf.Callable.parseDelimitedFrom(in);
            callableProtos.add(proto);
        }

        DescriptorDeserializer deserializer =
                DescriptorDeserializer.create(new LockBasedStorageManager(), namespace, new NameResolver(simpleNames, qualifiedNames),
                                              descriptorFinder, UNSUPPORTED);
        for (ProtoBuf.Callable proto : callableProtos) {
            CallableMemberDescriptor descriptor = deserializer.loadCallable(proto);
            if (descriptor instanceof FunctionDescriptor) {
                namespace.getMemberScope().addFunctionDescriptor((FunctionDescriptor) descriptor);
            }
            else if (descriptor instanceof PropertyDescriptor) {
                namespace.getMemberScope().addPropertyDescriptor((PropertyDescriptor) descriptor);
            }
            else {
                throw new IllegalStateException("Unknown descriptor type: " + descriptor);
            }
        }
        namespace.getMemberScope().changeLockLevel(WritableScope.LockLevel.READING);

        return namespace;
    }

    public static void serialize(
            List<ClassDescriptor> classes, List<CallableMemberDescriptor> callables,
            Map<ClassDescriptor, byte[]> serializedClasses, OutputStream serializedCallables
    ) throws IOException {

        serializeClasses(classes, serializedClasses);

        DescriptorSerializer descriptorSerializer = new DescriptorSerializer();
        List<MessageLite> messages = Lists.newArrayList();
        for (CallableMemberDescriptor callable : callables) {
            messages.add(descriptorSerializer.callableProto(callable).build());
        }

        NameSerializationUtil.serializeNameTable(serializedCallables, descriptorSerializer.getNameTable());

        for (MessageLite message : messages) {
            message.writeDelimitedTo(serializedCallables);
        }
    }

    private static void serializeClasses(Collection<ClassDescriptor> classes, Map<ClassDescriptor, byte[]> serializedClasses)
            throws IOException {
        for (ClassDescriptor classDescriptor : classes) {
            DescriptorSerializer descriptorSerializer = new DescriptorSerializer();

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ProtoBuf.Class classProto = descriptorSerializer.classProto(classDescriptor).build();

            NameSerializationUtil.serializeNameTable(bytes, descriptorSerializer.getNameTable());
            classProto.writeTo(bytes);

            serializedClasses.put(classDescriptor, bytes.toByteArray());

            //noinspection unchecked
            serializeClasses((Collection) classDescriptor.getUnsubstitutedInnerClassesScope().getAllDescriptors(), serializedClasses);
            //noinspection unchecked
            serializeClasses((Collection) classDescriptor.getUnsubstitutedInnerClassesScope().getObjectDescriptors(), serializedClasses);

            ClassDescriptor classObjectDescriptor = classDescriptor.getClassObjectDescriptor();
            if (classObjectDescriptor != null) {
                serializeClasses(Collections.singletonList(classObjectDescriptor), serializedClasses);
            }
        }
    }

    private static class ClassMetadata {
        private final ProtoBuf.SimpleNameTable simpleNames;
        private final ProtoBuf.QualifiedNameTable qualifiedNames;
        private final ProtoBuf.Class classProto;

        private ClassMetadata(
                ProtoBuf.SimpleNameTable simpleNames,
                ProtoBuf.QualifiedNameTable qualifiedNames,
                ProtoBuf.Class classProto
        ) {
            this.simpleNames = simpleNames;
            this.qualifiedNames = qualifiedNames;
            this.classProto = classProto;
        }
    }

    private static class DescriptorFinderImpl implements DescriptorFinder {
        private final DescriptorFinder parentResolver;

        private final DeclarationDescriptor parentForClasses;
        private final NullableFunction<String, ClassMetadata> classMetadata;

        private final MemoizedFunctionToNullable<ClassId, ClassDescriptor> classes;

        public DescriptorFinderImpl(
                @NotNull DescriptorFinder parentResolver,
                @NotNull DeclarationDescriptor parentForClasses,
                @NotNull NullableFunction<String, ClassMetadata> classMetadata
        ) {
            this.parentResolver = parentResolver;

            this.parentForClasses = parentForClasses;

            this.classMetadata = classMetadata;

            this.classes = new MemoizedFunctionToNullableImpl<ClassId, ClassDescriptor>() {
                @Nullable
                @Override
                protected ClassDescriptor doCompute(@NotNull ClassId classId) {
                    DeclarationDescriptor containingDeclaration =
                            classId.isTopLevelClass() ? DescriptorFinderImpl.this.parentForClasses : findClass(classId.getOuterClassId());
                    assert containingDeclaration != null : "Containing declaration not found: " + classId.getOuterClassId();
                    return resolveClass(containingDeclaration, classId);
                }
            };
        }

        @Nullable
        private ClassDescriptor resolveClass(
                @NotNull DeclarationDescriptor containingDeclaration,
                @NotNull ClassId classId
        ) {
            FqNameUnsafe fqName = classId.asSingleFqName();

            ClassMetadata classMetadata = this.classMetadata.fun(fqName.asString());
            if (classMetadata == null) {
                return parentResolver.findClass(classId);
            }

            NameResolver nameResolver = new NameResolver(classMetadata.simpleNames, classMetadata.qualifiedNames);
            return new DeserializedClassDescriptor(classId, new LockBasedStorageManager(), containingDeclaration, nameResolver,
                                                   DUMMY_ANNOTATION_DESERIALIZER, this,
                                                   classMetadata.classProto, null);
        }

        @Nullable
        @Override
        public ClassDescriptor findClass(@NotNull ClassId classId) {
            return classes.fun(classId);
        }
    }

    private static class JavaDescriptorFinder implements DescriptorFinder {
        private final JavaDescriptorResolver javaDescriptorResolver;

        public JavaDescriptorFinder(JavaDescriptorResolver javaDescriptorResolver) {
            this.javaDescriptorResolver = javaDescriptorResolver;
        }

        @Nullable
        @Override
        public ClassDescriptor findClass(@NotNull ClassId classId) {
            FqNameUnsafe fqNameUnsafe = classId.asSingleFqName();
            assert fqNameUnsafe.isSafe() : "Unsafe fqName made it to Java resolve: " + fqNameUnsafe;
            ClassDescriptor javaClassDescriptor = javaDescriptorResolver.resolveClass(fqNameUnsafe.toSafe());
            if (javaClassDescriptor != null) {
                return javaClassDescriptor;
            }
            NamespaceDescriptor packageDescriptor = getNamespace(classId.getPackageFqName());
            if (packageDescriptor == null) {
                throw new IllegalStateException("Java package not found: " + classId.getPackageFqName() + " for " + classId);
            }

            JetScope scope = packageDescriptor.getMemberScope();
            for (Iterator<Name> iterator = classId.getRelativeClassName().pathSegments().iterator(); iterator.hasNext(); ) {
                Name name = iterator.next();
                ClassifierDescriptor classifier = scope.getClassifier(name);
                if (classifier == null) {
                    throw new IllegalStateException("Class not found: " + classId);
                }
                ClassDescriptor classDescriptor = (ClassDescriptor) classifier;
                if (!iterator.hasNext()) {
                    return classDescriptor;
                }
                scope = classDescriptor.getUnsubstitutedInnerClassesScope();
            }

            throw new IllegalStateException("Class not found: " + classId);
        }

        @Nullable
        private NamespaceDescriptor getNamespace(@NotNull FqName fqName) {
            return javaDescriptorResolver.resolveNamespace(fqName);
        }
    }
}
