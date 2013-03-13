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

package org.jetbrains.jet.lang.resolve.calls.context;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.CallableDescriptor;
import org.jetbrains.jet.lang.descriptors.FunctionDescriptor;
import org.jetbrains.jet.lang.descriptors.VariableDescriptor;
import org.jetbrains.jet.lang.psi.CallKey;
import org.jetbrains.jet.lang.psi.JetExpression;
import org.jetbrains.jet.lang.resolve.BindingTrace;
import org.jetbrains.jet.lang.resolve.BindingTraceContext;
import org.jetbrains.jet.lang.resolve.DelegatingBindingTrace;
import org.jetbrains.jet.lang.resolve.calls.results.OverloadResolutionResults;
import org.jetbrains.jet.lang.resolve.calls.results.OverloadResolutionResultsImpl;
import org.jetbrains.jet.util.slicedmap.Slices;
import org.jetbrains.jet.util.slicedmap.WritableSlice;

public class ResolutionResultsCache {

    public static class MemberType<D extends CallableDescriptor> {}
    private static class FunctionMemberType extends MemberType<FunctionDescriptor> {}
    private static class PropertyMemberType extends MemberType<VariableDescriptor> {}
    public static final FunctionMemberType FUNCTION_MEMBER_TYPE = new FunctionMemberType();
    public static final PropertyMemberType PROPERTY_MEMBER_TYPE = new PropertyMemberType();


    private static final WritableSlice<CallKey, OverloadResolutionResultsImpl<FunctionDescriptor>> RESOLUTION_RESULTS_FOR_FUNCTION = Slices.createSimpleSlice();
    private static final WritableSlice<CallKey, OverloadResolutionResultsImpl<VariableDescriptor>> RESOLUTION_RESULTS_FOR_PROPERTY = Slices.createSimpleSlice();
    private static final WritableSlice<JetExpression, DelegatingBindingTrace> TRACE_DELTAS_CACHE = Slices.createSimpleSlice();
    private static final WritableSlice<JetExpression, CallCandidateResolutionContext<FunctionDescriptor>> DEFERRED_COMPUTATION_FOR_CALL = Slices.createSimpleSlice();

    private final BindingTrace trace = new BindingTraceContext();

    @NotNull
    private static <D extends CallableDescriptor> WritableSlice<CallKey, OverloadResolutionResultsImpl<D>> getSliceByMemberType(@NotNull MemberType<D> memberType) {
        return (WritableSlice<CallKey, OverloadResolutionResultsImpl<D>>)
                (memberType == FUNCTION_MEMBER_TYPE ? RESOLUTION_RESULTS_FOR_FUNCTION : RESOLUTION_RESULTS_FOR_PROPERTY);
    }

    public <D extends CallableDescriptor> void recordResolutionResults(@NotNull CallKey callKey, @NotNull MemberType<D> memberType, @NotNull OverloadResolutionResultsImpl<D> results) {
        trace.record(getSliceByMemberType(memberType), callKey, results);
    }

    @Nullable
    public <D extends CallableDescriptor> OverloadResolutionResultsImpl<D> getResolutionResults(@NotNull CallKey callKey, @NotNull MemberType<D> memberType) {
        return trace.get(getSliceByMemberType(memberType), callKey);
    }

    public void recordResolutionTrace(@NotNull JetExpression expression, @NotNull DelegatingBindingTrace delegatingTrace) {
        trace.record(TRACE_DELTAS_CACHE, expression, delegatingTrace);
    }

    @Nullable
    public DelegatingBindingTrace getResolutionTrace(@NotNull JetExpression expression) {
        return trace.get(TRACE_DELTAS_CACHE, expression);
    }

    public void recordDeferredComputationForCall(@NotNull JetExpression expression, @NotNull CallCandidateResolutionContext<FunctionDescriptor> deferredComputation) {
        trace.record(DEFERRED_COMPUTATION_FOR_CALL, expression, deferredComputation);
    }

    @Nullable
    public CallCandidateResolutionContext<FunctionDescriptor> getDeferredComputation(@NotNull JetExpression expression) {
        return trace.get(DEFERRED_COMPUTATION_FOR_CALL, expression);
    }

    @NotNull
    public static ResolutionResultsCache create() {
        return new ResolutionResultsCache();
    }
}