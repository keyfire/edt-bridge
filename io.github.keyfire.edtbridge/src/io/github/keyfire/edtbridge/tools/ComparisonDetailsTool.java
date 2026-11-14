package io.github.keyfire.edtbridge.tools;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Reads one level of object, feature, module or method differences at a time. */
public final class ComparisonDetailsTool {
    private final CompareConfigurationsTool comparisons;

    public ComparisonDetailsTool(CompareConfigurationsTool comparisons) {
        this.comparisons = comparisons;
    }

    public String name() { return "edt_comparison_details"; }

    public JsonObject descriptor() {
        JsonObject props = CompareConfigurationsTool.pageProperties();
        props.add("comparisonId", ToolJson.strProp("Result ID from edt_compare_configurations."));
        props.add("objectIndex", CompareConfigurationsTool.boundedProperty(
                "Index of a changed object in the comparison results.", 0, Integer.MAX_VALUE));
        JsonObject path = new JsonObject();
        path.addProperty("type", "array");
        path.addProperty("description", "Zero-based child indices to the parent node. Omit to list the object's children.");
        path.add("items", CompareConfigurationsTool.boundedProperty("Child index.", 0, Integer.MAX_VALUE));
        props.add("nodeIndices", path);
        return ToolJson.descriptor(name(),
                "READ: page through changed properties and modules down to BSL methods in a configuration comparison.",
                "Постранично прочитать различия свойств и модулей вплоть до методов BSL в результате сравнения конфигураций.",
                props, "comparisonId", "objectIndex");
    }

    public JsonObject call(JsonObject args) {
        try {
            String id = ToolJson.getStr(args, "comparisonId");
            int objectIndex = CompareConfigurationsTool.integer(args.get("objectIndex"), "objectIndex", 0, Integer.MAX_VALUE);
            int offset = CompareConfigurationsTool.integer(args, "offset", 0, 0, Integer.MAX_VALUE);
            int limit = CompareConfigurationsTool.integer(args, "limit", 50, 1, 200);
            List<Integer> path = new ArrayList<>();
            if (args.has("nodeIndices")) {
                for (var index : args.getAsJsonArray("nodeIndices")) {
                    path.add(CompareConfigurationsTool.integer(index, "nodeIndices item", 0, Integer.MAX_VALUE));
                }
            }
            var snapshot = comparisons.require(id);
            var page = snapshot.details(objectIndex, path, offset, limit);
            return McpServer.textResult(CompareConfigurationsTool.page(id, snapshot, page, objectIndex, path, offset).toString());
        } catch (RuntimeException failure) {
            return McpServer.toolError("edt_comparison_details failed: " + failure.getMessage());
        }
    }
}
