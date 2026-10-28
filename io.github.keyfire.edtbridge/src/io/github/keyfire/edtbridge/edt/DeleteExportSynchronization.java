/*
 * edt-bridge - a 1C:EDT bridge that exposes the live EDT model over MCP.
 * Copyright 2026 edt-bridge contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.keyfire.edtbridge.edt;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import org.eclipse.core.resources.IProject;

/** Wait for the actual native export contexts, not merely forceExport's request acceptance. */
final class DeleteExportSynchronization {
    static final long TIMEOUT_MILLIS = 10_000;
    private static final List<String> EXPORT_STAGES = List.of("EXP_O", "EXP_B");

    public static final class Result {
        public boolean completed;
        public boolean timedOut;
        public long waitMillis;
        public long timeoutMillis;
        public long maxExpectedWaitMillis;
        public Map<String, List<String>> requestedContexts = new LinkedHashMap<>();
        public String coverage = "Native EXP_O object and EXP_B blob export contexts for the listed target-project "
                + "FQNs only. Completion means those native contexts have finished or retired; it does not prove "
                + "a successful global disk export, cover other projects, or settle unrelated derived data. "
                + "The SDK may use the timeout once to drain queued contexts and once to wait for computation.";
        public String message;
    }

    private DeleteExportSynchronization() {}

    static Result await(IProject project, List<String> fqns) throws InterruptedException {
        try {
            IDerivedDataManagerProvider provider = FormWriteGateway.coreService(IDerivedDataManagerProvider.class);
            return await(provider == null ? null : provider.get(project), fqns, TIMEOUT_MILLIS);
        } catch (RuntimeException unavailable) {
            Result result = await((IDerivedDataManager) null, fqns, TIMEOUT_MILLIS);
            result.message += ": " + GatewaySupport.describeCause(unavailable);
            return result;
        }
    }

    static Result await(IDerivedDataManager manager, List<String> fqns, long timeoutMillis)
            throws InterruptedException {
        if (timeoutMillis <= 0 || timeoutMillis > TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("export wait timeout must be between 1 and 10000 milliseconds");
        }
        Result result = new Result();
        result.timeoutMillis = timeoutMillis;
        result.maxExpectedWaitMillis = timeoutMillis * 2;
        Map<Object, Collection<String>> scope = new LinkedHashMap<>();
        for (String fqn : fqns) {
            if (fqn == null || fqn.isBlank()) throw new IllegalArgumentException("export FQN must not be blank");
            result.requestedContexts.put(fqn, EXPORT_STAGES);
            scope.put(fqn, EXPORT_STAGES);
        }
        if (manager == null || scope.isEmpty()) {
            result.message = manager == null ? "native export completion service is unavailable"
                    : "no requested export contexts; completion is unconfirmed";
            return result;
        }
        long started = System.nanoTime();
        try {
            // The native ProjectSynchronizationManager schedules these exact stages with FQN keys.
            // waitComputationExt also drains accumulated contexts before checking their completion.
            result.completed = manager.waitComputationExt(scope, timeoutMillis);
            result.timedOut = !result.completed;
            result.message = result.completed ? "requested native export contexts completed"
                    : "native export wait timed out; serialized files may still precede the applied model";
        } catch (RuntimeException failure) {
            result.message = "native export completion could not be checked: " + GatewaySupport.describeCause(failure);
        } finally {
            result.waitMillis = (System.nanoTime() - started) / 1_000_000;
        }
        return result;
    }
}
