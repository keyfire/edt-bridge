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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import io.github.keyfire.edtbridge.core.DeleteReferenceCandidates;

/** A bounded lexical audit for deletion previews and remaining-reference reports. */
public final class DeleteReferenceAuditGateway {
    static final int SAMPLE_CAP = 40;
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    private static final int MAX_FILES = 5000;
    private static final int MAX_CONTROL_SNAPSHOTS = 20_000;
    public static final String COVERAGE = "Possible references by case-insensitive whole identifier name in discovered "
            + "workspace files of open projects and their IFILE/LOCATION text buffers: BSL code and BSL strings/query literals (excluding // comments), actual dynamic-list "
            + ".form and dataset .dcs query fields (excluding query strings/comments), and form dataPath bindings. "
            + "Candidates are lexical: aliases, value types and runtime strings are not resolved, so names can match "
            + "unrelated members. Derived resources/subtrees are excluded (only skipped roots are counted). "
            + "The cumulative content budget stops after a bounded final file read, which can cross that budget by "
            + "at most maxFinalReadOverrunBytes. Closed projects, external sources, unmaterialized/unregistered text buffers, unsaved graphical designers and names assembled "
            + "at runtime are not covered. complete=false means a full semantic cascade has not been proven.";

    public static final class Example {
        public String project;
        public String sourcePath;
        public String kind;
        public String owner;
        public int line;
        public String text;
        public boolean unsaved;
    }
    public static final class Failure {
        public String project;
        public String sourcePath;
        public String message;
    }
    public static final class Result {
        public boolean complete = false;
        public boolean scanComplete;
        public String coverage = COVERAGE;
        public int candidateCount;
        public boolean countIsLowerBound;
        public boolean snapshotMayPrecedeNativeExport;
        public Map<String, Integer> byKind = new LinkedHashMap<>();
        public int projectsScanned;
        public int closedProjectsSkipped;
        public int filesScanned;
        public int filesConsidered;
        public int derivedResourceRootsSkipped;
        public long observedContentBytes;
        public Map<String, Long> limits = Map.of("maxFiles", (long) MAX_FILES,
                "maxFileBytes", (long) MAX_FILE_BYTES, "maxTotalBytes", MAX_TOTAL_BYTES,
                "maxExamples", (long) SAMPLE_CAP, "maxControlSnapshots", (long) MAX_CONTROL_SNAPSHOTS,
                "maxFinalReadOverrunBytes", (long) MAX_FILE_BYTES + 1);
        public int filesWithUnsavedChanges;
        public boolean truncated;
        public boolean examplesTruncated;
        public int errorCount;
        public List<Example> examples = new ArrayList<>();
        public List<Failure> failures = new ArrayList<>();
        transient Map<String, List<DeleteReferenceCandidates.Control>> formControls = new LinkedHashMap<>();
    }
    public static final class UnboundControl {
        public String project;
        public String sourcePath;
        public String name;
        public String type;
        public String previousDataPath;
    }
    record Content(byte[] bytes, String bufferText, boolean unsaved) {
        int size() { return bufferText == null ? bytes.length : bufferText.length() * 2; }
    }
    @FunctionalInterface interface Reader { Content read(IFile file) throws Exception; }
    private final Supplier<IProject[]> projects;
    private final Reader reader;

    public DeleteReferenceAuditGateway() {
        this(() -> ResourcesPlugin.getWorkspace().getRoot().getProjects(), DeleteReferenceAuditGateway::read);
    }
    DeleteReferenceAuditGateway(Supplier<IProject[]> projects, Reader reader) {
        this.projects = projects;
        this.reader = reader;
    }

    public Result scan(String memberName) {
        Result result = new Result();
        long totalBytes = 0;
        int controlCount = 0;
        List<IProject> scope = new ArrayList<>(List.of(projects.get()));
        scope.sort(Comparator.comparing(IProject::getName));
        for (IProject project : scope) {
            if (!project.isOpen()) { result.closedProjectsSkipped++; continue; }
            result.projectsScanned++;
            List<IFile> files = new ArrayList<>();
            try {
                project.accept(resource -> {
                    if (result.truncated) return false;
                    if (resource.isDerived()) { result.derivedResourceRootsSkipped++; return false; }
                    if (resource instanceof IFile file) {
                        String extension = file.getFileExtension();
                        if ("bsl".equalsIgnoreCase(extension) || "form".equalsIgnoreCase(extension)
                                || "dcs".equalsIgnoreCase(extension)) {
                            if (files.size() + result.filesConsidered >= MAX_FILES) result.truncated = true;
                            else files.add(file);
                        }
                    }
                    return true;
                });
            } catch (Exception failure) {
                failed(result, project.getName(), null, failure);
            }
            files.sort(Comparator.comparing(file -> file.getProjectRelativePath().toPortableString()));
            for (IFile file : files) {
                String path = file.getProjectRelativePath().toPortableString();
                result.filesConsidered++;
                boolean byteBudgetExhausted = false;
                try {
                    Content content = reader.read(file);
                    totalBytes += content.size();
                    result.observedContentBytes = totalBytes;
                    if (totalBytes > MAX_TOTAL_BYTES) {
                        result.truncated = true;
                        byteBudgetExhausted = true;
                    }
                    if (content.size() > MAX_FILE_BYTES) throw new IOException("file/buffer exceeds the 8 MiB audit limit");
                    if (byteBudgetExhausted) break;
                    result.filesScanned++;
                    if (content.unsaved()) result.filesWithUnsavedChanges++;
                    DeleteReferenceCandidates.Scan scan;
                    if ("bsl".equalsIgnoreCase(file.getFileExtension())) {
                        String text = content.bufferText() != null ? content.bufferText()
                                : Charset.forName(file.getCharset()).newDecoder()
                                        .onMalformedInput(CodingErrorAction.REPORT)
                                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                                        .decode(ByteBuffer.wrap(content.bytes())).toString();
                        scan = DeleteReferenceCandidates.bsl(text, memberName, SAMPLE_CAP);
                    } else {
                        boolean form = "form".equalsIgnoreCase(file.getFileExtension());
                        scan = content.bufferText() == null
                                ? DeleteReferenceCandidates.xml(content.bytes(), memberName, form, SAMPLE_CAP)
                                : DeleteReferenceCandidates.xml(content.bufferText(), memberName, form, SAMPLE_CAP);
                        if (form) {
                            if (controlCount + scan.controls.size() <= MAX_CONTROL_SNAPSHOTS) {
                                result.formControls.put(project.getName() + "\n" + path, scan.controls);
                                controlCount += scan.controls.size();
                            } else result.truncated = true;
                            if (scan.controlsTruncated) result.truncated = true;
                        }
                    }
                    result.candidateCount += scan.candidateCount();
                    scan.counts.forEach((kind, count) -> result.byKind.merge(kind, count, Integer::sum));
                    for (var candidate : scan.examples) {
                        if (result.examples.size() >= SAMPLE_CAP) break;
                        Example example = new Example();
                        example.project = project.getName(); example.sourcePath = path;
                        example.kind = candidate.kind(); example.owner = candidate.owner();
                        example.line = candidate.line(); example.text = candidate.text(); example.unsaved = content.unsaved();
                        result.examples.add(example);
                    }
                } catch (Exception failure) {
                    failed(result, project.getName(), path, failure);
                }
                if (byteBudgetExhausted) break;
            }
            if (result.truncated) break;
        }
        result.scanComplete = !result.truncated && result.errorCount == 0 && result.filesWithUnsavedChanges == 0;
        result.countIsLowerBound = !result.scanComplete;
        result.examplesTruncated = result.candidateCount > result.examples.size();
        return result;
    }

    public static List<UnboundControl> retainedUnbound(Result before, Result after, String name) {
        List<UnboundControl> result = new ArrayList<>();
        for (var entry : before.formControls.entrySet()) {
            List<DeleteReferenceCandidates.Control> current = after.formControls.get(entry.getKey());
            if (current == null) continue;
            String[] location = entry.getKey().split("\n", 2);
            for (var control : DeleteReferenceCandidates.retainedWithoutDataPath(entry.getValue(), current, name)) {
                UnboundControl retained = new UnboundControl();
                retained.project = location[0]; retained.sourcePath = location[1]; retained.name = control.name();
                retained.type = control.type(); retained.previousDataPath = control.dataPath();
                result.add(retained);
            }
        }
        return result;
    }

    private static Content read(IFile file) throws Exception {
        var manager = FileBuffers.getTextFileBufferManager();
        var buffer = manager == null ? null : manager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
        var locationBuffer = manager == null || file.getLocation() == null ? null
                : manager.getTextFileBuffer(file.getLocation(), LocationKind.LOCATION);
        Content selected = selectBuffers(buffer, locationBuffer);
        if (selected != null) return selected;
        try (var input = file.getContents()) {
            return new Content(input.readNBytes(MAX_FILE_BYTES + 1), null, false);
        }
    }

    static Content selectBuffers(org.eclipse.core.filebuffers.ITextFileBuffer ifile,
            org.eclipse.core.filebuffers.ITextFileBuffer location) throws IOException {
        if (ifile == null && location == null) return null;
        if (ifile == null) return bufferContent(location);
        if (location == null || ifile == location) return bufferContent(ifile);
        Content first = bufferContent(ifile);
        Content second = bufferContent(location);
        if (first.unsaved() != second.unsaved()) return first.unsaved() ? first : second;
        if (!first.bufferText().equals(second.bufferText())) {
            throw new IOException("IFILE and LOCATION buffers disagree; save or reconcile both editors before auditing");
        }
        return first;
    }

    private static Content bufferContent(org.eclipse.core.filebuffers.ITextFileBuffer buffer) throws IOException {
        if (buffer.getDocument() == null) throw new IOException("an open text buffer has no readable document");
        if (buffer.getDocument().getLength() > MAX_FILE_BYTES / 2) throw new IOException("text buffer exceeds the 8 MiB audit limit");
        return new Content(null, buffer.getDocument().get(), buffer.isDirty());
    }

    private static void failed(Result result, String project, String path, Exception cause) {
        result.errorCount++;
        if (result.failures.size() >= 20) return;
        Failure failure = new Failure();
        failure.project = project; failure.sourcePath = path; failure.message = GatewaySupport.describeCause(cause);
        result.failures.add(failure);
    }
}
