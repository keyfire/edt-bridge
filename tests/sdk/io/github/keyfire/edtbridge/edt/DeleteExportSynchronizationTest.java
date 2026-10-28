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

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com._1c.g5.v8.derived.IDerivedDataManager;
import io.github.keyfire.edtbridge.core.DeleteReferenceCandidates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeleteExportSynchronizationTest {
    @TempDir Path temporary;

    @Test void delayedNativeSerializationIsObservedBeforePostAuditInsteadOfReadingTheOldForm() throws Exception {
        Path form = temporary.resolve("Form.form");
        String prefix = "<form xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + "<items xsi:type=\"form:InputField\"><name>VersionField</name>";
        Files.writeString(form, prefix + "<dataPath>Record.Version</dataPath></items></form>");
        var before = DeleteReferenceCandidates.xml(Files.readAllBytes(form), "Version", true, 40);
        CountDownLatch barrierEntered = new CountDownLatch(1);
        CompletableFuture<Void> serialization = CompletableFuture.runAsync(() -> {
            try {
                assertTrue(barrierEntered.await(2, TimeUnit.SECONDS));
                Files.writeString(form, prefix + "</items></form>", StandardCharsets.UTF_8);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        IDerivedDataManager nativeManager = manager((scope, timeout) -> {
            assertEquals(Map.of("InformationRegister.Versions", List.of("EXP_O", "EXP_B"),
                    "InformationRegister.Versions.Form.Record.Form", List.of("EXP_O", "EXP_B")), scope);
            assertEquals(1000L, timeout);
            barrierEntered.countDown();
            serialization.get(2, TimeUnit.SECONDS);
            return true;
        });
        try {
            var waited = DeleteExportSynchronization.await(nativeManager,
                    List.of("InformationRegister.Versions", "InformationRegister.Versions.Form.Record.Form"), 1000);
            var after = DeleteReferenceCandidates.xml(Files.readAllBytes(form), "Version", true, 40);
            assertEquals(1, DeleteReferenceCandidates.retainedWithoutDataPath(
                    before.controls, after.controls, "Version").size());
            assertTrue(waited.completed);
            assertFalse(waited.timedOut);
        } finally {
            barrierEntered.countDown();
            serialization.get(2, TimeUnit.SECONDS);
        }
    }

    @Test void nativeTimeoutCannotBePresentedAsCompletedSerialization() throws Exception {
        var waited = DeleteExportSynchronization.await(manager((scope, timeout) -> false),
                List.of("InformationRegister.Versions"), 1000);
        assertFalse(waited.completed);
        assertTrue(waited.timedOut);
        assertEquals(2000L, waited.maxExpectedWaitMillis);
    }

    @Test void missingManagerOrTargetsCannotVacuouslyConfirmCompletion() throws Exception {
        assertFalse(DeleteExportSynchronization.await(null, List.of("Catalog.Orders"), 1000).completed);
        assertFalse(DeleteExportSynchronization.await(manager((scope, timeout) -> true), List.of(), 1000).completed);
    }

    @Test void interruptionPropagatesWithoutStartingAHelperThread() {
        assertThrows(InterruptedException.class, () -> DeleteExportSynchronization.await(
                manager((scope, timeout) -> { throw new InterruptedException("canceled"); }),
                List.of("Catalog.Orders"), 1000));
    }

    @Test void unboundedTimeoutIsRejectedBeforeCallingTheNativeManager() {
        var nativeManager = manager((scope, timeout) -> { fail("native wait must not be called"); return true; });
        assertThrows(IllegalArgumentException.class, () -> DeleteExportSynchronization.await(
                nativeManager, List.of("Catalog.Orders"), 0));
        assertThrows(IllegalArgumentException.class, () -> DeleteExportSynchronization.await(
                nativeManager, List.of("Catalog.Orders"), 60_000));
    }

    @FunctionalInterface private interface NativeWait {
        boolean await(Object scope, long timeout) throws Exception;
    }
    private static IDerivedDataManager manager(NativeWait wait) {
        return (IDerivedDataManager) Proxy.newProxyInstance(IDerivedDataManager.class.getClassLoader(),
                new Class<?>[] {IDerivedDataManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("waitComputationExt")) return wait.await(args[0], (Long) args[1]);
                    return method.getReturnType() == boolean.class ? false : null;
                });
    }
}
