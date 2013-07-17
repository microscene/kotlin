package org.jetbrains.jet.lang.types.lang;

import com.intellij.openapi.util.Computable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.descriptors.serialization.*;
import org.jetbrains.jet.descriptors.serialization.descriptors.AnnotationDeserializer;
import org.jetbrains.jet.descriptors.serialization.descriptors.DeserializedPackageMemberScope;
import org.jetbrains.jet.lang.descriptors.ClassDescriptor;
import org.jetbrains.jet.lang.descriptors.NamespaceDescriptor;
import org.jetbrains.jet.lang.descriptors.ReceiverParameterDescriptor;
import org.jetbrains.jet.lang.descriptors.annotations.AnnotationDescriptor;
import org.jetbrains.jet.lang.descriptors.impl.AbstractNamespaceDescriptorImpl;
import org.jetbrains.jet.lang.resolve.lazy.storage.NotNullLazyValue;
import org.jetbrains.jet.lang.resolve.lazy.storage.StorageManager;
import org.jetbrains.jet.lang.resolve.name.FqName;
import org.jetbrains.jet.lang.resolve.name.Name;
import org.jetbrains.jet.lang.resolve.scopes.JetScope;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

class BuiltinsNamespaceDescriptorImpl extends AbstractNamespaceDescriptorImpl {

    private final DeserializedPackageMemberScope members;
    private final NameResolver nameResolver;

    public BuiltinsNamespaceDescriptorImpl(@NotNull final StorageManager storageManager, @NotNull NamespaceDescriptor containingDeclaration) {
        super(containingDeclaration, Collections.<AnnotationDescriptor>emptyList(), KotlinBuiltIns.BUILT_INS_PACKAGE_NAME);

        try {
            nameResolver = NameSerializationUtil.deserializeNameResolver(getStream(BuiltInsSerializationUtil.getNameTableFilePath(this)));

            final NotNullLazyValue<Collection<Name>> classNames = storageManager.createLazyValue(new Computable<Collection<Name>>() {
                @Override
                @NotNull
                public Collection<Name> compute() {
                    InputStream in = getStream(BuiltInsSerializationUtil.getClassNamesFilePath(BuiltinsNamespaceDescriptorImpl.this));

                    try {
                        DataInputStream data = new DataInputStream(in);
                        try {
                            int size = data.readInt();
                            List<Name> result = new ArrayList<Name>(size);
                            for (int i = 0; i < size; i++) {
                                result.add(nameResolver.getName(data.readInt()));
                            }
                            return result;
                        }
                        finally {
                            data.close();
                        }
                    }
                    catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            });

            DescriptorFinder descriptorFinder = new AbstractDescriptorFinder(storageManager, AnnotationDeserializer.UNSUPPORTED) {
                @Nullable
                @Override
                protected ClassData getClassData(@NotNull ClassId classId) {
                    InputStream stream = getStreamNullable(BuiltInsSerializationUtil.getClassMetadataPath(classId));
                    if (stream == null) {
                        return null;
                    }

                    try {
                        ProtoBuf.Class classProto = ProtoBuf.Class.parseFrom(stream);

                        Name expectedShortName = classId.getRelativeClassName().shortName();
                        Name actualShortName = nameResolver.getName(classProto.getName());
                        if (!actualShortName.isSpecial() && !actualShortName.equals(expectedShortName)) {
                            // Workaround for case-insensitive file systems,
                            // otherwise we'd find "Collection" for "collection" etc
                            return null;
                        }

                        return new ClassData(nameResolver, classProto);
                    }
                    catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }

                @Nullable
                @Override
                public NamespaceDescriptor findPackage(@NotNull FqName fqName) {
                    return fqName.equals(KotlinBuiltIns.BUILT_INS_PACKAGE_FQ_NAME) ? BuiltinsNamespaceDescriptorImpl.this : null;
                }

                @NotNull
                @Override
                public Collection<Name> getClassNames(@NotNull FqName packageName) {
                    return packageName.equals(KotlinBuiltIns.BUILT_INS_PACKAGE_FQ_NAME)
                           ? classNames.compute()
                           : Collections.<Name>emptyList();
                }

                @Override
                protected void classDescriptorCreated(@NotNull ClassDescriptor classDescriptor) {
                    // Do nothing
                }
            };

            members = new DeserializedPackageMemberScope(
                    storageManager,
                    this,
                    DescriptorDeserializer.create(storageManager, this, nameResolver, descriptorFinder, AnnotationDeserializer.UNSUPPORTED),
                    loadCallables(), descriptorFinder
            );
        }
        catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<ProtoBuf.Callable> loadCallables() throws IOException {
        String packageFilePath = BuiltInsSerializationUtil.getPackageFilePath(this);
        InputStream stream = getStream(packageFilePath);

        List<ProtoBuf.Callable> callables = new ArrayList<ProtoBuf.Callable>();
        while (true) {
            ProtoBuf.Callable callable = ProtoBuf.Callable.parseDelimitedFrom(stream);
            if (callable == null) {
                break;
            }
            callables.add(callable);
        }
        return callables;
    }

    @NotNull
    @Override
    public JetScope getMemberScope() {
        return members;
    }

    @NotNull
    @Override
    public FqName getFqName() {
        return KotlinBuiltIns.BUILT_INS_PACKAGE_FQ_NAME;
    }

    @NotNull
    private static InputStream getStream(@NotNull String path) {
        InputStream stream = getStreamNullable(path);
        if (stream == null) {
            throw new IllegalStateException("Resource not found in classpath: " + path);
        }
        return stream;
    }

    @Nullable
    private static InputStream getStreamNullable(@NotNull String path) {
        return KotlinBuiltIns.class.getClassLoader().getResourceAsStream(path);
    }
}
