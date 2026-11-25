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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

class FormCopyGatewayTest {

    @TempDir
    Path directory;

    @Test void refusesToOverwriteExistingModuleMissingFromWorkspaceIndex() throws Exception {
        Path module = directory.resolve("Module.bsl");
        Files.writeString(module, "original");

        assertThrows(FileAlreadyExistsException.class,
                () -> FormCopyGateway.writeNewModule(workspaceHandle(module), bytes("copy")));

        assertEquals("original", Files.readString(module));
    }

    @Test void createsNewModuleWithOriginalBytes() throws Exception {
        Path module = directory.resolve("Module.bsl");

        String path = FormCopyGateway.writeNewModule(workspaceHandle(module), bytes("original\r\n"));

        assertEquals("Module.bsl", path);
        assertEquals("original\r\n", Files.readString(module));
    }

    @Test void preservesACompetingFileCreatedAfterTheInitialCheck() throws Exception {
        Path module = directory.resolve("Module.bsl");

        assertThrows(FileAlreadyExistsException.class, () -> FormCopyGateway.writeNewModule(
                workspaceHandle(module, () -> Files.writeString(module, "competing file")), bytes("copy")));

        assertEquals("competing file", Files.readString(module));
    }

    @Test void retainsExtendedPresentationInTheSameFormKind() {
        var source = MdClassFactory.eINSTANCE.createDataProcessorForm();
        source.getExtendedPresentation().put("ru", "Name");
        source.getExtendedPresentation().put("en", "Presentation");
        var copy = MdClassFactory.eINSTANCE.createDataProcessorForm();

        FormCopyGateway.describeLike(source, copy);

        assertEquals(Map.of("en", "Presentation", "ru", "Name"), copy.getExtendedPresentation().map());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface BeforeWrite {
        void run() throws Exception;
    }

    private static IFile workspaceHandle(Path module) {
        return workspaceHandle(module, () -> {});
    }

    private static IFile workspaceHandle(Path module, BeforeWrite beforeWrite) {
        return (IFile) Proxy.newProxyInstance(IFile.class.getClassLoader(), new Class<?>[] {IFile.class},
                (ignored, method, args) -> switch (method.getName()) {
                    case "getLocation" -> org.eclipse.core.runtime.Path.fromOSString(module.toString());
                    case "getFullPath", "getProjectRelativePath" ->
                            org.eclipse.core.runtime.Path.fromPortableString("Module.bsl");
                    case "exists" -> false;
                    case "getParent" -> {
                        beforeWrite.run();
                        yield null;
                    }

                    case "refreshLocal" -> null;
                    default -> throw new AssertionError("unexpected IFile operation: " + method);
                });
    }
}
