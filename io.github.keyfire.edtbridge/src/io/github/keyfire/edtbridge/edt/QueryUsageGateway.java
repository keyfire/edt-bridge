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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IResourceFactory;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.resource.XtextResourceSet;
import com._1c.g5.v8.dt.ql.model.QuerySchemaTable;
import com.google.inject.Injector;
import io.github.keyfire.edtbridge.core.MetadataPaths;
import io.github.keyfire.edtbridge.core.QueryMetadataNames;
import io.github.keyfire.edtbridge.core.QueryTextFields;
import io.github.keyfire.edtbridge.core.SearchPattern;

/** Search stored form/DCS query fields and find metadata table sources through EDT's QL AST. */
public final class QueryUsageGateway {
    private static final int MAX_XML_BYTES = 32 * 1024 * 1024;
    public static final String COVERAGE = "Query fields in workspace .form/.dcs XML and open text buffers; "
            + "unsaved graphical-designer changes and BSL query strings are outside this scan";

    public static final class Hit {
        public String project;
        public String kind;
        public String sourcePath;
        public String sourceFqn;
        public String formFqn;
        public String templateFqn;
        public String attribute;
        public String dataset;
        public int line;
        public int xmlLine;
        public String text;
        public String table;
        public boolean unsaved;
        public boolean textTruncated;
    }
    public static final class Failure {
        public String project;
        public String sourcePath;
        public String owner;
        public String message;
    }
    public static final class Result {
        public boolean ok;
        public boolean complete;
        public boolean truncated;
        public int filesScanned;
        public int queriesScanned;
        public int errorCount;
        public String coverage = COVERAGE;
        public String message;
        public List<Hit> hits = new ArrayList<>();
        public List<Failure> failures = new ArrayList<>();
    }
    private record Query(IFile file, String fqn, QueryTextFields.Field field, boolean unsaved) { }
    private interface Match { List<Hit> find(Query query) throws Exception; }

    public Result search(String projectName, String text, boolean regex, boolean caseSensitive,
            String pathFilter, String kind, int maxResults) {
        if (text == null || text.isEmpty()) {
            Result result = new Result(); result.message = "pattern is required"; return result;
        }
        Pattern pattern;
        try {
            pattern = SearchPattern.compile(regex ? text : Pattern.quote(text), caseSensitive);
        } catch (RuntimeException invalid) {
            Result result = new Result(); result.message = "invalid search expression: " + invalid.getMessage(); return result;
        }
        return scan(projectName, pathFilter, kind, maxResults, query -> {
            List<Hit> hits = new ArrayList<>();
            String[] lines = query.field().text().split("\\R", -1);
            for (int index = 0; index < lines.length; index++) {
                if (pattern.matcher(lines[index]).find()) hits.add(hit(query, index + 1, lines[index], null));
            }
            return hits;
        });
    }

    public Result references(String projectName, String fqn, int limit) {
        Result result = scan(projectName, null, "all", limit, query -> tableReferences(query, fqn));
        result.coverage = COVERAGE + "; references contain QL FROM/JOIN table sources, including nested/virtual tables; "
                + "VALUE/TYPE expressions and query assembly are outside this table-source scan";
        return result;
    }

    private Result scan(String projectName, String pathFilter, String kind, int maxResults, Match match) {
        Result result = new Result();
        String scope = kind == null || kind.isBlank() ? "all" : kind.toLowerCase(Locale.ROOT);
        if (!List.of("all", "dynamiclist", "dcs").contains(scope)) {
            result.message = "kind must be all, dynamicList, or dcs"; return result;
        }
        int cap = Math.min(maxResults > 0 ? maxResults : 200, 10000);
        List<IProject> projects = new ArrayList<>();
        if (projectName != null && !projectName.isBlank()) {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (!project.isOpen()) { result.message = "project not found or closed: " + projectName; return result; }
            projects.add(project);
        } else {
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) if (project.isOpen()) projects.add(project);
        }
        projects.sort(Comparator.comparing(IProject::getName));
        for (IProject project : projects) {
            List<IFile> files = new ArrayList<>();
            try {
                project.accept(resource -> {
                    if (resource instanceof IFile file) {
                        String extension = file.getFileExtension();
                        if (("form".equalsIgnoreCase(extension) && !scope.equals("dcs"))
                                || ("dcs".equalsIgnoreCase(extension) && !scope.equals("dynamiclist"))) {
                            if (pathFilter == null || pathFilter.isBlank()
                                    || file.getProjectRelativePath().toString().contains(pathFilter)) files.add(file);
                        }
                    }
                    return true;
                });
            } catch (Exception failure) {
                failed(result, project.getName(), null, null, failure); continue;
            }
            files.sort(Comparator.comparing(file -> file.getProjectRelativePath().toString()));
            for (IFile file : files) {
                result.filesScanned++;
                try {
                    var manager = FileBuffers.getTextFileBufferManager();
                    var buffer = manager == null ? null : manager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
                    boolean unsaved = buffer != null && buffer.isDirty();
                    List<QueryTextFields.Field> fields;
                    if (buffer != null && buffer.getDocument() != null) {
                        if (buffer.getDocument().getLength() > MAX_XML_BYTES) throw new IOException("query XML exceeds the 32 MiB scan limit");
                        fields = QueryTextFields.extract(buffer.getDocument().get());
                    } else {
                        try (var input = file.getContents()) {
                            byte[] bytes = input.readNBytes(MAX_XML_BYTES + 1);
                            if (bytes.length > MAX_XML_BYTES) throw new IOException("query XML exceeds the 32 MiB scan limit");
                            fields = QueryTextFields.extract(bytes);
                        }
                    }
                    String fqn = sourceFqn(file.getProjectRelativePath().toString());
                    for (QueryTextFields.Field field : fields) {
                        if (!scope.equals("all") && !scope.equals(field.kind().toLowerCase(Locale.ROOT))) continue;
                        result.queriesScanned++;
                        try {
                            for (Hit hit : match.find(new Query(file, fqn, field, unsaved))) {
                                if (result.hits.size() == cap) {
                                    result.truncated = true;
                                    finish(result);
                                    return result;
                                }
                                result.hits.add(hit);
                            }
                        } catch (Exception failure) {
                            failed(result, project.getName(), file.getProjectRelativePath().toString(), field.ownerName(), failure);
                        }
                    }
                } catch (Exception failure) {
                    failed(result, project.getName(), file.getProjectRelativePath().toString(), null, failure);
                }
            }
        }
        finish(result);
        return result;
    }

    private static void finish(Result result) {
        result.ok = result.errorCount == 0;
        result.complete = !result.truncated && result.errorCount == 0;
        result.message = "scanned " + result.filesScanned + " XML file(s), " + result.queriesScanned
                + " query field(s); returned " + result.hits.size() + " hit(s)"
                + (result.truncated ? "; hit limit reached, coverage is incomplete" : "")
                + (result.errorCount > 0 ? "; " + result.errorCount + " field/file(s) could not be inspected" : "");
    }

    private static void failed(Result result, String project, String path, String owner, Exception cause) {
        result.errorCount++;
        if (result.failures.size() >= 20) return;
        Failure failure = new Failure();
        failure.project = project; failure.sourcePath = path; failure.owner = owner;
        failure.message = GatewaySupport.describeCause(cause);
        result.failures.add(failure);
    }

    private static Hit hit(Query query, int line, String text, String table) {
        Hit hit = new Hit();
        hit.project = query.file().getProject().getName();
        hit.kind = query.field().kind(); hit.sourcePath = query.file().getProjectRelativePath().toString();
        hit.sourceFqn = query.fqn();
        if (hit.kind.equals("dynamicList")) { hit.formFqn = query.fqn(); hit.attribute = query.field().ownerName(); }
        else { hit.templateFqn = query.fqn(); hit.dataset = query.field().ownerName(); }
        hit.line = line; hit.xmlLine = query.field().xmlLine(); hit.unsaved = query.unsaved(); hit.table = table;
        hit.textTruncated = text.length() > 4000;
        hit.text = hit.textTruncated ? text.substring(0, 4000) : text;
        return hit;
    }

    private List<Hit> tableReferences(Query query, String fqn) throws Exception {
        boolean dcs = query.field().kind().equals("dcs");
        String bundleName = dcs ? "com._1c.g5.v8.dt.ql.dcs.ui" : "com._1c.g5.v8.dt.ql.ui";
        String activatorName = dcs ? "com._1c.g5.v8.dt.ql.dcs.ui.internal.QlDcsActivator"
                : "com._1c.g5.v8.dt.ql.ui.internal.QlActivator";
        String language = dcs ? "com._1c.g5.v8.dt.ql.dcs.QlDcs" : "com._1c.g5.v8.dt.ql.Ql";
        var bundle = Platform.getBundle(bundleName);
        if (bundle == null) throw new IOException("EDT query language services are unavailable: " + bundleName);
        Class<?> activatorClass = bundle.loadClass(activatorName);
        Object activator = activatorClass.getMethod("getInstance").invoke(null);
        if (activator == null) throw new IOException("EDT query language is not active: " + bundleName);
        Injector injector = (Injector) activatorClass.getMethod("getInjector", String.class).invoke(activator, language);
        XtextResourceSet set = injector.getInstance(XtextResourceSet.class);
        IResourceFactory factory = injector.getInstance(IResourceFactory.class);
        XtextResource resource = (XtextResource) factory.createResource(URI.createURI("platform:/resource/"
                + query.file().getProject().getName() + "/__edt_bridge_usage__." + (dcs ? "qldcs" : "ql")));
        set.getResources().add(resource);
        try {
            resource.load(new ByteArrayInputStream(query.field().text().getBytes(StandardCharsets.UTF_8)), set.getLoadOptions());
            if (!resource.getErrors().isEmpty()) throw new IOException("query syntax could not be parsed: " + resource.getErrors().get(0).getMessage());
            List<Hit> out = new ArrayList<>();
            String[] lines = query.field().text().split("\\R", -1);
            for (var iterator = resource.getAllContents(); iterator.hasNext();) {
                var object = iterator.next();
                if (!(object instanceof QuerySchemaTable source) || source.getTable() == null) continue;
                String table = source.getTable().getFullTableName();
                if (!QueryMetadataNames.matches(fqn, table)) continue;
                var node = NodeModelUtils.getNode(source.getTable());
                int line = node == null ? 1 : node.getStartLine();
                out.add(hit(query, line, line > 0 && line <= lines.length ? lines[line - 1] : table, table));
            }
            return out;
        } finally {
            resource.unload();
            set.getResources().remove(resource);
        }
    }

    private static String sourceFqn(String relative) {
        String[] parts = relative.replace('\\', '/').split("/");
        for (int index = 0; index + 1 < parts.length; index++) {
            String folder = parts[index];
            if (folder.equals("CommonForms")) return "CommonForm." + parts[index + 1];
            if (folder.equals("CommonTemplates")) return "CommonTemplate." + parts[index + 1];
            String type = MetadataPaths.TYPE_BY_FOLDER.get(folder);
            if (type == null && folder.equals("ExternalDataProcessors")) type = "ExternalDataProcessor";
            if (type == null && folder.equals("ExternalReports")) type = "ExternalReport";
            if (type == null) continue;
            String owner = type + "." + parts[index + 1];
            for (int child = index + 2; child + 1 < parts.length; child++) {
                if (parts[child].equals("Forms")) return owner + ".Form." + parts[child + 1];
                if (parts[child].equals("Templates")) return owner + ".Template." + parts[child + 1];
            }
        }
        return null;
    }
}
