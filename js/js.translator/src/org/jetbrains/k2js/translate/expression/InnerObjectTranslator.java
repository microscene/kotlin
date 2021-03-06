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

package org.jetbrains.k2js.translate.expression;

import com.google.dart.compiler.backend.js.ast.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.ClassDescriptor;
import org.jetbrains.jet.lang.psi.JetElement;
import org.jetbrains.k2js.translate.context.TranslationContext;

class InnerObjectTranslator extends InnerDeclarationTranslator {
    public InnerObjectTranslator(@NotNull JetElement element, @NotNull ClassDescriptor descriptor, @NotNull TranslationContext context, @NotNull JsFunction fun) {
        super(element, descriptor, context, fun);
    }

    @Override
    protected JsExpression createExpression(@NotNull JsNameRef nameRef, @Nullable JsExpression self) {
        return createInvocation(nameRef, self);
    }

    @Override
    protected JsInvocation createInvocation(@NotNull JsNameRef nameRef, @Nullable JsExpression self) {
        JsInvocation invocation = new JsInvocation(nameRef);
        if (self != null) {
            fun.getParameters().add(new JsParameter(((JsNameRef) self).getName()));
            invocation.getArguments().add(JsLiteral.THIS);
        }
        return invocation;
    }
}
