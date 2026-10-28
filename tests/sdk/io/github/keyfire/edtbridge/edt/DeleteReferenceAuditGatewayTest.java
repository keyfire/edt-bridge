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
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceVisitor;
import org.junit.jupiter.api.Test;

class DeleteReferenceAuditGatewayTest {
    @Test void countsAllObservedCandidatesWhileCappingOnlyExamplesAndNeverClaimsSemanticCompleteness() {
        Fixture fixture = new Fixture("Demo");
        for (int index = 0; index < 60; index++) fixture.add("src/Module" + index + ".bsl", "Record.Version = 1;");
        var result = fixture.audit().scan("Version");
        assertEquals(60, result.candidateCount);
        assertEquals(40, result.examples.size());
        assertTrue(result.examplesTruncated);
        assertTrue(result.scanComplete);
        assertFalse(result.complete);
        assertFalse(result.countIsLowerBound);
        assertEquals(60, result.byKind.get("bslCode"));
    }

    @Test void readFailureAndMalformedXmlKeepOtherCandidatesButMarkCountsAsLowerBounds() {
        Fixture fixture = new Fixture("Demo");
        fixture.add("src/Good.bsl", "Record.Version = 1;");
        fixture.add("src/Missing.bsl", (DeleteReferenceAuditGateway.Content) null);
        fixture.add("src/Form.form", "<form>");
        var result = fixture.audit().scan("Version");
        assertEquals(1, result.candidateCount);
        assertEquals(2, result.errorCount);
        assertEquals(2, result.failures.size());
        assertFalse(result.scanComplete);
        assertTrue(result.countIsLowerBound);
    }

    @Test void dirtyBuffersAreAuditedAndCannotBeReportedAsSettledStoredSources() {
        Fixture fixture = new Fixture("Demo");
        fixture.add("src/Module.bsl", new DeleteReferenceAuditGateway.Content(null, "Record.Version = 1;", true));
        var result = fixture.audit().scan("Version");
        assertEquals(1, result.candidateCount);
        assertEquals(1, result.filesWithUnsavedChanges);
        assertTrue(result.examples.get(0).unsaved);
        assertFalse(result.scanComplete);
        assertTrue(result.countIsLowerBound);
    }

    @Test void xmlBufferHonorsCharactersDespiteAnOriginalUtf16Declaration() {
        Fixture fixture = new Fixture("Demo");
        fixture.add("src/Form.form", new DeleteReferenceAuditGateway.Content(null,
                "<?xml version=\"1.0\" encoding=\"UTF-16\"?><form xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + "<items xsi:type=\"form:InputField\"><name>VersionField</name>"
                + "<dataPath><segments>Record.Version</segments></dataPath></items></form>", false));
        var result = fixture.audit().scan("Version");
        assertTrue(result.scanComplete);
        assertEquals(1, result.byKind.get("formBinding"));
        assertEquals("Record.Version", result.examples.get(0).text);
    }

    @Test void oversizeFileAndClosedProjectAreNamedAsUncoveredInsteadOfProvingNoReferences() {
        Fixture fixture = new Fixture("Demo");
        fixture.add("src/Large.bsl", new DeleteReferenceAuditGateway.Content(new byte[8 * 1024 * 1024 + 1], null, false));
        Fixture closed = new Fixture("Closed");
        closed.open = false;
        var audit = new DeleteReferenceAuditGateway(() -> new IProject[] {fixture.project, closed.project}, fixture::read);
        var result = audit.scan("Version");
        assertEquals(1, result.closedProjectsSkipped);
        assertEquals(1, result.errorCount);
        assertFalse(result.scanComplete);
        assertFalse(result.complete);
        assertTrue(result.countIsLowerBound);
    }

    @Test void globalFileLimitCountsUnreadableFilesAcrossProjects() {
        Fixture first = new Fixture("First");
        Fixture second = new Fixture("Second");
        for (int index = 0; index < 3000; index++) {
            first.add("src/Missing" + index + ".bsl", (DeleteReferenceAuditGateway.Content) null);
            second.add("src/Missing" + index + ".bsl", (DeleteReferenceAuditGateway.Content) null);
        }
        var audit = new DeleteReferenceAuditGateway(() -> new IProject[] {first.project, second.project}, file -> {
            throw new IOException("unreadable");
        });
        var result = audit.scan("Version");
        assertEquals(5000, result.errorCount);
        assertTrue(result.truncated);
        assertTrue(result.countIsLowerBound);
        assertEquals(20, result.failures.size());
    }

    @Test void dirtyLocationBufferIsNotMaskedByCleanIfileBuffer() throws Exception {
        var content = DeleteReferenceAuditGateway.selectBuffers(
                buffer("Record.Other = 1;", false), buffer("Record.Version = 1;", true));
        assertEquals("Record.Version = 1;", content.bufferText());
        assertTrue(content.unsaved());
    }

    @Test void disagreeingDirtyOrCleanBuffersCannotPretendToBeOneCompleteDocument() {
        assertThrows(IOException.class, () -> DeleteReferenceAuditGateway.selectBuffers(
                buffer("Record.Other = 1;", true), buffer("Record.Version = 1;", true)));
        assertThrows(IOException.class, () -> DeleteReferenceAuditGateway.selectBuffers(
                buffer("Record.Other = 1;", false), buffer("Record.Version = 1;", false)));
    }

    @Test void identicalBuffersKeepTheirUnsavedState() throws Exception {
        var content = DeleteReferenceAuditGateway.selectBuffers(
                buffer("Record.Version = 1;", false), buffer("Record.Version = 1;", true));
        assertEquals("Record.Version = 1;", content.bufferText());
        assertTrue(content.unsaved());
    }

    @Test void oversizeFilesConsumeTheGlobalByteBudgetInsteadOfCausingUnboundedReadWork() {
        Fixture fixture = new Fixture("Demo");
        var oversize = new DeleteReferenceAuditGateway.Content(new byte[8 * 1024 * 1024 + 1], null, false);
        for (int index = 0; index < 20; index++) fixture.add("src/Large" + index + ".bsl", oversize);
        var result = fixture.audit().scan("Version");
        assertTrue(result.truncated);
        assertTrue(result.filesConsidered <= 9);
        assertTrue(result.countIsLowerBound);
    }

    private static org.eclipse.core.filebuffers.ITextFileBuffer buffer(String text, boolean dirty) {
        return (org.eclipse.core.filebuffers.ITextFileBuffer) Proxy.newProxyInstance(
                org.eclipse.core.filebuffers.ITextFileBuffer.class.getClassLoader(),
                new Class<?>[] {org.eclipse.core.filebuffers.ITextFileBuffer.class}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "getDocument" -> new org.eclipse.jface.text.Document(text);
                    case "isDirty" -> dirty;
                    default -> method.getReturnType() == boolean.class ? false : null;
                });
    }

    @Test void retainedUnboundControlReportIncludesItsProjectPathAndPreviousBinding() {
        Fixture fixture = new Fixture("Demo");
        String prefix = "<form xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + "<items xsi:type=\"form:InputField\"><name>VersionField</name>";
        fixture.add("src/Record/Form.form", prefix + "<dataPath>Record.Version</dataPath></items></form>");
        var before = fixture.audit().scan("Version");
        fixture.add("src/Record/Form.form", prefix + "</items></form>");
        var after = fixture.audit().scan("Version");
        var controls = DeleteReferenceAuditGateway.retainedUnbound(before, after, "Version");
        assertEquals(1, controls.size());
        assertEquals("Demo", controls.get(0).project);
        assertEquals("src/Record/Form.form", controls.get(0).sourcePath);
        assertEquals("Record.Version", controls.get(0).previousDataPath);
    }

    private static final class Fixture {
        final String name;
        final Map<String, DeleteReferenceAuditGateway.Content> contents = new LinkedHashMap<>();
        final Map<String, IFile> files = new LinkedHashMap<>();
        boolean open = true;
        IProject project;

        Fixture(String name) {
            this.name = name;
            project = (IProject) Proxy.newProxyInstance(IProject.class.getClassLoader(), new Class<?>[] {IProject.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getName")) return name;
                        if (method.getName().equals("isOpen")) return open;
                        if (method.getName().equals("accept")) {
                            IResourceVisitor visitor = (IResourceVisitor) args[0];
                            for (IFile file : files.values()) visitor.visit(file);
                        }
                        return method.getReturnType() == boolean.class ? false : null;
                    });
        }

        void add(String path, String text) {
            add(path, new DeleteReferenceAuditGateway.Content(text.getBytes(StandardCharsets.UTF_8), null, false));
        }
        void add(String path, DeleteReferenceAuditGateway.Content content) {
            contents.put(path, content);
            files.putIfAbsent(path, (IFile) Proxy.newProxyInstance(IFile.class.getClassLoader(), new Class<?>[] {IFile.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getFileExtension" -> path.substring(path.lastIndexOf('.') + 1);
                        case "getProjectRelativePath" -> new org.eclipse.core.runtime.Path(path);
                        case "getProject" -> project;
                        case "getCharset" -> "UTF-8";
                        default -> method.getReturnType() == boolean.class ? false : null;
                    }));
        }
        DeleteReferenceAuditGateway.Content read(IFile file) throws Exception {
            String path = file.getProjectRelativePath().toPortableString();
            var content = contents.get(path);
            if (content == null) throw new IOException("file could not be read: " + path);
            return content;
        }
        DeleteReferenceAuditGateway audit() {
            return new DeleteReferenceAuditGateway(() -> new IProject[] {project}, this::read);
        }
    }
}
