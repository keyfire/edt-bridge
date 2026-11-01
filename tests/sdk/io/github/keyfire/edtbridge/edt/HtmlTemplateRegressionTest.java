/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.*;
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
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.text.Document;

class HtmlTemplateRegressionTest {
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
        Runnable onExclusive = () -> {};
        Runnable onSavedCheck = () -> {};
        HtmlTemplateGateway.Target target = new HtmlTemplateGateway.Target("Demo", "CommonTemplate.Notice",
                "original-uuid", "HTMLDocument", "ru", List.of("ru", "en"),
                Map.of("ru", "src/CommonTemplates/Notice/ru/Template.html", "en", "src/CommonTemplates/Notice/en/Template.html"),
                null, null, null);
        public HtmlTemplateGateway.Target resolve(String project, String fqn, String language) { return target; }
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
            exclusiveCalls++; onExclusive.run(); operation.run();
        }
        public void write(HtmlTemplateGateway.Target target, byte[] bytes) {
            writes++; text = corruptWrite ? "unexpected writer content" : ExactTextReplacement.decode(bytes); stamp++;
        }
    }
}
