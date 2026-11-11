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

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import io.github.keyfire.edtbridge.core.SpreadsheetLayout;
import io.github.keyfire.edtbridge.core.SpreadsheetRender;
import io.github.keyfire.edtbridge.core.TextChange;

/**
 * A spreadsheet template (a print form layout) as a compact description: column widths and named
 * row areas of cells. The file stays the source of truth; this gateway reads it into the description,
 * writes a description back as the complete file and renders it to a picture.
 */
public final class SpreadsheetGateway {
    private final HtmlTemplateGateway templates = new HtmlTemplateGateway();

    public static final class ReadResult {
        public boolean ok;
        public String templateFqn;
        public String path;
        public String sourceHash;
        public SpreadsheetLayout.Layout layout;
        public String message;
    }

    public static final class WriteResult {
        public boolean ok;
        public boolean applied;
        public String templateFqn;
        public String path;
        public String sourceHash;
        public String resultHash;
        public List<String> plan = new ArrayList<>();
        public TextChange change;
        /** Features of the current file the compact description cannot carry; a write drops them. */
        public List<String> dropped = new ArrayList<>();
        public String message;
    }

    public static final class RenderResult {
        public boolean ok;
        public String templateFqn;
        public String pngPath;
        public int width;
        public int height;
        public List<String> unsupported = new ArrayList<>();
        public String message;
    }

    /** EDT stores a new, empty spreadsheet template as a file of zero length. */
    private static SpreadsheetLayout.Layout layoutOf(String text) {
        return text == null || text.isBlank() ? new SpreadsheetLayout.Layout(List.of(), List.of(), List.of())
                : SpreadsheetLayout.parse(text);
    }

    public ReadResult read(String projectName, String fqn) {
        ReadResult result = new ReadResult();
        result.templateFqn = fqn;
        HtmlTemplateGateway.Result source = templates.readSpreadsheet(projectName, fqn);
        result.path = source.path;
        if (!source.ok) {
            result.message = source.message;
            return result;
        }
        try {
            result.layout = layoutOf(source.text);
            result.sourceHash = source.sourceHash;
            result.ok = true;
            result.message = result.layout.unsupported().isEmpty() ? "the template is fully described"
                    : "the template also has " + String.join(", ", result.layout.unsupported())
                            + ", which the description does not carry; edt_write_spreadsheet would drop them";
        } catch (IllegalArgumentException refusal) {
            result.message = "the template file cannot be read as a spreadsheet: " + refusal.getMessage();
        }
        return result;
    }

    public WriteResult write(String projectName, String fqn, SpreadsheetLayout.Layout layout, String expectedHash,
            boolean dropUnsupported, boolean apply) {
        WriteResult result = new WriteResult();
        result.templateFqn = fqn;
        HtmlTemplateGateway.Result current = templates.readSpreadsheet(projectName, fqn);
        result.path = current.path;
        if (!current.ok) {
            result.message = current.message;
            return result;
        }
        if (apply && (expectedHash == null || expectedHash.isBlank())) {
            result.message = "apply requires expectedHash: the sourceHash of the dry-run";
            return result;
        }
        try {
            SpreadsheetLayout.Layout before = layoutOf(current.text);
            result.dropped.addAll(before.unsupported());
            if (!result.dropped.isEmpty() && !dropUnsupported) {
                result.message = "nothing written: the template has " + String.join(", ", result.dropped)
                        + ", which the description does not carry and a write would drop; pass dropUnsupported=true "
                        + "to accept that, or change such a template in the EDT editor";
                return result;
            }
            String text = SpreadsheetLayout.write(layout);
            for (SpreadsheetLayout.Area area : layout.areas()) {
                result.plan.add("area " + (area.name() == null || area.name().isEmpty() ? "(unnamed)" : area.name())
                        + ": " + area.rows().size() + " row(s)");
            }
            result.change = TextChange.of(current.text, text);
            HtmlTemplateGateway.Result replaced = templates.replaceSpreadsheet(projectName, fqn, text,
                    apply ? expectedHash : current.sourceHash, apply, apply);
            result.sourceHash = replaced.sourceHash;
            result.resultHash = replaced.resultHash;
            result.applied = replaced.applied;
            result.ok = replaced.ok;
            result.message = !replaced.ok ? replaced.message
                    : apply ? "template written: " + String.join("; ", result.plan)
                            + ". Check it with edt_spreadsheet_render; the build of the object checks the file"
                    : "dry-run: " + String.join("; ", result.plan) + "; apply with expectedHash=" + replaced.sourceHash;
        } catch (IllegalArgumentException refusal) {
            result.message = "nothing written: " + refusal.getMessage();
        }
        return result;
    }

    public RenderResult render(String projectName, String fqn, String outPath) {
        RenderResult result = new RenderResult();
        result.templateFqn = fqn;
        HtmlTemplateGateway.Result source = templates.readSpreadsheet(projectName, fqn);
        if (!source.ok) {
            result.message = source.message;
            return result;
        }
        try {
            SpreadsheetLayout.Layout layout = layoutOf(source.text);
            byte[] png = SpreadsheetRender.png(layout);
            File target = outPath != null && !outPath.isBlank() ? new File(outPath)
                    : File.createTempFile("edt_spreadsheet_", ".png");
            if (target.getParentFile() != null) target.getParentFile().mkdirs();
            Files.write(target.toPath(), png);
            var image = javax.imageio.ImageIO.read(target);
            result.pngPath = target.getAbsolutePath();
            result.width = image == null ? 0 : image.getWidth();
            result.height = image == null ? 0 : image.getHeight();
            result.unsupported.addAll(layout.unsupported());
            result.ok = true;
            result.message = result.unsupported.isEmpty() ? "rendered" : "rendered without " + String.join(", ", result.unsupported);
        } catch (IllegalArgumentException refusal) {
            result.message = "the template cannot be rendered: " + refusal.getMessage();
        } catch (java.io.IOException failure) {
            result.message = "the picture was not written: " + failure.getMessage();
        }
        return result;
    }
}
