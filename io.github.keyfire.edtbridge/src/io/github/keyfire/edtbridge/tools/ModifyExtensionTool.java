package io.github.keyfire.edtbridge.tools;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import io.github.keyfire.edtbridge.edt.MetadataSettingsGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Edits metadata of an existing EDT configuration extension project. */
public final class ModifyExtensionTool {
    public String name() { return "edt_modify_extension"; }
    public boolean isWrite() { return true; }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", ToolJson.strProp("EDT configuration extension project"));
        props.add("synonym", ToolJson.strProp("Synonym in the extension's default language. Empty removes it."));
        props.add("comment", ToolJson.strProp("Developer comment. An empty string clears it."));
        props.add("vendor", ToolJson.strProp("Vendor stored in the extension metadata."));
        props.add("version", ToolJson.strProp("Version stored in the extension metadata."));
        props.add("namePrefix", ToolJson.strProp("Prefix for extension-owned names, a valid 1C identifier."));
        props.add("purpose", ToolJson.strProp("Customization, AddOn or Patch."));
        props.add("apply", ToolJson.boolProp("false (default): show old and new values. true: save the changes."));
        return ToolJson.descriptor(name(),
                "WRITE: edit the synonym, comment, vendor, version, name prefix or purpose of an EDT "
                + "configuration extension project. Preview by default; apply=true saves the project metadata.",
                "ЗАПИСЬ: изменить синоним, комментарий, поставщика, версию, префикс имен или назначение "
                + "расширения конфигурации в проекте EDT. По умолчанию предпросмотр, apply=true сохраняет изменения.",
                props, "projectName");
    }

    public JsonObject call(JsonObject args) {
        try {
            var result = new MetadataSettingsGateway().modifyExtension(ToolJson.getStr(args, "projectName"),
                    ToolJson.stringPatch(args, "synonym", "comment", "vendor", "version", "namePrefix", "purpose"),
                    ToolJson.getBool(args, "apply"));
            return McpServer.textResult(new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(result));
        } catch (RuntimeException failure) {
            return McpServer.toolError(name() + ": " + failure.getMessage());
        }
    }
}
