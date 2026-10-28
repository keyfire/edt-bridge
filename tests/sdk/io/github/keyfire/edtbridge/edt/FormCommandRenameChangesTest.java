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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import com._1c.g5.v8.dt.form.model.*;

class FormCommandRenameChangesTest {
    private static final String BROKEN = "com._1c.g5.v8.dt.internal.form.ui.refactoring.FormElementHandlerRenameParticipant$FormCommandHandlerNameChange";
    private static final FormCommandRenameChanges.Access LOCAL = (command, write, operation) -> operation.accept(command);

    @Test void sdkCommandActionRejectsTheNativeStringAssignment() {
        FormCommand command = command("RunHandler");
        assertThrows(ClassCastException.class, () -> command.eSet(FormPackage.Literals.FORM_COMMAND__ACTION, "Renamed"));
        assertEquals("RunHandler", handler(command).getName());
    }
    @Test void typedChangePreservesObjectsAndPropertiesAndReturnsUndo() throws Exception {
        FormCommand command = command("rUnHandler");
        var action = command.getAction(); var handler = handler(command);
        command.setId(73); command.getTitle().put("ru", "Run");
        Change original = nativeChange(command, "Renamed");
        Change change = FormCommandRenameChanges.adapt(original, "RunHandler", "Renamed", LOCAL);
        assertNotSame(original, change);
        assertSame(command, change.getModifiedElement());
        assertFalse(change.isValid(null).hasError());
        Change undo = change.perform(null);
        assertSame(action, command.getAction()); assertSame(handler, handler(command));
        assertEquals("Renamed", handler.getName()); assertEquals(73, command.getId());
        assertEquals("Run", command.getTitle().get("ru"));
        assertNotNull(undo); assertFalse(undo.isValid(null).hasError());
        undo.perform(null);
        assertEquals("rUnHandler", handler.getName());
    }
    @Test void extensionActionPreservesOtherHandlersAndCallTypes() throws Exception {
        FormCommand command = FormFactory.eINSTANCE.createFormCommand(); command.setName("Run");
        var action = FormFactory.eINSTANCE.createFormExtensionCommandHandlerContainer();
        var target = FormFactory.eINSTANCE.createCommandHandlerExtension(); target.setName("RunHandler");
        target.setCallType(ExtendedMethodCallType.values()[0]);
        var other = FormFactory.eINSTANCE.createCommandHandlerExtension(); other.setName("Other");
        other.setCallType(ExtendedMethodCallType.values()[1]);
        action.getHandlers().addAll(List.of(target, other)); command.setAction(action);
        var callType = target.getCallType(); var otherCallType = other.getCallType();
        Change change = FormCommandRenameChanges.adapt(nativeChange(command, "Renamed"), "RunHandler", "Renamed", LOCAL);
        Change undo = change.perform(null);
        assertSame(action, command.getAction()); assertEquals(List.of(target, other), action.getHandlers());
        assertEquals("Renamed", target.getName()); assertEquals("Other", other.getName());
        assertEquals(callType, target.getCallType()); assertEquals(otherCallType, other.getCallType());
        undo.perform(null); assertEquals("RunHandler", target.getName());
    }
    @Test void adaptationPreservesCompositeIdentityOrderAndUnrelatedNativeChanges() throws Exception {
        Change before = marker(), after = marker();
        Change broken = nativeChange(command("RunHandler"), "Renamed");
        CompositeChange nested = new CompositeChange("Native", new Change[] {before, broken, after});
        CompositeChange root = new CompositeChange("Root", new Change[] {nested}); root.markAsSynthetic();
        assertSame(root, FormCommandRenameChanges.adapt(root, "RunHandler", "Renamed", LOCAL));
        assertSame(nested, root.getChildren()[0]); assertTrue(root.isSynthetic());
        assertSame(before, nested.getChildren()[0]); assertSame(after, nested.getChildren()[2]);
        assertNotSame(broken, nested.getChildren()[1]);
    }
    @Test void changedActionIsRejectedDuringPreflightAndBeforeAnyWrite() throws Exception {
        FormCommand command = command("RunHandler");
        Change change = FormCommandRenameChanges.adapt(nativeChange(command, "Renamed"), "RunHandler", "Renamed", LOCAL);
        handler(command).setName("ChangedAfterPlanning");
        assertTrue(change.isValid(null).hasError());
        assertThrows(CoreException.class, () -> change.perform(null));
        assertEquals("ChangedAfterPlanning", handler(command).getName());
    }
    @Test void ambiguousExtensionBindingsAreRefusedWithoutMutatingTheNativeTree() throws Exception {
        FormCommand valid = command("RunHandler"), ambiguous = FormFactory.eINSTANCE.createFormCommand();
        var action = FormFactory.eINSTANCE.createFormExtensionCommandHandlerContainer();
        for (int index = 0; index < 2; index++) {
            var handler = FormFactory.eINSTANCE.createCommandHandlerExtension(); handler.setName("RunHandler");
            action.getHandlers().add(handler);
        }
        ambiguous.setAction(action);
        Change first = nativeChange(valid, "Renamed"), second = nativeChange(ambiguous, "Renamed");
        CompositeChange root = new CompositeChange("Root", new Change[] {first, second});
        assertThrows(CoreException.class, () -> FormCommandRenameChanges.adapt(root, "RunHandler", "Renamed", LOCAL));
        assertArrayEquals(new Change[] {first, second}, root.getChildren());
        assertEquals("RunHandler", handler(valid).getName());
    }
    @Test void absentActionAndDuplicateNativeTargetsAreRefusedBeforeExecution() throws Exception {
        FormCommand empty = FormFactory.eINSTANCE.createFormCommand();
        assertThrows(CoreException.class, () -> FormCommandRenameChanges.adapt(nativeChange(empty,"Renamed"),"RunHandler","Renamed",LOCAL));
        FormCommand command = command("RunHandler");
        CompositeChange duplicate = new CompositeChange("Duplicate", new Change[] {nativeChange(command,"Renamed"),nativeChange(command,"Renamed")});
        assertThrows(CoreException.class, () -> FormCommandRenameChanges.adapt(duplicate,"RunHandler","Renamed",LOCAL));
        assertEquals("RunHandler", handler(command).getName());
    }
    @Test void disabledBrokenChangesRemainDisabledAndUntouched() throws Exception {
        Change change = nativeChange(command("RunHandler"),"Renamed"); change.setEnabled(false);
        assertSame(change, FormCommandRenameChanges.adapt(change,"RunHandler","Renamed",LOCAL));
        assertFalse(change.isEnabled());
    }
    @Test void writeBoundaryFailureRestoresTheOriginalTypedBinding() throws Exception {
        FormCommand command = command("RunHandler"); var action = command.getAction();
        var firstWrite = new java.util.concurrent.atomic.AtomicBoolean(true);
        FormCommandRenameChanges.Access access = (current, write, operation) -> {
            operation.accept(current);
            if (write && firstWrite.getAndSet(false)) {
                throw new CoreException(new org.eclipse.core.runtime.Status(org.eclipse.core.runtime.IStatus.ERROR,
                        "test", "failure after mutation"));
            }
        };
        Change change = FormCommandRenameChanges.adapt(nativeChange(command,"Renamed"),"RunHandler","Renamed",access);
        assertThrows(CoreException.class, () -> change.perform(null));
        assertSame(action, command.getAction()); assertEquals("RunHandler", handler(command).getName());
    }
    @Test void compositeExposesTypedUndoWhenALaterNativeChangeFails() throws Exception {
        FormCommand command = command("RunHandler");
        Change failure = new Change() {
            public String getName() {return "Later failure";}
            public void initializeValidationData(IProgressMonitor monitor) {}
            public RefactoringStatus isValid(IProgressMonitor monitor) {return new RefactoringStatus();}
            public Change perform(IProgressMonitor monitor) throws CoreException {
                throw new CoreException(new org.eclipse.core.runtime.Status(org.eclipse.core.runtime.IStatus.ERROR,"test","late failure"));
            }
            public Object getModifiedElement() {return null;}
        };
        CompositeChange all = new CompositeChange("Native rename",new Change[] {nativeChange(command,"Renamed"),failure});
        FormCommandRenameChanges.adapt(all,"RunHandler","Renamed",LOCAL);
        assertThrows(CoreException.class, () -> all.perform(new org.eclipse.core.runtime.NullProgressMonitor()));
        assertEquals("Renamed",handler(command).getName());
        Change undo = all.getUndoUntilException(); assertNotNull(undo);
        undo.perform(new org.eclipse.core.runtime.NullProgressMonitor()); assertEquals("RunHandler",handler(command).getName());
    }
    private static FormCommand command(String name) {
        var command = FormFactory.eINSTANCE.createFormCommand(); command.setName("Run");
        var action = FormFactory.eINSTANCE.createFormCommandHandlerContainer();
        var handler = FormFactory.eINSTANCE.createCommandHandler(); handler.setName(name);
        action.setHandler(handler); command.setAction(action); return command;
    }
    private static CommandHandler handler(FormCommand command) {
        return ((FormCommandHandlerContainer) command.getAction()).getHandler();
    }
    private static Change nativeChange(FormCommand command, String newName) throws Exception {
        var constructor = Class.forName(BROKEN).getDeclaredConstructor(FormCommand.class,org.eclipse.emf.ecore.EStructuralFeature.class,String.class);
        constructor.setAccessible(true);
        return (Change) constructor.newInstance(command,FormPackage.Literals.FORM_COMMAND__ACTION,newName);
    }
    private static Change marker() {
        return new Change() {
            public String getName() {return "Unrelated native change";}
            public void initializeValidationData(IProgressMonitor monitor) {}
            public RefactoringStatus isValid(IProgressMonitor monitor) {return new RefactoringStatus();}
            public Change perform(IProgressMonitor monitor) {throw new AssertionError("must not execute while adapting");}
            public Object getModifiedElement() {return null;}
        };
    }
}
