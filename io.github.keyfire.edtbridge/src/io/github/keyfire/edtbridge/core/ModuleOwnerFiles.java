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
package io.github.keyfire.edtbridge.core;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Whether a module file sits next to the metadata that makes it part of the project.
 *
 * <p>EDT compiles a module only for an object of its model. A {@code Module.bsl} written into
 * {@code Forms/<Name>/} of a form that the project does not have - in an extension, a form that was
 * never adopted - parses, takes methods and even shows up as an interception in a build, yet never
 * runs: the form is missing. The model of a form is {@code Form.form} next to its module, and an
 * object module sits next to its object's {@code .mdo}; without them the module is detached.
 */
public final class ModuleOwnerFiles {

    /** Modules that belong to the object whose {@code .mdo} lies in the same folder. */
    private static final Set<String> OBJECT_MODULES = Set.of(
            "ObjectModule.bsl", "ManagerModule.bsl", "RecordSetModule.bsl", "ValueManagerModule.bsl");

    private ModuleOwnerFiles() {
    }

    /**
     * {@code null} when the module has its owner's metadata beside it (or is a module this check
     * does not cover), otherwise why it is detached.
     *
     * @param modulePath project-relative path, e.g. {@code src/Documents/Order/Forms/Main/Module.bsl}
     * @param exists     whether a project-relative path exists
     */
    public static String problem(String modulePath, Predicate<String> exists) {
        if (modulePath == null || modulePath.isBlank()) {
            return null;
        }
        String path = modulePath.replace('\\', '/');
        int slash = path.lastIndexOf('/');
        if (slash <= 0) {
            return null;
        }
        String folder = path.substring(0, slash);
        String file = path.substring(slash + 1);
        int parentSlash = folder.lastIndexOf('/');
        String folderName = folder.substring(parentSlash + 1);
        String grandParent = parentSlash <= 0 ? "" : folder.substring(0, parentSlash);
        boolean formModule = "Module.bsl".equals(file)
                && (grandParent.endsWith("/Forms") || grandParent.endsWith("/CommonForms")
                        || grandParent.equals("src/CommonForms"));
        if (formModule) {
            if (exists.test(folder + "/Form.form")) {
                return null;
            }
            return "the form module " + path + " has no form model (" + folder + "/Form.form) beside it:"
                    + " the form \"" + folderName + "\" is not part of the project, so the module never"
                    + " runs. In an extension, adopt the form first with edt_adopt_object"
                    + " (e.g. Document.Order.Form." + folderName + ")";
        }
        if (OBJECT_MODULES.contains(file)) {
            String mdo = folder + "/" + folderName + ".mdo";
            if (exists.test(mdo)) {
                return null;
            }
            return "the module " + path + " has no object metadata (" + mdo + ") beside it:"
                    + " the object is not part of the project, so the module never runs."
                    + " In an extension, adopt the object first with edt_adopt_object";
        }
        return null;
    }
}
