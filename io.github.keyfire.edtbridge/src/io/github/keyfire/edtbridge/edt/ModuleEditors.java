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
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;

/**
 * The editors of one file in client EDT. EDT applies a quick fix to a module through the module
 * editor: the fix opens it when it is closed and changes the editor's text, which reaches the disk
 * only when the editor is saved. SWT lets only the UI thread touch an editor, so every method here
 * except {@link #onUiThread} expects to run on that thread.
 */
final class ModuleEditors {
    private ModuleEditors() {
    }

    /** Runs the operation on the EDT UI thread and waits for it; refuses when the thread does not respond. */
    static void onUiThread(Runnable operation) {
        Display display = PlatformUI.getWorkbench().getDisplay();
        if (display == null || display.isDisposed()) {
            throw new IllegalStateException("EDT workbench is unavailable");
        }
        if (Display.getCurrent() == display) {
            operation.run();
        } else {
            HtmlTemplateGateway.dispatchBounded(display::asyncExec, operation, HtmlTemplateGateway.UI_WAIT_MS);
        }
    }

    /** The open editors whose input is the file, in every window and page. */
    static List<IEditorReference> of(IFile file) {
        List<IEditorReference> found = new ArrayList<>();
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
            for (IWorkbenchPage page : window.getPages()) {
                for (IEditorReference reference : page.getEditorReferences()) {
                    if (file.equals(inputFile(reference))) {
                        found.add(reference);
                    }
                }
            }
        }
        return found;
    }

    /** Whether an open editor of the file holds text that is not on disk. */
    static boolean dirty(IFile file) {
        return of(file).stream().anyMatch(IEditorReference::isDirty);
    }

    /**
     * Saves the editors of the file that hold unsaved text and closes those that were not open
     * before, so the fix leaves the workbench as it found it. Returns how many editors were saved.
     */
    static int saveAndClose(IFile file, Set<IEditorReference> openBefore) {
        int saved = 0;
        for (IEditorReference reference : of(file)) {
            IEditorPart editor = reference.getEditor(false);
            if (editor == null) {
                continue;
            }
            if (editor.isDirty()) {
                editor.doSave(new NullProgressMonitor());
                if (!editor.isDirty()) {
                    saved++;
                }
            }
            if (!openBefore.contains(reference) && !editor.isDirty()) {
                IWorkbenchPage page = reference.getPage();
                if (page != null) {
                    page.closeEditor(editor, false);
                }
            }
        }
        return saved;
    }

    private static IFile inputFile(IEditorReference reference) {
        try {
            IEditorInput input = reference.getEditorInput();
            return input == null ? null : input.getAdapter(IFile.class);
        } catch (PartInitException unavailable) {
            return null;
        }
    }
}
