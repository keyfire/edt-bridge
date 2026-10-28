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

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.dt.bsl.bm.ui.refactoring.AbstractBslMdObjectHandlerRenameParticipant;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.form.model.CommandHandler;
import com._1c.g5.v8.dt.form.model.CommandHandlerExtension;
import com._1c.g5.v8.dt.form.model.ExtendedMethodCallType;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormCommandHandlerContainer;
import com._1c.g5.v8.dt.form.model.FormExtensionCommandHandlerContainer;
import com._1c.g5.v8.dt.form.model.FormPackage;
import com._1c.g5.wiring.ServiceAccess;

/** Repair the SDK command rename change that assigns a string to the typed action reference. */
public final class FormCommandRenameChanges {
    private static final String BROKEN_CLASS = "com._1c.g5.v8.dt.internal.form.ui.refactoring."
            + "FormElementHandlerRenameParticipant$FormCommandHandlerNameChange";
    private static final String PLUGIN = "io.github.keyfire.edtbridge";

    private FormCommandRenameChanges() { }

    @FunctionalInterface
    interface Access {
        void execute(FormCommand command, boolean write, Consumer<FormCommand> operation) throws CoreException;
    }

    /** Preserve native ordering and every other change; validate all replacements before editing the tree. */
    public static Change adapt(Change change, String oldName, String newName) throws CoreException {
        return adapt(change, oldName, newName, FormCommandRenameChanges::inModel);
    }

    static Change adapt(Change change, String oldName, String newName, Access access) throws CoreException {
        Map<Change, Change> replacements = new IdentityHashMap<>();
        Map<FormCommand, Boolean> targets = new IdentityHashMap<>();
        collect(change, oldName, newName, access, replacements, targets);
        return replace(change, replacements);
    }

    private static void collect(Change change, String oldName, String newName, Access access,
            Map<Change, Change> replacements, Map<FormCommand, Boolean> targets) throws CoreException {
        if (!change.isEnabled()) return;
        if (BROKEN_CLASS.equals(change.getClass().getName())) {
            if (!(change.getModifiedElement() instanceof FormCommand command)) {
                throw failure("native command rename has no FormCommand target", null);
            }
            if (targets.put(command, Boolean.TRUE) != null) {
                throw failure("multiple native changes target the same command action; rename refused", null);
            }
            if (oldName == null || oldName.isBlank() || newName == null || newName.isBlank()) {
                throw failure("command handler rename requires both old and new names", null);
            }
            State[] state = {null};
            int[] selected = {-1};
            execute(access, command, false, current -> {
                state[0] = state(current);
                for (int index = 0; index < state[0].handlers().size(); index++) {
                    if (oldName.equalsIgnoreCase(state[0].handlers().get(index).name())) {
                        if (selected[0] >= 0) throw new IllegalArgumentException("command action has ambiguous handler bindings");
                        selected[0] = index;
                    }
                }
                if (selected[0] < 0) throw new IllegalArgumentException("command action no longer refers to " + oldName);
            });
            replacements.put(change, new NativeParticipantAccess.TypedCommandHandlerNameChange(change.getName(), command,
                    state[0], selected[0], newName, access));
        } else if (change instanceof CompositeChange composite) {
            for (Change child : composite.getChildren()) collect(child, oldName, newName, access, replacements, targets);
        }
    }

    private static Change replace(Change change, Map<Change, Change> replacements) {
        Change replacement = replacements.get(change);
        if (replacement != null) return replacement;
        if (change instanceof CompositeChange composite && change.isEnabled()) {
            Change[] children = composite.getChildren();
            boolean changed = false;
            for (int index = 0; index < children.length; index++) {
                Change adapted = replace(children[index], replacements);
                changed |= adapted != children[index]; children[index] = adapted;
            }
            if (changed) {
                for (Change original : composite.getChildren()) composite.remove(original);
                composite.addAll(children);
            }
        }
        return change;
    }

    private record HandlerState(String type, String name, ExtendedMethodCallType callType) { }
    private record State(String actionType, List<HandlerState> handlers) {
        State renamed(int index, String name) {
            List<HandlerState> updated = new ArrayList<>(handlers);
            HandlerState before = handlers.get(index);
            updated.set(index, new HandlerState(before.type(), name, before.callType()));
            return new State(actionType, List.copyOf(updated));
        }
    }

    private static List<CommandHandler> handlers(FormCommand command) {
        if (command.getAction() instanceof FormCommandHandlerContainer action && action.getHandler() != null) {
            return List.of(action.getHandler());
        }
        if (command.getAction() instanceof FormExtensionCommandHandlerContainer action && !action.getHandlers().isEmpty()) {
            return new ArrayList<>(action.getHandlers());
        }
        throw new IllegalArgumentException("command action has no supported handler container");
    }

    private static State state(FormCommand command) {
        List<HandlerState> states = new ArrayList<>();
        for (CommandHandler handler : handlers(command)) {
            if (handler == null || handler.getName() == null || handler.getName().isBlank()) {
                throw new IllegalArgumentException("command action has an incomplete handler binding");
            }
            states.add(new HandlerState(handler.eClass().getName(), handler.getName(),
                    handler instanceof CommandHandlerExtension extension ? extension.getCallType() : null));
        }
        return new State(command.getAction().eClass().getName(), List.copyOf(states));
    }

    // The SDK exposes this nested Change as protected to participant subclasses.
    private abstract static class NativeParticipantAccess extends AbstractBslMdObjectHandlerRenameParticipant {
        private static final class TypedCommandHandlerNameChange extends MdObjectHandlerNameChange {
            private final String description;
            private final FormCommand command;
            private final State expected;
            private final int selected;
            private final Access access;

            TypedCommandHandlerNameChange(String description, FormCommand command, State expected,
                    int selected, String newName, Access access) {
                super(command, FormPackage.Literals.FORM_COMMAND__ACTION, newName);
                this.description = description; this.command = command; this.expected = expected;
                this.selected = selected; this.access = access;
            }

            @Override public String getName() { return description; }

            @Override public RefactoringStatus isValid(IProgressMonitor monitor) {
                try {
                    execute(access, command, false, current -> requireExpected(current, expected));
                    return new RefactoringStatus();
                } catch (CoreException invalid) {
                    return RefactoringStatus.createFatalErrorStatus(invalid.getMessage());
                }
            }

            @Override public Change perform(IProgressMonitor monitor) throws CoreException {
                State updated = expected.renamed(selected, newName);
                boolean[] changed = {false};
                try {
                    execute(access, command, true, current -> {
                        requireExpected(current, expected);
                        handlers(current).get(selected).setName(newName);
                        changed[0] = true;
                    });
                } catch (CoreException failed) {
                    if (changed[0]) {
                        try {
                            execute(access, command, true, current -> {
                                if (expected.equals(state(current))) return;
                                requireExpected(current, updated);
                                handlers(current).get(selected).setName(expected.handlers().get(selected).name());
                            });
                        } catch (CoreException rollback) {
                            failed.addSuppressed(rollback);
                            throw failure(failed.getMessage() + "; command handler rollback failed: " + rollback.getMessage(), failed);
                        }
                    }
                    throw failed;
                }
                return new TypedCommandHandlerNameChange(description, command, updated, selected,
                        expected.handlers().get(selected).name(), access);
            }
        }
    }

    private static void requireExpected(FormCommand command, State expected) {
        if (!expected.equals(state(command))) {
            throw new IllegalArgumentException("command action changed after rename planning; retry");
        }
    }

    private static void execute(Access access, FormCommand command, boolean write,
            Consumer<FormCommand> operation) throws CoreException {
        try { access.execute(command, write, operation); }
        catch (RuntimeException invalid) { throw failure(invalid.getMessage(), invalid); }
    }

    private static void inModel(FormCommand command, boolean write, Consumer<FormCommand> operation) throws CoreException {
        IBmModelManager manager = ServiceAccess.get(IBmModelManager.class);
        var model = manager == null ? null : manager.getModel(command);
        if (model == null) throw failure("BM model unavailable for command handler rename", null);
        AbstractBmTask<Object> task = new AbstractBmTask<Object>("edt-bridge.renameFormCommandHandler") {
            @Override public Object execute(IBmTransaction transaction, IProgressMonitor monitor) {
                FormCommand current = transaction.toTransactionObject(command);
                if (current == null) throw new IllegalArgumentException("form command disappeared after rename planning");
                operation.accept(current); return null;
            }
        };
        if (write) {
            model.getGlobalContext().execute(task);
            model.waitAllEnqueuedEventsSent();
        } else {
            model.executeReadonlyTask(task);
        }
    }

    private static CoreException failure(String message, Throwable cause) {
        return new CoreException(new Status(IStatus.ERROR, PLUGIN, message, cause));
    }
}
