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

import java.util.Map;

/**
 * Where an object's sources live, derived from its FQN alone.
 *
 * <p>This is the one piece of the bridge that is pure string work: an FQN in, a project-relative
 * folder out. It deliberately has NO dependency on EDT or Eclipse, so it compiles - and is tested -
 * without the proprietary SDK a hosted runner cannot obtain. Anything that needs the workspace
 * (does the folder exist, which .bsl files are in it) stays with the gateway that owns the model.
 *
 * <p>The mapping used to be duplicated: once in the module resolver and once in the validation
 * filter, which is how the two could disagree. There is one copy now.
 */
public final class MetadataPaths {

    private MetadataPaths() {
    }

    /** FQN type prefix (lower-cased) -> source folder name. */
    public static final Map<String, String> FOLDER_BY_TYPE = Map.ofEntries(
            Map.entry("catalog", "Catalogs"), Map.entry("document", "Documents"),
            Map.entry("documentjournal", "DocumentJournals"), Map.entry("enum", "Enums"),
            Map.entry("report", "Reports"), Map.entry("dataprocessor", "DataProcessors"),
            Map.entry("chartofcharacteristictypes", "ChartsOfCharacteristicTypes"),
            Map.entry("chartofaccounts", "ChartsOfAccounts"),
            Map.entry("chartofcalculationtypes", "ChartsOfCalculationTypes"),
            Map.entry("informationregister", "InformationRegisters"),
            Map.entry("accumulationregister", "AccumulationRegisters"),
            Map.entry("accountingregister", "AccountingRegisters"),
            Map.entry("calculationregister", "CalculationRegisters"),
            Map.entry("businessprocess", "BusinessProcesses"), Map.entry("task", "Tasks"),
            Map.entry("exchangeplan", "ExchangePlans"), Map.entry("constant", "Constants"),
            Map.entry("commonmodule", "CommonModules"), Map.entry("commonform", "CommonForms"),
            Map.entry("commoncommand", "CommonCommands"),
            // External objects live in their own project, but the layout under src/ is the same, so
            // their modules and forms resolve by FQN like any other owner.
            Map.entry("externaldataprocessor", "ExternalDataProcessors"),
            Map.entry("externalreport", "ExternalReports"),
            // Service objects: one Module.bsl each, resolved by FQN like the module owners above.
            Map.entry("httpservice", "HTTPServices"), Map.entry("webservice", "WebServices"));

    /** Source folder name -> FQN type prefix, the inverse of {@link #FOLDER_BY_TYPE}. */
    public static final Map<String, String> TYPE_BY_FOLDER = Map.ofEntries(
            Map.entry("Catalogs", "Catalog"), Map.entry("Documents", "Document"),
            Map.entry("DocumentJournals", "DocumentJournal"), Map.entry("Enums", "Enum"),
            Map.entry("Reports", "Report"), Map.entry("DataProcessors", "DataProcessor"),
            Map.entry("ChartsOfCharacteristicTypes", "ChartOfCharacteristicTypes"),
            Map.entry("ChartsOfAccounts", "ChartOfAccounts"),
            Map.entry("ChartsOfCalculationTypes", "ChartOfCalculationTypes"),
            Map.entry("InformationRegisters", "InformationRegister"),
            Map.entry("AccumulationRegisters", "AccumulationRegister"),
            Map.entry("AccountingRegisters", "AccountingRegister"),
            Map.entry("CalculationRegisters", "CalculationRegister"),
            Map.entry("BusinessProcesses", "BusinessProcess"), Map.entry("Tasks", "Task"),
            Map.entry("ExchangePlans", "ExchangePlan"), Map.entry("Constants", "Constant"),
            Map.entry("CommonModules", "CommonModule"), Map.entry("CommonForms", "CommonForm"),
            Map.entry("CommonCommands", "CommonCommand"),
            Map.entry("HTTPServices", "HTTPService"), Map.entry("WebServices", "WebService"));

    /** The name of the only module file an owner with a single module keeps. */
    public static final String SINGLE_MODULE_FILE = "Module.bsl";

    /** Source folder for an FQN type prefix, case-insensitively; null when the type is not known. */
    public static String folderForType(String typePrefix) {
        return (typePrefix == null) ? null : FOLDER_BY_TYPE.get(typePrefix.toLowerCase());
    }

    /** True when the FQN names a form: {@code <Type>.<Object>.Form.<Name>} or {@code CommonForm.<Name>}. */
    public static boolean isForm(String fqn) {
        String[] parts = split(fqn);
        if (parts == null) {
            return false;
        }
        return (parts.length >= 4 && "Form".equalsIgnoreCase(parts[parts.length - 2]))
                || (parts.length == 2 && "CommonForm".equalsIgnoreCase(parts[0]));
    }

    /**
     * Project-relative source folder of the object an FQN names, WITHOUT the {@code src/} prefix -
     * a project may or may not use one, and only the workspace knows. Examples:
     * {@code Catalog.Товары} -> {@code Catalogs/Товары}; {@code Catalog.Товары.Form.Список} ->
     * {@code Catalogs/Товары/Forms/Список}; {@code HTTPService.Payments} -> {@code HTTPServices/Payments}.
     * Null when the type prefix is unknown or the FQN has a shape this does not describe.
     */
    public static String objectFolder(String fqn) {
        String[] parts = split(fqn);
        if (parts == null) {
            return null;
        }
        if (parts.length >= 4 && "Form".equalsIgnoreCase(parts[parts.length - 2])) {
            String folder = folderForType(parts[0]);
            return (folder == null) ? null
                    : folder + "/" + parts[1] + "/Forms/" + parts[parts.length - 1];
        }
        if (parts.length == 2) {
            String folder = folderForType(parts[0]);
            return (folder == null) ? null : folder + "/" + parts[1];
        }
        return null;
    }

    /**
     * True when this kind of owner keeps exactly one module file, so the module path follows from the
     * FQN with no look at the workspace: forms, common modules and the service objects. A top object
     * such as a catalog has several (ObjectModule, ManagerModule, ...) and needs the caller to choose.
     */
    public static boolean hasSingleModule(String fqn) {
        String[] parts = split(fqn);
        if (parts == null) {
            return false;
        }
        if (isForm(fqn)) {
            return true;
        }
        return parts.length == 2 && ("CommonModule".equalsIgnoreCase(parts[0])
                || "HTTPService".equalsIgnoreCase(parts[0])
                || "WebService".equalsIgnoreCase(parts[0]));
    }

    /**
     * Module path for an owner that has exactly one module, WITHOUT the {@code src/} prefix; null
     * when the owner may have several (the caller then picks by moduleType or lists the candidates).
     */
    public static String singleModulePath(String fqn) {
        if (!hasSingleModule(fqn)) {
            return null;
        }
        String folder = objectFolder(fqn);
        return (folder == null) ? null : folder + "/" + SINGLE_MODULE_FILE;
    }

    /**
     * The object-name segment of an FQN - the part after the type prefix. Used to match an EDT check
     * marker, which names its object by presentation instead of by path.
     */
    public static String nameToken(String fqn) {
        String[] parts = split(fqn);
        return (parts != null && parts.length >= 2 && !parts[1].isBlank()) ? parts[1] : null;
    }

    /** Split an FQN into its dot-separated parts, or null when there is nothing to split. */
    private static String[] split(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return null;
        }
        return fqn.trim().split("\\.");
    }
}
