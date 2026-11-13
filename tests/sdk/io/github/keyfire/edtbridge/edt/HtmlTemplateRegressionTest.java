/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Proxy;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.operations.model.IEditingContext;
import com._1c.g5.v8.dt.htmldocument.model.HtmlDocumentFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import io.github.keyfire.edtbridge.core.ExactTextReplacement;
import org.junit.jupiter.api.Test;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.text.Document;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.part.FileEditorInput;

class HtmlTemplateRegressionTest {
    @Test void checkedDispatchRethrowsRuntimeFailureConsumedByDispatcher() {
        int[] calls = {0};
        HtmlTemplateGateway.dispatchBounded(Runnable::run, () -> calls[0]++, 1_000);
        assertEquals(1, calls[0]);
        var failure = new IllegalArgumentException("unsaved template editor");
        assertSame(failure, assertThrows(IllegalArgumentException.class, () ->
            HtmlTemplateGateway.dispatchBounded(operation -> {
                try { operation.run(); } catch (RuntimeException ignored) {}
            }, () -> { throw failure; }, 1_000)));
    }

    @Test void checkedDispatchRethrowsErrorConsumedByDispatcher() {
        var failure = new AssertionError("editor dispatch failed");
        assertSame(failure, assertThrows(AssertionError.class, () ->
            HtmlTemplateGateway.dispatchBounded(operation -> {
                try { operation.run(); } catch (Error ignored) {}
            }, () -> { throw failure; }, 1_000)));
    }

    @Test void unresponsiveUiThreadIsReportedBusyAndTheQueuedCheckNeverRuns() {
        Runnable[] queued = {null}; int[] calls = {0};
        assertThrows(HtmlTemplateGateway.BusyException.class,
                () -> HtmlTemplateGateway.dispatchBounded(operation -> queued[0] = operation, () -> calls[0]++, 50));
        queued[0].run();
        assertEquals(0, calls[0]);
    }

    @Test void startedUiCheckIsAwaitedPastTheDeadline() {
        int[] calls = {0};
        HtmlTemplateGateway.dispatchBounded(operation -> new Thread(operation).start(), () -> {
            try { Thread.sleep(200); } catch (InterruptedException ignored) { }
            calls[0]++;
        }, 20);
        assertEquals(1, calls[0]);
    }

    @Test void queuedExclusiveOperationPastTheDeadlineIsDroppedAsBusy() {
        int[] calls = {0};
        // EDT polls the monitor while another operation holds the project and drops the queued one on cancel.
        assertThrows(HtmlTemplateGateway.BusyException.class, () -> HtmlTemplateGateway.performBounded((run, monitor) -> {
            long until = System.nanoTime() + 2_000_000_000L;
            while (!monitor.isCanceled() && System.nanoTime() < until) Thread.onSpinWait();
            if (!monitor.isCanceled()) run.run();
        }, () -> calls[0]++, 30));
        assertEquals(0, calls[0]);
        HtmlTemplateGateway.performBounded((run, monitor) -> { run.run(); assertFalse(monitor.isCanceled()); },
                () -> calls[0]++, 30);
        assertEquals(1, calls[0]);
    }

    @Test void busyRefusalIsFlaggedAndWritesNothing() {
        var access = new MemoryAccess(); var gateway = new HtmlTemplateGateway(access);
        access.onExclusive = () -> { throw new HtmlTemplateGateway.BusyException("infobase update is waiting for an answer"); };
        var result = gateway.replace("Demo", "CommonTemplate.Notice", "ru", "replacement",
                ExactTextReplacement.hash(access.text), true, true);
        assertFalse(result.ok); assertTrue(result.busy, result.message); assertFalse(result.writeAttempted);
        assertEquals(0, access.writes); assertEquals("old\r\n", access.text);
    }

    @Test void swallowedExclusiveDirtyRefusalCannotReportSuccessOrWrite() {
        var access = new MemoryAccess(); var gateway = new HtmlTemplateGateway(access);
        access.swallowExclusiveFailure = true;
        access.onSavedCheck = () -> {
            if (access.savedChecks == 2) throw new IllegalArgumentException("unsaved template editor");
        };
        var result = gateway.replace("Demo", "CommonTemplate.Notice", "ru", "replacement",
                ExactTextReplacement.hash(access.text), true, true);
        assertFalse(result.ok, result.message); assertFalse(result.applied); assertFalse(result.verified);
        assertFalse(result.writeAttempted); assertEquals(0, access.writes); assertEquals("old\r\n", access.text);
        assertTrue(result.message.contains("unsaved template editor"), result.message);
    }

    @Test void skippedExclusiveCallbackCannotReportSuccessOrWrite() {
        var access = new MemoryAccess(); var gateway = new HtmlTemplateGateway(access);
        access.skipExclusiveCallback = true;
        var result = gateway.replace("Demo", "CommonTemplate.Notice", "ru", "replacement",
                ExactTextReplacement.hash(access.text), true, true);
        assertFalse(result.ok, result.message); assertFalse(result.applied); assertFalse(result.verified);
        assertFalse(result.writeAttempted); assertEquals(0, access.writes); assertEquals("old\r\n", access.text);
    }

    @Test void swallowedPostWriteVerificationFailureStillReportsPartialFailure() {
        var access = new MemoryAccess(); var gateway = new HtmlTemplateGateway(access);
        access.swallowExclusiveFailure = true; access.corruptWrite = true;
        var result = gateway.replace("Demo", "CommonTemplate.Notice", "ru", "replacement",
                ExactTextReplacement.hash(access.text), true, true);
        assertFalse(result.ok, result.message); assertTrue(result.applied); assertFalse(result.verified);
        assertTrue(result.writeAttempted); assertEquals(1, access.writes);
        assertTrue(result.message.contains("written but verification failed"), result.message);
    }

    @Test void fileEditorInputsWithoutFileAdaptersStillMatchOnlyTemplateFiles() throws Exception {
        Map<String, IFile> files = new LinkedHashMap<>();
        IProject project = (IProject) Proxy.newProxyInstance(IProject.class.getClassLoader(),
                new Class<?>[] {IProject.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getFile" -> files.get(arguments[0].toString());
                    case "getName" -> "Demo";
                    default -> null;
                });
        String ru = "src/CommonTemplates/Notice/ru/Template.html";
        String en = "src/CommonTemplates/Notice/en/Template.html";
        String metadata = "src/CommonTemplates/Notice/Notice.mdo";
        String document = "src/CommonTemplates/Notice/Template.htmldocument";
        for (String path : List.of(ru, en, metadata, document)) files.put(path, editorFile(project, path));
        var target = new HtmlTemplateGateway.Target("Demo", "CommonTemplate.Notice", "original-uuid", "HTMLDocument",
                "ru", List.of("ru", "en"), Map.of("ru", ru, "en", en), files.get(ru), files.get(metadata), files.get(document));
        for (String path : List.of(ru, en, metadata, document)) {
            assertTrue(matchesEditorFile(new FileEditorInput(files.get(path)), target),
                    "A native file editor must match its template file without IFile adaptation: " + path);
        }
        assertFalse(matchesEditorFile(new FileEditorInput(editorFile(project,
                "src/CommonTemplates/Other/ru/Template.html")), target));
        assertFalse(matchesEditorFile(null, target));
        IEditorInput adapted = (IEditorInput) Proxy.newProxyInstance(IEditorInput.class.getClassLoader(),
                new Class<?>[] {IEditorInput.class}, (proxy, method, arguments) ->
                    method.getName().equals("getAdapter") && arguments[0] == IFile.class ? files.get(ru) : null);
        assertTrue(matchesEditorFile(adapted, target));
    }

    @Test void resolvesCommonAndIndexedNestedTemplatesAndRefusesNonTemplateObjects() {
        var common = MdClassFactory.eINSTANCE.createCommonTemplate(); common.setName("Notice");
        var owner = MdClassFactory.eINSTANCE.createDataProcessor(); owner.setName("Demo");
        var nested = MdClassFactory.eINSTANCE.createTemplate(); nested.setName("Notice");
        var indexed = MdClassFactory.eINSTANCE.createTemplate(); indexed.setName("Indexed");
        owner.getTemplates().add(nested);
        IBmTransaction transaction = (IBmTransaction) Proxy.newProxyInstance(IBmTransaction.class.getClassLoader(),
                new Class<?>[] {IBmTransaction.class}, (proxy, method, arguments) -> {
                    if (!method.getName().equals("getTopObjectByFqn")) return null;
                    return switch ((String) arguments[0]) {
                        case "CommonTemplate.Notice" -> common;
                        case "DataProcessor.Demo" -> owner;
                        case "DataProcessor.Demo.Template.Notice" -> nested;
                        case "DataProcessor.Demo.Template.Indexed" -> indexed;
                        default -> null;
                    };
                });
        assertSame(common, HtmlTemplateGateway.resolveTemplate(transaction, "CommonTemplate.Notice"));
        assertSame(nested, HtmlTemplateGateway.resolveTemplate(transaction, "DataProcessor.Demo.Template.Notice"));
        assertSame(indexed, HtmlTemplateGateway.resolveTemplate(transaction, "DataProcessor.Demo.Template.Indexed"));
        assertThrows(IllegalArgumentException.class, () -> HtmlTemplateGateway.resolveTemplate(transaction, "DataProcessor.Demo"));
        assertThrows(IllegalArgumentException.class, () -> HtmlTemplateGateway.resolveTemplate(transaction, "CommonTemplate.Missing"));
    }

    @Test void dirtyOrStaleBuffersAndApiContextsRefuseEvenWhenSavedBytesAreUnchanged() {
        boolean[] dirty = {false}, synchronizedState = {true}, disposed = {false};
        var document = new Document("<p>Saved</p>\r\n");
        ITextFileBuffer buffer = (ITextFileBuffer) Proxy.newProxyInstance(ITextFileBuffer.class.getClassLoader(),
                new Class<?>[] {ITextFileBuffer.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "isDirty" -> dirty[0];
                    case "isSynchronized" -> synchronizedState[0];
                    case "getDocument" -> document;
                    case "getStatus" -> Status.OK_STATUS;
                    default -> null;
                });
        assertNull(HtmlTemplateGateway.bufferProblem(buffer, "<p>Saved</p>\r\n"));
        assertNull(HtmlTemplateGateway.bufferProblem(buffer, "\ufeff<p>Saved</p>\r\n"));
        dirty[0] = true;
        assertNotNull(HtmlTemplateGateway.bufferProblem(buffer, "<p>Saved</p>\r\n"));
        dirty[0] = false; synchronizedState[0] = false;
        assertNotNull(HtmlTemplateGateway.bufferProblem(buffer, "<p>Saved</p>\r\n"));
        synchronizedState[0] = true; document.set("<p>New buffer</p>\r\n");
        assertNotNull(HtmlTemplateGateway.bufferProblem(buffer, "<p>Saved</p>\r\n"));
        IEditingContext context = (IEditingContext) Proxy.newProxyInstance(IEditingContext.class.getClassLoader(),
                new Class<?>[] {IEditingContext.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "isDirty" -> dirty[0];
                    case "isDisposed" -> disposed[0];
                    default -> throw new AssertionError("The guard must not save or mutate a context");
                });
        assertDoesNotThrow(() -> HtmlTemplateGateway.requireContextSaved(context));
        dirty[0] = true;
        assertThrows(IllegalArgumentException.class, () -> HtmlTemplateGateway.requireContextSaved(context));
        dirty[0] = false; disposed[0] = true;
        assertThrows(IllegalArgumentException.class, () -> HtmlTemplateGateway.requireContextSaved(context));
    }

    @Test void selectsOneExistingLocaleWithoutChangingOtherPagesOrMetadata() {
        var template = MdClassFactory.eINSTANCE.createCommonTemplate();
        template.setName("Notice"); template.setComment("Keep metadata");
        template.setTemplateType(TemplateType.HTML_DOCUMENT);
        var document = HtmlDocumentFactory.eINSTANCE.createHtmlDocument();
        var ru = HtmlDocumentFactory.eINSTANCE.createHtmlDocumentPage(); ru.setLang("ru");
        var en = HtmlDocumentFactory.eINSTANCE.createHtmlDocumentPage(); en.setLang("en");
        document.getPages().add(ru); document.getPages().add(en); template.setTemplate(document);
        assertSame(en, HtmlTemplateGateway.selectPage(template, "en"));
        assertEquals(List.of(ru, en), document.getPages());
        assertEquals("Keep metadata", template.getComment());
        assertThrows(IllegalArgumentException.class, () -> HtmlTemplateGateway.selectPage(template, "de"));
        assertThrows(IllegalArgumentException.class, () -> HtmlTemplateGateway.selectPage(template, "EN"));
        assertThrows(IllegalArgumentException.class, () -> HtmlTemplateGateway.selectPage(template, ""));
        var duplicate = HtmlDocumentFactory.eINSTANCE.createHtmlDocumentPage(); duplicate.setLang("en");
        document.getPages().add(duplicate);
        assertThrows(IllegalArgumentException.class, () -> HtmlTemplateGateway.selectPage(template, "en"));
        duplicate.setLang("EN");
        assertThrows(IllegalArgumentException.class, () -> HtmlTemplateGateway.selectPage(template, "ru"));
        template.setTemplateType(TemplateType.TEXT_DOCUMENT);
        assertThrows(IllegalArgumentException.class, () -> HtmlTemplateGateway.selectPage(template, "ru"));
    }

    @Test void previewAndNoOpNeverWriteAndEmptyTextRemainsDistinctFromMissing() {
        var access = new MemoryAccess();
        var gateway = new HtmlTemplateGateway(access);
        var preview = gateway.replace("Demo", "CommonTemplate.Notice", "ru", "", null, false, false);
        assertTrue(preview.ok, preview.message); assertTrue(preview.applyPending);
        assertEquals("old\r\n", preview.oldText); assertEquals("", preview.newText);
        assertEquals("old\r\n", access.text); assertEquals(0, access.writes);
        var missing = gateway.replace("Demo", "CommonTemplate.Notice", "ru", null, null, false, false);
        assertFalse(missing.ok); assertEquals(0, access.writes);
        var noOp = gateway.replace("Demo", "CommonTemplate.Notice", "ru", access.text,
                ExactTextReplacement.hash(access.text), true, true);
        assertTrue(noOp.ok, noOp.message); assertTrue(noOp.unchanged);
        assertEquals(0, access.writes); assertEquals(0, access.exclusiveCalls);
    }

    @Test void applyRequiresCurrentHashAndForceAndRereadsInsideExclusiveOperation() {
        var access = new MemoryAccess(); var gateway = new HtmlTemplateGateway(access);
        assertFalse(gateway.replace("Demo", "CommonTemplate.Notice", "ru", "new", null, true, true).ok);
        assertFalse(gateway.replace("Demo", "CommonTemplate.Notice", "ru", "new",
                ExactTextReplacement.hash(access.text), true, false).ok);
        assertFalse(gateway.replace("Demo", "CommonTemplate.Notice", "ru", "new",
                ExactTextReplacement.hash("stale"), true, true).ok);
        assertEquals(0, access.writes);
        String current = ExactTextReplacement.hash(access.text);
        access.onExclusive = () -> { access.text = "newer user source"; access.stamp++; };
        var result = gateway.replace("Demo", "CommonTemplate.Notice", "ru", "replacement", current, true, true);
        assertFalse(result.ok); assertFalse(result.applied); assertEquals(0, access.writes);
        assertEquals("newer user source", access.text);
    }

    @Test void applyRefusesDirtyEditorAndReboundIdentityEvenWithIdenticalText() {
        var access = new MemoryAccess(); var gateway = new HtmlTemplateGateway(access);
        String hash = ExactTextReplacement.hash(access.text);
        access.dirty = true;
        assertFalse(gateway.replace("Demo", "CommonTemplate.Notice", "ru", "new", hash, true, true).ok);
        assertEquals(0, access.writes);
        access.dirty = false;
        access.onExclusive = () -> access.target = new HtmlTemplateGateway.Target("Demo", "CommonTemplate.Notice",
                "changed-uuid", "HTMLDocument", "ru", List.of("ru", "en"),
                Map.of("ru", "src/CommonTemplates/Notice/ru/Template.html", "en", "src/CommonTemplates/Notice/en/Template.html"),
                null, null, null);
        var rebound = gateway.replace("Demo", "CommonTemplate.Notice", "ru", "new", hash, true, true);
        assertFalse(rebound.ok); assertFalse(rebound.applied); assertEquals(0, access.writes);
    }

    @Test void appliesExactTextVerifiesSavedSourceAndReportsPostWriteFailureAsPartial() {
        var access = new MemoryAccess(); var gateway = new HtmlTemplateGateway(access);
        String replacement = "\ufeff \r\n<p>Привет &amp; bye</p>\n";
        var result = gateway.replace("Demo", "CommonTemplate.Notice", "ru", replacement,
                ExactTextReplacement.hash(access.text), true, true);
        assertTrue(result.ok, result.message); assertTrue(result.applied); assertTrue(result.verified);
        assertEquals(replacement, access.text); assertEquals(1, access.writes);
        assertEquals(List.of("ru", "en"), result.availableLanguages);
        assertEquals("<p>Other language</p>\r\n", access.otherLanguage);
        access.corruptWrite = true;
        var partial = gateway.replace("Demo", "CommonTemplate.Notice", "ru", "next",
                ExactTextReplacement.hash(access.text), true, true);
        assertFalse(partial.ok); assertTrue(partial.applied); assertFalse(partial.verified);
    }

    @Test void changedLocaleMappingAndFileRevisionInvalidateThePreviewWithoutAWrite() {
        var access = new MemoryAccess(); var gateway = new HtmlTemplateGateway(access);
        String hash = ExactTextReplacement.hash(access.text);
        access.onExclusive = () -> access.stamp++;
        var revision = gateway.replace("Demo", "CommonTemplate.Notice", "ru", "new", hash, true, true);
        assertFalse(revision.ok); assertFalse(revision.applied); assertEquals(0, access.writes);
        access.onExclusive = () -> access.target = new HtmlTemplateGateway.Target("Demo", "CommonTemplate.Notice",
                "original-uuid", "HTMLDocument", "ru", List.of("ru", "en"),
                Map.of("ru", "src/CommonTemplates/Notice/en/Template.html", "en", "src/CommonTemplates/Notice/ru/Template.html"),
                null, null, null);
        var mapping = gateway.replace("Demo", "CommonTemplate.Notice", "ru", "new", hash, true, true);
        assertFalse(mapping.ok); assertFalse(mapping.applied); assertEquals(0, access.writes);
    }

    @Test void readReturnsExactSavedSourceAndRefusesDirtySource() {
        var access = new MemoryAccess(); var gateway = new HtmlTemplateGateway(access);
        access.text = "\ufeff<script>const x = 'a';</script>\r\n";
        var result = gateway.read("Demo", "CommonTemplate.Notice", "ru");
        assertTrue(result.ok, result.message); assertEquals(access.text, result.text);
        assertEquals(ExactTextReplacement.hash(access.text), result.sourceHash);
        access.dirty = true;
        assertFalse(gateway.read("Demo", "CommonTemplate.Notice", "ru").ok);
        assertEquals(0, access.writes);
    }

    @Test void readOnlySourceCanBeReadButCannotProduceAValidReplacementPlan() {
        var access = new MemoryAccess(); var gateway = new HtmlTemplateGateway(access);
        access.readOnly = true;
        assertTrue(gateway.read("Demo", "CommonTemplate.Notice", "ru").ok);
        var result = gateway.replace("Demo", "CommonTemplate.Notice", "ru", "new", null, false, false);
        assertFalse(result.ok); assertFalse(result.valid); assertEquals(0, access.writes);
    }

    @Test void sourceChangeDuringEditorValidationCannotBeOverwrittenOrReturnedAsCurrent() {
        for (int changeAt : List.of(1, 2)) {
            var access = new MemoryAccess(); var gateway = new HtmlTemplateGateway(access);
            String expectedHash = ExactTextReplacement.hash(access.text);
            access.onSavedCheck = () -> {
                if (access.savedChecks == changeAt) { access.text = "newer source during editor validation"; access.stamp++; }
            };
            var result = gateway.replace("Demo", "CommonTemplate.Notice", "ru", "replacement", expectedHash, true, true);
            assertFalse(result.ok); assertFalse(result.applied); assertEquals(0, access.writes);
            assertEquals("newer source during editor validation", access.text);
        }
        var access = new MemoryAccess(); var gateway = new HtmlTemplateGateway(access);
        access.onSavedCheck = () -> { access.text = "new saved source"; access.stamp++; };
        assertFalse(gateway.read("Demo", "CommonTemplate.Notice", "ru").ok);
        assertEquals("new saved source", access.text); assertEquals(0, access.writes);
    }

    @Test void schemaFileWrittenAfterTheTemplateIsAwaitedByTheFirstResolve() {
        HtmlTemplateGateway.noteCreated("Demo", "ExternalReport.Demo.Template.Main");
        var access = new MemoryAccess();
        // EDT answers edt_add_template before its export writes Template.dcs; the model may wrap the refusal.
        access.onResolve = call -> call <= 2 ? new RuntimeException("bm task failed",
                new HtmlTemplateGateway.ContentFileMissingException("existing template file is unavailable in the requested project", null))
                : null;
        var gateway = new HtmlTemplateGateway(access, 5_000);
        var read = gateway.read("Demo", "ExternalReport.Demo.Template.Main", HtmlTemplateGateway.SCHEMA);
        assertTrue(read.ok, read.message);
        assertEquals(2, access.refreshes);
        access.resolves = 0; access.refreshes = 0;
        var preview = gateway.replace("Demo", "ExternalReport.Demo.Template.Main", HtmlTemplateGateway.SCHEMA,
                "<schema/>", null, false, false);
        assertTrue(preview.ok, preview.message);
        assertEquals(2, access.refreshes);
        assertEquals(0, access.writes);
    }

    @Test void schemaFileThatNeverAppearsIsRefusedAfterTheBoundedWait() {
        HtmlTemplateGateway.noteCreated("Demo", "ExternalReport.Demo.Template.Main");
        var access = new MemoryAccess();
        access.onResolve = call -> new HtmlTemplateGateway.ContentFileMissingException(
                "existing template file is unavailable in the requested project", null);
        long started = System.nanoTime();
        var result = new HtmlTemplateGateway(access, 300).replace("Demo", "ExternalReport.Demo.Template.Main",
                HtmlTemplateGateway.SCHEMA, "<schema/>", null, false, false);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertFalse(result.ok);
        assertTrue(result.message.contains("unavailable in the requested project"), result.message);
        assertTrue(result.message.contains("ms for EDT to write it"), result.message);
        assertTrue(elapsedMs >= 300 && elapsedMs < 3_000, "waited " + elapsedMs + " ms");
        assertTrue(access.resolves > 1);
        assertEquals(0, access.writes);
    }

    @Test void missingFileOfATemplateNotCreatedJustNowIsRefusedWithoutWaiting() {
        var access = new MemoryAccess();
        access.onResolve = call -> new HtmlTemplateGateway.ContentFileMissingException(
                "existing template file is unavailable in the requested project", null);
        long started = System.nanoTime();
        var result = new HtmlTemplateGateway(access, 60_000).read("Demo", "ExternalReport.Demo.Template.Older",
                HtmlTemplateGateway.SCHEMA);
        assertFalse(result.ok);
        assertEquals(1, access.resolves);
        assertEquals(0, access.refreshes);
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000);
        assertFalse(HtmlTemplateGateway.recentlyCreated("Demo", "ExternalReport.Demo.Template.Older"));
        HtmlTemplateGateway.noteCreated("Demo", "ExternalReport.Demo.Template.Older");
        assertTrue(HtmlTemplateGateway.recentlyCreated("Demo", "externalreport.demo.template.older"));
    }

    @Test void missingTemplateAndOtherRefusalsAreReturnedWithoutWaiting() {
        var access = new MemoryAccess();
        access.onResolve = call -> new IllegalArgumentException("existing common or nested template not found: "
                + "ExternalReport.Demo.Template.Missing");
        var gateway = new HtmlTemplateGateway(access, 60_000);
        long started = System.nanoTime();
        var result = gateway.replace("Demo", "ExternalReport.Demo.Template.Missing", HtmlTemplateGateway.SCHEMA,
                "<schema/>", null, false, false);
        assertFalse(result.ok);
        assertTrue(result.message.contains("template not found"), result.message);
        assertEquals(1, access.resolves);
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000);
        // An HTML locale has no export lag to wait for: its missing page is refused at once as well.
        access.resolves = 0;
        access.onResolve = call -> new HtmlTemplateGateway.ContentFileMissingException(
                "existing template file is unavailable in the requested project", null);
        assertFalse(gateway.read("Demo", "CommonTemplate.Notice", "ru").ok);
        assertEquals(1, access.resolves);
        assertEquals(0, access.refreshes);
    }

    private static IFile editorFile(IProject project, String path) {
        return (IFile) Proxy.newProxyInstance(IFile.class.getClassLoader(), new Class<?>[] {IFile.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getProject" -> project;
                    case "getProjectRelativePath" -> new Path(path);
                    case "getFullPath" -> new Path("/Demo/" + path);
                    case "getName" -> new Path(path).lastSegment();
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> path;
                    default -> null;
                });
    }

    private static boolean matchesEditorFile(IEditorInput input, HtmlTemplateGateway.Target target) throws Exception {
        var access = Class.forName(HtmlTemplateGateway.class.getName() + "$EdtAccess");
        var matches = access.getDeclaredMethod("matchesFile", IEditorInput.class, HtmlTemplateGateway.Target.class);
        matches.setAccessible(true);
        return (Boolean) matches.invoke(null, input, target);
    }

    private static final class MemoryAccess implements HtmlTemplateGateway.Access {
        String text = "old\r\n";
        final String otherLanguage = "<p>Other language</p>\r\n";
        long stamp = 1;
        int writes;
        int exclusiveCalls;
        int savedChecks;
        boolean dirty;
        boolean corruptWrite;
        boolean readOnly;
        boolean swallowExclusiveFailure;
        boolean skipExclusiveCallback;
        int resolves;
        int refreshes;
        Runnable onExclusive = () -> {};
        Runnable onSavedCheck = () -> {};
        /** The failure of the n-th resolve (1-based), or null to resolve. */
        java.util.function.IntFunction<RuntimeException> onResolve = call -> null;
        HtmlTemplateGateway.Target target = new HtmlTemplateGateway.Target("Demo", "CommonTemplate.Notice",
                "original-uuid", "HTMLDocument", "ru", List.of("ru", "en"),
                Map.of("ru", "src/CommonTemplates/Notice/ru/Template.html", "en", "src/CommonTemplates/Notice/en/Template.html"),
                null, null, null);
        public HtmlTemplateGateway.Target resolve(String project, String fqn, String language) {
            RuntimeException failure = onResolve.apply(++resolves);
            if (failure != null) throw failure;
            return target;
        }
        public void refresh(IFile file) { refreshes++; }
        public HtmlTemplateGateway.Source read(HtmlTemplateGateway.Target target) {
            return new HtmlTemplateGateway.Source(text, stamp, "UTF-8");
        }
        public void requireSaved(HtmlTemplateGateway.Target target, String source) {
            if (dirty) throw new IllegalArgumentException("unsaved template editor");
            savedChecks++; onSavedCheck.run();
        }
        public void requireWritable(HtmlTemplateGateway.Target target) {
            if (readOnly) throw new IllegalArgumentException("template file is read-only");
        }
        public void exclusive(HtmlTemplateGateway.Target target, Runnable operation) {
            exclusiveCalls++; onExclusive.run();
            if (skipExclusiveCallback) return;
            try { operation.run(); } catch (RuntimeException failure) {
                if (!swallowExclusiveFailure) throw failure;
            }
        }
        public void write(HtmlTemplateGateway.Target target, byte[] bytes) {
            writes++; text = corruptWrite ? "unexpected writer content" : ExactTextReplacement.decode(bytes); stamp++;
        }
    }
}
