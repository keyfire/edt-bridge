package io.github.keyfire.edtbridge.tools;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import io.github.keyfire.edtbridge.edt.MetadataSettingsGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Edits the settings of an existing external data processor or report. */
public final class ModifyExternalObjectTool {
    public String name() { return "edt_modify_external_object"; }
    public boolean isWrite() { return true; }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", ToolJson.strProp("EDT external object project"));
        props.add("fqn", ToolJson.strProp("Root object, e.g. ExternalDataProcessor.Check or ExternalReport.Summary"));
        props.add("synonymRu", ToolJson.strProp("Russian synonym. An empty string removes it."));
        props.add("comment", ToolJson.strProp("Developer comment. An empty string clears it."));
        props.add("defaultForm", ToolJson.strProp("Name or FQN of an existing managed form of this object."));
        props.add("defaultSettingsForm", ToolJson.strProp("Report only: owned managed settings form name or FQN."));
        props.add("defaultVariantForm", ToolJson.strProp("Report only: owned managed variant form name or FQN."));
        props.add("mainDataCompositionSchema", ToolJson.strProp("Report only: owned data composition schema template name or FQN."));
        props.add("apply", ToolJson.boolProp("false (default): show old and new values. true: save the changes."));
        return ToolJson.descriptor(name(),
                "WRITE: edit the synonym, comment or main form of an external data processor or report. "
                + "For reports, also selects settings and variant forms and the main data composition schema. "
                + "Supply at least one property. Preview by default; apply=true saves.",
                "ЗАПИСЬ: изменить синоним, комментарий или основную форму внешней обработки или отчета. "
                + "Для отчетов также доступны формы настроек и варианта и основная схема компоновки данных. "
                + "Передайте хотя бы одно свойство. По умолчанию предпросмотр, apply=true сохраняет изменения.",
                props, "projectName", "fqn");
    }

    public JsonObject call(JsonObject args) {
        try {
            var result = new MetadataSettingsGateway().modifyExternalObject(ToolJson.getStr(args, "projectName"),
                    ToolJson.getStr(args, "fqn"), ToolJson.stringPatch(args, "synonymRu", "comment", "defaultForm",
                            "defaultSettingsForm", "defaultVariantForm", "mainDataCompositionSchema"),
                    ToolJson.getBool(args, "apply"));
            return McpServer.textResult(new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(result));
        } catch (RuntimeException failure) {
            return McpServer.toolError(name() + ": " + failure.getMessage());
        }
    }
}
