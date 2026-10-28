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
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import org.junit.jupiter.api.Test;

class DeleteObjectRegressionTest {
    @Test void childReadbackConfirmsPresenceThenAbsenceInsteadOfOnlyTrustingNativePerform() {
        var owner = MdClassFactory.eINSTANCE.createInformationRegister();
        owner.setName("Versions");
        var resource = MdClassFactory.eINSTANCE.createInformationRegisterResource();
        resource.setName("Version");
        owner.getResources().add(resource);
        IBmTransaction transaction = (IBmTransaction) Proxy.newProxyInstance(
                IBmTransaction.class.getClassLoader(), new Class<?>[] {IBmTransaction.class},
                (proxy, method, args) -> method.getName().equals("getTopObjectByFqn")
                        && args[0].equals("InformationRegister.Versions") ? owner : null);
        String fqn = "InformationRegister.Versions.Resource.Version";
        assertSame(resource, MetadataWriteGateway.resolveDeleteTarget(transaction, fqn));
        assertNull(MetadataWriteGateway.resolveDeleteTarget(transaction, "InformationRegister.Versions.Dimension.Version"));
        assertNull(MetadataWriteGateway.resolveDeleteTarget(transaction, "InformationRegister.Versions.Unknown.Version"));
        assertNull(MetadataWriteGateway.resolveDeleteTarget(transaction, "InformationRegister.Versions.ce.Version"));
        assertNull(MetadataWriteGateway.resolveDeleteTarget(transaction, "InformationRegister.Versions.MdObject.Version"));
        assertNull(MetadataWriteGateway.resolveDeleteTarget(transaction, fqn + "."));
        var duplicate = MdClassFactory.eINSTANCE.createInformationRegisterResource();
        duplicate.setName("Version");
        owner.getResources().add(duplicate);
        assertThrows(IllegalArgumentException.class, () -> MetadataWriteGateway.resolveDeleteTarget(transaction, fqn));
        owner.getResources().clear();
        assertNull(MetadataWriteGateway.resolveDeleteTarget(transaction, fqn));
        assertSame(owner, MetadataWriteGateway.resolveDeleteTarget(transaction, "InformationRegister.Versions"));
    }

    @Test void emptyUncheckedIncompleteAndBlockedNativePlansCannotAuthorizeDelete() {
        var result = new MetadataWriteGateway.DeleteObjectResult();
        result.refactoringCount = 1;
        assertFalse(MetadataWriteGateway.deleteReady(result), "native count=1 with items=0 is not a delete plan");
        var item = new MetadataWriteGateway.RenameItem();
        item.name = "Delete Version";
        result.items.add(item);
        assertFalse(MetadataWriteGateway.deleteReady(result), "unchecked items do not authorize writes");
        item.checked = true;
        assertTrue(MetadataWriteGateway.deleteReady(result));
        result.truncated = true;
        assertFalse(MetadataWriteGateway.deleteReady(result), "a capped native preview cannot authorize unenumerated changes");
        result.truncated = false;
        result.nativePreviewComplete = false;
        assertFalse(MetadataWriteGateway.deleteReady(result));
        result.nativePreviewComplete = true;
        result.problems.add(new MetadataWriteGateway.RenameProblem());
        assertFalse(MetadataWriteGateway.deleteReady(result));
    }
}
