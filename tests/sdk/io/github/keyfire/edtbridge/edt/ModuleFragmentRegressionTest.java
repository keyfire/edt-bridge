/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.core.ExactTextReplacement;
import io.github.keyfire.edtbridge.core.ModuleFragment;
import org.eclipse.core.resources.IFile;
import org.eclipse.xtext.resource.XtextResource;
import org.junit.jupiter.api.Test;

class ModuleFragmentRegressionTest {
    private static final String FIRST = "// First anchor";
    private static final String LAST = "// Last anchor";
    private static final String FIRST_REPLACEMENT = "// First replacement\n// More context";
    private static final String LAST_REPLACEMENT = "// Last replacement\n" + "// detail\n".repeat(150);
    private static final String DISTANT_LINE = "// Unrelated part of the large module";
    private static final String SOURCE = FIRST + "\n" + "// unchanged line\n".repeat(2_000)
            + DISTANT_LINE + "\n" + "// unchanged line\n".repeat(2_000) + LAST + "\n";
    private static final List<ModuleFragment.Change> EDITS = List.of(
            new ModuleFragment.Change(FIRST, FIRST_REPLACEMENT), new ModuleFragment.Change(LAST, LAST_REPLACEMENT));

    @Test void batchDryRunReturnsShortPreviewsAndLeavesLargeModuleUnchanged() {
        var contents = new AtomicReference<>(SOURCE);
        JsonObject result = replace(contents, null, false);
        assertTrue(result.get("ok").getAsBoolean(), result::toString);
        assertTrue(result.get("applyPending").getAsBoolean());
        assertEquals(ExactTextReplacement.hash(SOURCE), result.get("sourceHash").getAsString());
        assertEquals(SOURCE, contents.get());
        assertShortPreviews(result);
    }

    @Test void batchApplyReturnsShortPreviewsAndWritesTheEntireUpdatedModule() {
        var contents = new AtomicReference<>(SOURCE);
        JsonObject dryRun = replace(contents, null, false);
        JsonObject result = replace(contents, dryRun.get("sourceHash").getAsString(), true);
        assertTrue(result.get("applied").getAsBoolean(), result::toString);
        assertEquals(SOURCE.replace(FIRST, FIRST_REPLACEMENT).replace(LAST, LAST_REPLACEMENT), contents.get());
        assertEquals(ExactTextReplacement.hash(contents.get()), result.get("resultHash").getAsString());
        assertShortPreviews(result);
    }

    @Test void aOneElementBatchStillReturnsPreviewsAndSingleEditKeepsPreview() {
        var contents = new AtomicReference<>(SOURCE);
        var one = List.of(EDITS.get(0));
        JsonObject batch = replace(contents, one, null, false, true);
        JsonObject single = replace(contents, one, null, false, false);
        assertFalse(batch.has("preview"));
        assertFalse(single.has("previews"));
        var previews = batch.getAsJsonArray("previews");
        assertEquals(1, previews.size());
        assertEquals(single.get("preview"), previews.get(0).getAsJsonObject().get("preview"));
    }

    private static JsonObject replace(AtomicReference<String> contents, String expectedHash, boolean apply) {
        return replace(contents, EDITS, expectedHash, apply, true);
    }

    private static JsonObject replace(AtomicReference<String> contents, List<ModuleFragment.Change> changes,
            String expectedHash, boolean apply, boolean batch) {
        IFile file = (IFile) Proxy.newProxyInstance(IFile.class.getClassLoader(), new Class<?>[] {IFile.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getContents" -> new ByteArrayInputStream(contents.get().getBytes(StandardCharsets.UTF_8));
                    case "setContents" -> {
                        contents.set(new String(((InputStream) args[0]).readAllBytes(), StandardCharsets.UTF_8));
                        yield null;
                    }
                    default -> throw new AssertionError(method.getName());
                });
        var result = new BslGateway.FragmentResult();
        result.modulePath = "src/CommonModules/Example/Module.bsl";
        new BslGateway().replaceFragmentsInFile(file, result, changes, expectedHash, apply, batch, source -> {
            var context = new BslGateway.BslContext();
            context.resource = new XtextResource();
            return context;
        });
        return new Gson().toJsonTree(result).getAsJsonObject();
    }

    private static void assertShortPreviews(JsonObject result) {
        assertFalse(result.toString().contains(DISTANT_LINE), "Response echoed unrelated module text");
        assertFalse(result.has("preview"));
        var previews = result.getAsJsonArray("previews");
        assertEquals(2, previews.size());
        assertEquals(1, previews.get(0).getAsJsonObject().get("line").getAsInt());
        assertEquals(4_004, previews.get(1).getAsJsonObject().get("line").getAsInt());
        assertTrue(previews.get(0).getAsJsonObject().get("preview").getAsString().contains(FIRST_REPLACEMENT));
        String lastPreview = previews.get(1).getAsJsonObject().get("preview").getAsString();
        assertTrue(lastPreview.contains("// Last replacement"));
        assertTrue(lastPreview.length() <= 1_204, "A large replacement still needs a short preview");
        assertTrue(lastPreview.endsWith(" ..."));
    }
}
