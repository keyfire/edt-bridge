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
import java.util.List;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.common.util.URI;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.xtext.nodemodel.impl.NodeModelBuilder;
import com._1c.g5.v8.dt.bsl.model.BslFactory;
import org.junit.jupiter.api.Test;

class BslRefactorRegressionTest {
    @Test void summaryReportsEachAffectedFileAndLeafEditWithoutDuplicatingContainers() {
        TextFileChange declaration = change("/Demo/src/CommonModules/Api/Module.bsl", 2);
        TextFileChange caller = change("/Demo/src/CommonModules/Client/Module.bsl", 3);
        TextFileChange handler = change("/Demo/src/CommonForms/Main/Form.form", 1);
        CompositeChange all = new CompositeChange("Rename", new org.eclipse.ltk.core.refactoring.Change[] {
            declaration, new CompositeChange("References", new org.eclipse.ltk.core.refactoring.Change[] {caller, handler})});
        var result = new BslRefactorGateway.Result();
        BslRefactorGateway.summarize(all, result);
        assertEquals(3, result.affectedFileCount);
        assertEquals(3, result.changeCount);
        assertEquals(6, result.editCount);
        assertEquals(0, result.unknownChangeCount);
        assertEquals("/Demo/src/CommonForms/Main/Form.form", result.affectedFiles.get(2).path);
        assertEquals(1, result.affectedFiles.get(2).editCount);
    }
    @Test void summaryIgnoresDisabledChangesAndCombinesRepeatedFileChanges() {
        var first = change("/Demo/Module.bsl", 1);
        var second = change("/Demo/Module.bsl", 2);
        var ignored = change("/Demo/Unused.bsl", 4);
        ignored.setEnabled(false);
        var result = new BslRefactorGateway.Result();
        BslRefactorGateway.summarize(new CompositeChange("Rename", new org.eclipse.ltk.core.refactoring.Change[] {
            first, second, ignored}), result);
        assertEquals(1, result.affectedFileCount);
        assertEquals(2, result.affectedFiles.get(0).changeCount);
        assertEquals(3, result.editCount);
    }
    @Test void methodResolutionRefusesMissingAndCaseInsensitiveDuplicates() {
        var module = BslFactory.eINSTANCE.createModule();
        var first = BslFactory.eINSTANCE.createProcedure(); first.setName("Run");
        module.getMethods().add(first);
        assertSame(first, BslRefactorGateway.uniqueMethod(module, "run"));
        assertThrows(IllegalArgumentException.class, () -> BslRefactorGateway.uniqueMethod(module, "Missing"));
        var duplicateModule = BslFactory.eINSTANCE.createModule();
        var one = BslFactory.eINSTANCE.createProcedure(); one.setName("Run");
        var two = BslFactory.eINSTANCE.createProcedure(); two.setName("RUN");
        duplicateModule.getMethods().add(one);
        duplicateModule.getMethods().add(two);
        assertThrows(IllegalArgumentException.class, () -> BslRefactorGateway.uniqueMethod(duplicateModule, "Run"));
    }
    @Test void rangeKeepsAdjacentDocumentationPragmasAndTrailingCommentButLeavesSeparatedHeader() {
        String text = "// Module header\r\n\r\n// Method docs\r\n&AtClient\r\nProcedure Run()\r\nEndProcedure // tail\r\n";
        var method = BslFactory.eINSTANCE.createProcedure(); method.setName("Run");
        var pragma = BslFactory.eINSTANCE.createPragma(); method.getPragmas().add(pragma);
        NodeModelBuilder builder = new NodeModelBuilder();
        var root = builder.newRootNode(text);
        var pn = builder.newCompositeNode(null, 0, root);
        builder.newLeafNode(text.indexOf("&AtClient"), "&AtClient".length(), null, false, null, pn);
        builder.associateWithSemanticElement(pn, pragma);
        var mn = builder.newCompositeNode(null, 0, root);
        int start = text.indexOf("Procedure Run"); int end = text.indexOf("EndProcedure") + "EndProcedure".length();
        builder.newLeafNode(start, end - start, null, false, null, mn);
        builder.associateWithSemanticElement(mn, method);
        int[] range = BslGateway.methodRange(method, text);
        assertEquals("// Method docs\r\n&AtClient\r\nProcedure Run()\r\nEndProcedure // tail", text.substring(range[0], range[1]));
        assertTrue(text.substring(0, range[0]).contains("// Module header"));
    }
    @Test void regionTokensBuildLexicalPathsDespiteSequentialAstContainment() {
        String text = "#Region Parent\n#Region Child\n#EndRegion\n#EndRegion\n#Region Other\n#Region Child\n#EndRegion\n#EndRegion\n";
        var module = BslFactory.eINSTANCE.createModule();
        NodeModelBuilder builder = new NodeModelBuilder();
        var root = builder.newRootNode(text);
        int offset = 0;
        for (String line : text.split("\n")) {
            int space = line.indexOf(' ');
            int length = space < 0 ? line.length() : space;
            builder.newLeafNode(offset, length, null, false, null, root);
            if (space >= 0) {
                builder.newLeafNode(offset + space, 1, null, true, null, root);
                builder.newLeafNode(offset + space + 1, line.length() - space - 1, null, false, null, root);
            }
            builder.newLeafNode(offset + line.length(), 1, null, true, null, root);
            offset += line.length() + 1;
        }
        builder.associateWithSemanticElement(root, module);
        var regions = BslGateway.regionRanges(module, text);
        assertEquals(4, regions.size());
        var point = io.github.keyfire.edtbridge.core.BslPlacement.choose(text.length(), regions, List.of(),
                0, text.length(), "Other/Child", null, null);
        assertEquals(text.lastIndexOf("#EndRegion\n#EndRegion"), point.offset());
    }
    @Test void filePreviewNormalizesPlatformUrisAndCountsNoEmptyContainerAsAnEdit() {
        assertEquals("/Demo/src/Module.bsl", BslRefactorGateway.resourcePath(URI.createURI("platform:/resource/Demo/src/Module.bsl#method")));
        assertEquals(0, BslRefactorGateway.countEdits(new MultiTextEdit()));
    }
    @Test void serverScopeUsesParsedLiteralWithEnglishSourceAndRefusesMultipleTargets() throws Exception {
        var module = BslFactory.eINSTANCE.createModule();
        var first = serverBranch("#If Server Then\n#EndIf");
        module.getPreprocessors().add(first);
        var resolver = BslGateway.class.getDeclaredMethod("findServerIf", com._1c.g5.v8.dt.bsl.model.Module.class);
        resolver.setAccessible(true);
        assertSame(first, resolver.invoke(null, module));
        module.getPreprocessors().add(serverBranch("#If Server Then\n#EndIf"));
        var failure = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> resolver.invoke(null, module));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }
    private static com._1c.g5.v8.dt.bsl.model.IfPreprocessor serverBranch(String source) {
        var branch = BslFactory.eINSTANCE.createIfPreprocessor();
        var part = BslFactory.eINSTANCE.createPreprocessorIfConditional();
        var literal = BslFactory.eINSTANCE.createPreprocessorLiteralExpression();
        literal.setTypeLiteral(com._1c.g5.v8.dt.bsl.model.PreprocessorLiteralExpressionType.SERVER);
        part.setPredicate(literal);
        branch.setIfPart(part);
        NodeModelBuilder builder = new NodeModelBuilder();
        var root = builder.newRootNode(source);
        builder.newLeafNode(0, source.length(), null, false, null, root);
        builder.associateWithSemanticElement(root, branch);
        return branch;
    }
    @Test void nativeBmTextChangeExposesEditsWithoutBeingATextChange() {
        var module = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createCommonModule();
        module.setName("Api");
        var resource = new org.eclipse.emf.ecore.resource.impl.ResourceImpl(URI.createURI("platform:/resource/Demo/src/CommonModules/Api/Api.mdo"));
        resource.getContents().add(module);
        MultiTextEdit edits = new MultiTextEdit();
        edits.addChild(new ReplaceEdit(0, 3, "New"));
        edits.addChild(new ReplaceEdit(8, 3, "New"));
        var change = new com._1c.g5.v8.dt.refactoring.core.ltk.BmObjectTextContentChange<>("Native text",
                module, module.eClass().getEStructuralFeature("comment"), edits);
        var result = new BslRefactorGateway.Result();
        BslRefactorGateway.summarize(change, result);
        assertEquals(2, result.editCount);
        assertEquals(2, result.affectedFiles.get(0).editCount);
    }
    @Test void nonTextNativeModelChangeDoesNotClaimZeroTextReplacements() {
        var change = new org.eclipse.ltk.core.refactoring.Change() {
            public String getName() { return "Update form handler"; }
            public void initializeValidationData(org.eclipse.core.runtime.IProgressMonitor monitor) {}
            public org.eclipse.ltk.core.refactoring.RefactoringStatus isValid(org.eclipse.core.runtime.IProgressMonitor monitor) { return new org.eclipse.ltk.core.refactoring.RefactoringStatus(); }
            public org.eclipse.ltk.core.refactoring.Change perform(org.eclipse.core.runtime.IProgressMonitor monitor) { throw new AssertionError("Preview must never execute changes"); }
            public Object getModifiedElement() { return URI.createURI("platform:/resource/Demo/src/CommonForms/Main/Form.form"); }
        };
        var result = new BslRefactorGateway.Result();
        BslRefactorGateway.summarize(change, result);
        assertEquals(1, result.changeCount);
        assertNull(result.editCount);
        assertNull(result.affectedFiles.get(0).editCount);
    }
    @Test void metadataPreviewPreservesNativeFilesAndMarksOpaqueItemsAsPartial() {
        var nativeItem = item(change("/Demo/src/CommonModules/Api/Module.bsl", 3), "Native references", true);
        var opaque = item(null, "Rename metadata identity", true);
        var ignored = item(null, "Unchecked optional item", false);
        var preview = BslRefactorGateway.previewItems(List.of(nativeItem, opaque, ignored));
        BslRefactorGateway.includeTargetPath(preview, "/Demo/src/CommonModules/Api/Api.mdo");
        assertEquals(2, preview.selectedItemCount);
        assertEquals(2, preview.affectedFileCount);
        assertEquals(1, preview.unmappedItemCount);
        assertFalse(preview.coverageComplete);
        assertEquals(3, preview.nativeTextEditCount);
        assertNull(preview.editCount);
        assertEquals("/Demo/src/CommonModules/Api/Module.bsl", preview.affectedFiles.get(0).path);
        assertEquals("/Demo/src/CommonModules/Api/Api.mdo", preview.affectedFiles.get(1).path);
        assertNull(preview.affectedFiles.get(1).editCount);
    }
    @Test void metadataPreviewFailureDoesNotClaimCompleteCoverageOrZeroEdits() {
        var broken = new com._1c.g5.v8.dt.refactoring.core.INativeChangeRefactoringItem() {
            public org.eclipse.ltk.core.refactoring.Change getNativeChange() { throw new IllegalStateException("Native preview unavailable"); }
            public String getName() { return "Unavailable native changes"; }
            public boolean isOptional() { return false; }
            public boolean isChecked() { return true; }
            public void setChecked(boolean checked) {}
        };
        var preview = BslRefactorGateway.previewItems(List.of(broken, item(change("/Demo/Module.bsl", 2), "Known", true)));
        assertFalse(preview.coverageComplete);
        assertFalse(preview.editCountComplete);
        assertNull(preview.editCount);
        assertEquals(1, preview.unmappedItemCount);
        assertEquals(2, preview.nativeTextEditCount);
        assertEquals(1, preview.affectedFileCount);
    }
    @Test void targetPathWithoutNativeOperationsIsKnownButNotFullChangeCoverage() {
        var preview = BslRefactorGateway.previewItems(List.of());
        BslRefactorGateway.includeTargetPath(preview, "/Demo/src/CommonModules/Api/Api.mdo");
        assertEquals(1, preview.affectedFileCount);
        assertFalse(preview.coverageComplete);
        assertFalse(preview.affectedFiles.get(0).changeCountComplete);
        assertNull(preview.editCount);
    }
    private static com._1c.g5.v8.dt.refactoring.core.IRefactoringItem item(
            org.eclipse.ltk.core.refactoring.Change change, String name, boolean selected) {
        Class<?> type = change == null ? com._1c.g5.v8.dt.refactoring.core.IRefactoringItem.class
                : com._1c.g5.v8.dt.refactoring.core.INativeChangeRefactoringItem.class;
        return (com._1c.g5.v8.dt.refactoring.core.IRefactoringItem) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "isChecked" -> selected;
                    case "isOptional" -> false;
                    case "getNativeChange" -> change;
                    default -> null;
                });
    }
    @Test void firstMethodMayInitializeOnlyAnExistingTypedModuleSlot() {
        var common = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createCommonModule();
        assertTrue(BslGateway.isEmptyModuleSlot(common, "Module.bsl"));
        var catalog = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createCatalog();
        assertTrue(BslGateway.isEmptyModuleSlot(catalog, "ObjectModule.bsl"));
        assertTrue(BslGateway.isEmptyModuleSlot(catalog, "ManagerModule.bsl"));
        assertFalse(BslGateway.isEmptyModuleSlot(catalog, "Module.bsl"));
        assertFalse(BslGateway.isEmptyModuleSlot(common, "UnknownModule.bsl"));
        assertFalse(BslGateway.isEmptyModuleSlot(null, "Module.bsl"));
        assertFalse(BslGateway.isEmptyModuleSlot(common, "../Module.bsl"));
        assertFalse(BslGateway.isEmptyModuleSlot(common, "Module.txt"));
    }
    @Test void firstMethodRefusesToReplaceModuleContentPresentOnlyInTheModel() {
        var owner = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createCommonModule();
        var module = BslFactory.eINSTANCE.createModule();
        owner.setModule(module);
        assertTrue(BslGateway.isEmptyModuleSlot(owner, "Module.bsl"));
        module.getMethods().add(BslFactory.eINSTANCE.createProcedure());
        assertFalse(BslGateway.isEmptyModuleSlot(owner, "Module.bsl"));
        module.getMethods().clear();
        NodeModelBuilder builder = new NodeModelBuilder();
        var root = builder.newRootNode("// Preserve existing module header");
        builder.newLeafNode(0, "// Preserve existing module header".length(), null, true, null, root);
        builder.associateWithSemanticElement(root, module);
        assertFalse(BslGateway.isEmptyModuleSlot(owner, "Module.bsl"));
    }
    @Test void moduleInitializationRequiresAnActualBmOwnerRatherThanAPathGuess() throws Exception {
        var owner = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createCommonModule();
        owner.setName("Api");
        var transaction = (com._1c.g5.v8.bm.core.IBmTransaction) Proxy.newProxyInstance(
                com._1c.g5.v8.bm.core.IBmTransaction.class.getClassLoader(),
                new Class<?>[] {com._1c.g5.v8.bm.core.IBmTransaction.class},
                (proxy, method, args) -> method.getName().equals("getTopObjectByFqn")
                        && "CommonModule.Api".equals(args[0]) ? owner : null);
        var lookup = BslGateway.class.getDeclaredMethod("moduleOwner", com._1c.g5.v8.bm.core.IBmTransaction.class, String.class);
        lookup.setAccessible(true);
        assertSame(owner, lookup.invoke(null, transaction, "CommonModule.Api"));
        assertNull(lookup.invoke(null, transaction, "CommonModule.Missing"));
        assertNull(lookup.invoke(null, transaction, "CommonModule.Api.Unknown.Name"));
        assertNull(lookup.invoke(null, transaction, "CommonModule.Api."));
    }
    @Test void failedCompositeRenameAppliesAvailableUndoForEarlierChanges() {
        int[] value = {0};
        var first = mutation(value, 1, false);
        var failing = mutation(value, 2, true);
        var planned = new CompositeChange("Rename", new org.eclipse.ltk.core.refactoring.Change[] {first, failing});
        var result = new BslRefactorGateway.Result();
        assertThrows(org.eclipse.core.runtime.CoreException.class, () -> BslRefactorGateway.performChangeWithRollback(planned, result));
        assertEquals(0, value[0]);
    }
    private static org.eclipse.ltk.core.refactoring.Change mutation(int[] value, int after, boolean fail) {
        return new org.eclipse.ltk.core.refactoring.Change() {
            public String getName() { return "Mutation"; }
            public void initializeValidationData(org.eclipse.core.runtime.IProgressMonitor monitor) {}
            public org.eclipse.ltk.core.refactoring.RefactoringStatus isValid(org.eclipse.core.runtime.IProgressMonitor monitor) { return new org.eclipse.ltk.core.refactoring.RefactoringStatus(); }
            public org.eclipse.ltk.core.refactoring.Change perform(org.eclipse.core.runtime.IProgressMonitor monitor) throws org.eclipse.core.runtime.CoreException {
                if (fail) throw new org.eclipse.core.runtime.CoreException(org.eclipse.core.runtime.Status.error("Injected failure"));
                int before = value[0]; value[0] = after;
                return mutation(value, before, false);
            }
            public Object getModifiedElement() { return null; }
        };
    }
    @Test void firstMethodCannotTreatAProxyModuleAsKnownEmptyState() {
        var owner = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createCommonModule();
        var module = BslFactory.eINSTANCE.createModule();
        ((org.eclipse.emf.ecore.InternalEObject) module).eSetProxyURI(URI.createURI("platform:/resource/Demo/Module.bsl"));
        owner.setModule(module);
        assertFalse(BslGateway.isEmptyModuleSlot(owner, "Module.bsl"));
    }
    @Test void returnedFormContentFqnResolvesToTheSameModuleOwnerAndPath() throws Exception {
        var content = com._1c.g5.v8.dt.form.model.FormFactory.eINSTANCE.createForm();
        assertTrue(BslGateway.isEmptyModuleSlot(content, "Module.bsl"));
        String returned = "ExternalDataProcessor.Demo.Form.Main.Form";
        String canonical = "ExternalDataProcessor.Demo.Form.Main";
        assertEquals(canonical, BslGateway.normalizeModuleOwnerFqn(returned));
        assertEquals("CommonForm.Main", BslGateway.normalizeModuleOwnerFqn("CommonForm.Main.Form"));
        var transaction = (com._1c.g5.v8.bm.core.IBmTransaction) Proxy.newProxyInstance(
                com._1c.g5.v8.bm.core.IBmTransaction.class.getClassLoader(),
                new Class<?>[] {com._1c.g5.v8.bm.core.IBmTransaction.class},
                (proxy, method, args) -> method.getName().equals("getTopObjectByFqn") && returned.equals(args[0]) ? content : null);
        var lookup = BslGateway.class.getDeclaredMethod("moduleOwner", com._1c.g5.v8.bm.core.IBmTransaction.class, String.class);
        lookup.setAccessible(true);
        assertSame(content, lookup.invoke(null, transaction, returned));
        assertSame(content, lookup.invoke(null, transaction, canonical));
    }
    @Test void firstMethodResolvesANativeLazyModuleReferenceBeforeTestingItsSourceState() {
        var owner = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createCommonModule();
        owner.setName("Api");
        var resources = new org.eclipse.emf.ecore.resource.impl.ResourceSetImpl();
        var metadata = new org.eclipse.emf.ecore.resource.impl.ResourceImpl(URI.createURI("memory:/Api.mdo"));
        var source = new org.eclipse.emf.ecore.resource.impl.ResourceImpl(URI.createURI("memory:/Module.bsl"));
        resources.getResources().add(metadata);
        resources.getResources().add(source);
        metadata.getContents().add(owner);
        var resolved = BslFactory.eINSTANCE.createModule();
        source.getContents().add(resolved);
        var lazy = BslFactory.eINSTANCE.createModule();
        ((org.eclipse.emf.ecore.InternalEObject) lazy).eSetProxyURI(source.getURI().appendFragment(source.getURIFragment(resolved)));
        owner.setModule(lazy);
        assertTrue(((org.eclipse.emf.ecore.EObject) owner.eGet(owner.eClass().getEStructuralFeature("module"), false)).eIsProxy());
        assertTrue(BslGateway.isEmptyModuleSlot(owner, "Module.bsl"));
        assertSame(resolved, owner.getModule());
        resolved.getMethods().add(BslFactory.eINSTANCE.createProcedure());
        assertFalse(BslGateway.isEmptyModuleSlot(owner, "Module.bsl"));
    }
    @Test void readyExistingCommonModuleMayInitializeItsExactlyCanonicalAbsentResourceProxy() {
        var owner = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createCommonModule();
        owner.setName("Api");
        URI expected = URI.createURI("platform:/resource/Demo/src/CommonModules/Api/Module.bsl");
        var proxy = BslFactory.eINSTANCE.createModule();
        ((org.eclipse.emf.ecore.InternalEObject) proxy).eSetProxyURI(expected.appendFragment("/0"));
        owner.setModule(proxy);
        assertNotNull(BslGateway.emptyModuleSlotProblem(owner, "Module.bsl"));
        assertNull(BslGateway.emptyModuleSlotProblem(owner, "Module.bsl", expected));
        assertTrue(proxy.eIsProxy());
        proxy.getMethods().add(BslFactory.eINSTANCE.createProcedure());
        assertNotNull(BslGateway.emptyModuleSlotProblem(owner, "Module.bsl", expected));
    }
    @Test void missingResourceProxyMustMatchCanonicalOwnerFileAndRootFragment() {
        var owner = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createCommonModule(); owner.setName("Api");
        URI expected = URI.createURI("platform:/resource/Demo/src/CommonModules/Api/Module.bsl");
        for (String address : List.of("platform:/resource/Other/src/CommonModules/Api/Module.bsl#/0",
                "platform:/resource/Demo/src/CommonModules/Other/Module.bsl#/0",
                "platform:/resource/Demo/src/CommonModules/Api/Module.bsl#/1", "file:/Module.bsl#/0")) {
            var proxy = BslFactory.eINSTANCE.createModule();
            ((org.eclipse.emf.ecore.InternalEObject) proxy).eSetProxyURI(URI.createURI(address)); owner.setModule(proxy);
            assertNotNull(BslGateway.emptyModuleSlotProblem(owner, "Module.bsl", expected), address);
        }
        var proxy = BslFactory.eINSTANCE.createModule();
        URI otherOwner = URI.createURI("platform:/resource/Demo/src/CommonModules/Other/Module.bsl");
        ((org.eclipse.emf.ecore.InternalEObject) proxy).eSetProxyURI(otherOwner.appendFragment("/0")); owner.setModule(proxy);
        assertNotNull(BslGateway.emptyModuleSlotProblem(owner, "Module.bsl", otherOwner));
    }
    @Test void missingModuleCannotOverwriteNonblankOrDirtyEditorContent() {
        assertNull(BslGateway.moduleBufferProblem("", false));
        assertNotNull(BslGateway.moduleBufferProblem("// Unsaved source", false));
        assertNotNull(BslGateway.moduleBufferProblem("", true));
        assertNotNull(BslGateway.moduleBufferProblem(null, false));
    }
    @Test void canonicalAbsentProxyCannotHideCachedModelMethodsOrComments() {
        URI expected = URI.createURI("platform:/resource/Demo/src/CommonModules/Api/Module.bsl");
        var resources = new org.eclipse.emf.ecore.resource.impl.ResourceSetImpl();
        var source = new org.eclipse.emf.ecore.resource.impl.ResourceImpl(expected);
        resources.getResources().add(source);
        var module = BslFactory.eINSTANCE.createModule(); source.getContents().add(module);
        assertNull(BslGateway.cachedModuleResourceProblem(resources, expected, null));
        module.getMethods().add(BslFactory.eINSTANCE.createProcedure());
        assertNotNull(BslGateway.cachedModuleResourceProblem(resources, expected, null));
        module.getMethods().clear();
        NodeModelBuilder builder = new NodeModelBuilder();
        var root = builder.newRootNode("// Cached unsaved header");
        builder.newLeafNode(0, "// Cached unsaved header".length(), null, true, null, root);
        builder.associateWithSemanticElement(root, module);
        assertNotNull(BslGateway.cachedModuleResourceProblem(resources, expected, null));
        assertNull(BslGateway.cachedModuleResourceProblem(resources, URI.createURI("platform:/resource/Demo/src/CommonModules/Other/Module.bsl"), null));
    }
    @Test void canonicalProxyWithItsOwnSourceCommentRemainsProtected() {
        var owner = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createCommonModule(); owner.setName("Api");
        URI expected = URI.createURI("platform:/resource/Demo/src/CommonModules/Api/Module.bsl");
        var proxy = BslFactory.eINSTANCE.createModule();
        ((org.eclipse.emf.ecore.InternalEObject) proxy).eSetProxyURI(expected.appendFragment("/0")); owner.setModule(proxy);
        NodeModelBuilder builder = new NodeModelBuilder();
        var root = builder.newRootNode("// Never replace this source");
        builder.newLeafNode(0, "// Never replace this source".length(), null, true, null, root);
        builder.associateWithSemanticElement(root, proxy);
        assertNotNull(BslGateway.emptyModuleSlotProblem(owner, "Module.bsl", expected));
    }
    @Test void missingCanonicalSourceRejectsForeignProxyEvenIfItCouldResolveToEmptyModule() {
        var owner = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createCommonModule(); owner.setName("Api");
        var resources = new org.eclipse.emf.ecore.resource.impl.ResourceSetImpl();
        var metadata = new org.eclipse.emf.ecore.resource.impl.ResourceImpl(URI.createURI("memory:/Api.mdo"));
        var foreign = new org.eclipse.emf.ecore.resource.impl.ResourceImpl(URI.createURI("memory:/Foreign.bsl"));
        resources.getResources().add(metadata); resources.getResources().add(foreign); metadata.getContents().add(owner);
        var empty = BslFactory.eINSTANCE.createModule(); foreign.getContents().add(empty);
        var proxy = BslFactory.eINSTANCE.createModule();
        ((org.eclipse.emf.ecore.InternalEObject) proxy).eSetProxyURI(foreign.getURI().appendFragment(foreign.getURIFragment(empty)));
        owner.setModule(proxy);
        URI expected = URI.createURI("platform:/resource/Demo/src/CommonModules/Api/Module.bsl");
        assertNotNull(BslGateway.emptyModuleSlotProblem(owner, "Module.bsl", expected));
        assertTrue(((org.eclipse.emf.ecore.EObject) owner.eGet(owner.eClass().getEStructuralFeature("module"), false)).eIsProxy());
    }
    @Test void canonicalProxyCannotDiscardItsOwnSourceWhileResolvingToAnEmptyResource() {
        var owner = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createCommonModule(); owner.setName("Api");
        URI expected = URI.createURI("platform:/resource/Demo/src/CommonModules/Api/Module.bsl");
        var resources = new org.eclipse.emf.ecore.resource.impl.ResourceSetImpl();
        var metadata = new org.eclipse.emf.ecore.resource.impl.ResourceImpl(URI.createURI("memory:/Api.mdo"));
        var source = new org.eclipse.emf.ecore.resource.impl.ResourceImpl(expected);
        resources.getResources().add(metadata); resources.getResources().add(source); metadata.getContents().add(owner);
        var empty = BslFactory.eINSTANCE.createModule(); source.getContents().add(empty);
        var proxy = BslFactory.eINSTANCE.createModule();
        ((org.eclipse.emf.ecore.InternalEObject) proxy).eSetProxyURI(expected.appendFragment(source.getURIFragment(empty)));
        proxy.getMethods().add(BslFactory.eINSTANCE.createProcedure()); owner.setModule(proxy);
        assertNotNull(BslGateway.emptyModuleSlotProblem(owner, "Module.bsl", expected));
        assertTrue(((org.eclipse.emf.ecore.EObject) owner.eGet(owner.eClass().getEStructuralFeature("module"), false)).eIsProxy());
        assertEquals(1, proxy.getMethods().size());
    }
    private static TextFileChange change(String path, int edits) {
        IFile file = (IFile) Proxy.newProxyInstance(IFile.class.getClassLoader(), new Class<?>[] {IFile.class},
                (proxy, method, args) -> method.getName().equals("getFullPath") ? new Path(path)
                        : method.getReturnType() == boolean.class ? false : null);
        TextFileChange change = new TextFileChange("Rename references", file);
        MultiTextEdit root = new MultiTextEdit();
        for (int i = 0; i < edits; i++) root.addChild(new ReplaceEdit(i * 10, 3, "New"));
        change.setEdit(root);
        return change;
    }
}
