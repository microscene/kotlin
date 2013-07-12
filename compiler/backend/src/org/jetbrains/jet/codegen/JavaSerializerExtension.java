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

package org.jetbrains.jet.codegen;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.asm4.Type;
import org.jetbrains.asm4.commons.Method;
import org.jetbrains.jet.codegen.state.JetTypeMapper;
import org.jetbrains.jet.descriptors.serialization.JavaProtoBufUtil;
import org.jetbrains.jet.descriptors.serialization.NameTable;
import org.jetbrains.jet.descriptors.serialization.ProtoBuf;
import org.jetbrains.jet.descriptors.serialization.SerializerExtension;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.resolve.java.JvmAbi;

public class JavaSerializerExtension extends SerializerExtension {
    private final JetTypeMapper typeMapper;

    public JavaSerializerExtension(@NotNull JetTypeMapper typeMapper) {
        this.typeMapper = typeMapper;
    }

    // TODO: mapSignature should be done upon generation of the member instead, because we don't know enough at this point to map correctly
    @Override
    public void serializeCallable(
            @NotNull CallableMemberDescriptor callable,
            @NotNull ProtoBuf.Callable.Builder proto,
            @NotNull NameTable nameTable
    ) {
        if (callable instanceof FunctionDescriptor) {
            Method method = typeMapper.mapSignature((FunctionDescriptor) callable).getAsmMethod();
            JavaProtoBufUtil.saveMethodSignature(proto, method, nameTable);
        }
        else if (callable instanceof PropertyDescriptor) {
            PropertyDescriptor property = (PropertyDescriptor) callable;
            Type type = typeMapper.mapType(property.getType());
            PropertyGetterDescriptor getter = property.getGetter();
            PropertySetterDescriptor setter = property.getSetter();
            Method getterMethod = getter == null ? null : typeMapper.mapGetterSignature(property, OwnerKind.IMPLEMENTATION).getAsmMethod();
            Method setterMethod = setter == null ? null : typeMapper.mapSetterSignature(property, OwnerKind.IMPLEMENTATION).getAsmMethod();

            // This is very wrong, see above todo
            String fieldName;
            String syntheticMethodName;
            if ((getter == null || getter.isDefault()) && (setter == null || setter.isDefault())) {
                fieldName = property.getName().asString();
                syntheticMethodName = null;
            }
            else {
                fieldName = null;
                syntheticMethodName = JvmAbi.getSyntheticMethodNameForAnnotatedProperty(property.getName());
            }

            JavaProtoBufUtil.savePropertySignature(proto, type, fieldName, syntheticMethodName, getterMethod, setterMethod, nameTable);
        }
    }
}
