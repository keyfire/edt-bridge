/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.tools;

import static io.github.keyfire.edtbridge.tools.ToolJson.boolProp;
import static io.github.keyfire.edtbridge.tools.ToolJson.getBool;
import static io.github.keyfire.edtbridge.tools.ToolJson.getStr;
import static io.github.keyfire.edtbridge.tools.ToolJson.strProp;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.core.ModuleFragment;
import io.github.keyfire.edtbridge.edt.BslGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** MCP tool: edt_replace_module_fragment - WRITE. Replace one exact fragment of a module. */
public final class ReplaceModuleFragmentTool {
    private final BslGateway gateway = new BslGateway();
    public String name() { return "edt_replace_module_fragment"; }
    public boolean isWrite() { return true; }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("EDT project name"));
        props.add("fqn", strProp("Module owner FQN: CommonModule.X, a form (Document.X.Form.Y), or an object + "
                + "moduleType. Alternatively pass modulePath."));
        props.add("moduleType", strProp("For an object with several modules: ObjectModule / ManagerModule / etc."));
        props.add("modulePath", strProp("Workspace-relative .bsl path, e.g. src/Documents/X/ObjectModule.bsl"));
        props.add("oldText", strProp("The exact fragment to replace, copied from edt_module_text with its "
                + "indentation. It must occur once in the module: add surrounding lines until it does."));
        props.add("newText", strProp("The text that takes its place; an empty string deletes the fragment."));
        JsonObject edits = new JsonObject();
        edits.addProperty("type", "array");
        edits.addProperty("description", "Several replacements applied together, in order, as "
                + "[{oldText, newText}]: a region or a #If block opens in one place and closes in another, "
                + "and only the whole batch keeps the module parseable. The result includes one short preview "
                + "per replacement in previews, in the order applied. Use instead of oldText/newText.");
        JsonObject item = new JsonObject();
        item.addProperty("type", "object");
        edits.add("items", item);
        props.add("edits", edits);
        props.add("expectedHash", strProp("sourceHash from edt_module_text or a dry-run. Required for apply."));
        props.add("apply", boolProp("false (default) = dry-run: find the fragment, check that the result "
                + "parses and return the hashes. true = write."));
        JsonArray required = new JsonArray();
        required.add("projectName");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", required);
        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description", "WRITE: replace one exact fragment of a module's BSL text - compilation "
                + "directives, module variables, regions, a preprocessor block, the text that edt_add_method and "
                + "edt_replace_method do not reach. The fragment must occur exactly once and the module must "
                + "still parse after the replacement; edits applies several fragments together, for a region "
                + "or a #If block that opens and closes in different places. Both dry-run and apply return a short "
                + "preview and a line for each entry in edits, in previews. For apply, pass sourceHash from edt_module_text or a dry-run as expectedHash. A changed module is refused. A form module "
                + "without its form model is refused. Check the result with edt_project_errors. Requires a "
                + "configured write token.");
        t.addProperty("descriptionRu", "ЗАПИСЬ: заменить один точный фрагмент текста модуля - директивы "
                + "компиляции, переменные модуля, области, блок препроцессора, то, до чего не достают "
                + "edt_add_method и edt_replace_method. Фрагмент должен встречаться ровно один раз, модуль после "
                + "замены должен разбираться; edits применяет несколько фрагментов вместе - для области или "
                + "блока #Если, которые открываются и закрываются в разных местах. Dry-run и apply возвращают в previews "
                + "строку и короткий предпросмотр каждой замены из edits. Для apply передайте sourceHash из edt_module_text "
                + "или dry-run в expectedHash. Изменившийся модуль дает отказ. Модуль формы без "
                + "модели формы дает отказ. Результат проверять edt_project_errors. Нужен токен записи.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = getStr(args, "projectName");
        java.util.List<ModuleFragment.Change> changes = new java.util.ArrayList<>();
        boolean batch = args.has("edits") && args.get("edits").isJsonArray();
        if (batch) {
            for (var element : args.getAsJsonArray("edits")) {
                if (!element.isJsonObject()) {
                    return McpServer.toolError("every item of edits is an object with oldText and newText");
                }
                JsonObject e = element.getAsJsonObject();
                changes.add(new ModuleFragment.Change(getStr(e, "oldText"), getStr(e, "newText")));
            }
        } else if (args.has("oldText") && args.has("newText")) {
            changes.add(new ModuleFragment.Change(getStr(args, "oldText"), getStr(args, "newText")));
        }
        if (project == null || changes.isEmpty()) {
            return McpServer.toolError("projectName and either oldText/newText or edits are required");
        }
        if (getStr(args, "fqn") == null && getStr(args, "modulePath") == null) {
            return McpServer.toolError("pass fqn or modulePath");
        }
        try {
            String fqn = getStr(args, "fqn");
            String moduleType = getStr(args, "moduleType");
            String path = getStr(args, "modulePath");
            String expectedHash = getStr(args, "expectedHash");
            boolean apply = getBool(args, "apply");
            var result = batch
                    ? gateway.replaceFragments(project, fqn, moduleType, path, changes, expectedHash, apply)
                    : gateway.replaceFragment(project, fqn, moduleType, path, changes.get(0).oldText(),
                            changes.get(0).newText(), expectedHash, apply);
            return McpServer.textResult(new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(result));
        } catch (Exception exception) {
            return McpServer.toolError("edt_replace_module_fragment failed: " + exception.getMessage());
        }
    }
}
