package io.github.keyfire.edtbridge.tools;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.runtime.OperationCanceledException;

import com._1c.g5.v8.dt.compare.matching.MatchingStrategy;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.keyfire.edtbridge.core.ConfigurationComparison;
import io.github.keyfire.edtbridge.core.ConfigurationComparison.Page;
import io.github.keyfire.edtbridge.edt.ConfigurationComparisonGateway;
import io.github.keyfire.edtbridge.edt.ConfigurationComparisonGateway.Source;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Starts native comparisons and pages their immutable results. */
public final class CompareConfigurationsTool {
    private static final Logger LOG = Logger.getLogger(CompareConfigurationsTool.class.getName());
    private static final Gson GSON = new Gson();
    private static final int MAX_RESULTS = 4;
    private static final long KEEP_NANOS = Duration.ofMinutes(30).toNanos();
    private record Stored(ConfigurationComparison result, long created) { }

    private final ConfigurationComparisonGateway gateway = new ConfigurationComparisonGateway();
    private final LinkedHashMap<String, Stored> results = new LinkedHashMap<>();

    public String name() { return "edt_compare_configurations"; }

    public JsonObject descriptor() {
        JsonObject props = pageProperties();
        props.add("main", sourceProperty("Original side of the comparison."));
        props.add("other", sourceProperty("New side of the comparison."));
        props.add("ancestor", sourceProperty("Optional common ancestor for a three-way comparison."));
        var matching = ToolJson.strProp("Matching strategy, default UUID_THEN_NAME.");
        var choices = new JsonArray();
        for (var strategy : MatchingStrategy.values()) choices.add(strategy.name());
        matching.add("enum", choices);
        props.add("matching", matching);
        props.add("waitSeconds", boundedProperty("Comparison timeout, default 300.", 1, 1800));
        props.add("comparisonId", ToolJson.strProp("Use a previous result for another page. Omit the sources."));
        return ToolJson.descriptor(name(),
                "READ: compare EDT configurations using EDT's native comparison. Each side is an open "
                + "configuration project, optionally at a local Git revision. Returns counts and a page of "
                + "changed objects. Use comparisonId for more pages or edt_comparison_details for object details.",
                "Сравнить конфигурации штатным механизмом EDT. Сторона задается открытым проектом конфигурации "
                + "и необязательной локальной ревизией Git. Результат содержит счетчики и страницу измененных "
                + "объектов. Для следующих страниц передайте comparisonId. Детали возвращает edt_comparison_details.",
                props);
    }

    public JsonObject call(JsonObject args) {
        try {
            int offset = integer(args, "offset", 0, 0, Integer.MAX_VALUE);
            int limit = integer(args, "limit", 50, 1, 200);
            String id = ToolJson.getStr(args, "comparisonId");
            ConfigurationComparison comparison;
            if (id == null) {
                Source main = source(args, "main", true);
                Source other = source(args, "other", true);
                Source ancestor = source(args, "ancestor", false);
                String matching = ToolJson.getStr(args, "matching");
                var strategy = matching == null ? MatchingStrategy.UUID_THEN_NAME
                        : MatchingStrategy.valueOf(matching.toUpperCase(Locale.ROOT));
                comparison = gateway.compare(main, other, ancestor, strategy, integer(args, "waitSeconds", 300, 1, 1800));
                id = remember(comparison);
            } else {
                if (args.has("main") || args.has("other") || args.has("ancestor")
                        || args.has("matching") || args.has("waitSeconds")) {
                    throw new IllegalArgumentException("Pass comparisonId with pagination only");
                }
                comparison = require(id);
            }
            JsonObject answer = page(id, comparison, comparison.objects(offset, limit), -1, List.of(), offset);
            answer.add("summary", GSON.toJsonTree(comparison.summary()));
            return McpServer.textResult(GSON.toJson(answer));
        } catch (OperationCanceledException cancelled) {
            return McpServer.toolError("Configuration comparison cancelled");
        } catch (Exception failure) {
            LOG.log(Level.WARNING, "Configuration comparison failed", failure);
            Throwable cause = failure.getCause();
            return McpServer.toolError("edt_compare_configurations failed: " + failure
                    + (cause == null ? "" : " (" + cause.getMessage() + ")"));
        }
    }

    synchronized ConfigurationComparison require(String id) {
        forgetExpired();
        Stored stored = results.get(id);
        if (stored == null) throw new IllegalArgumentException("Unknown or expired comparisonId; run the comparison again");
        return stored.result();
    }

    private synchronized String remember(ConfigurationComparison result) {
        forgetExpired();
        if (results.size() == MAX_RESULTS) {
            Iterator<String> iterator = results.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
        String id = UUID.randomUUID().toString();
        results.put(id, new Stored(result, System.nanoTime()));
        return id;
    }

    private void forgetExpired() {
        long now = System.nanoTime();
        results.values().removeIf(stored -> now - stored.created() >= KEEP_NANOS);
    }

    private static JsonObject sourceProperty(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("description", description);
        JsonObject fields = new JsonObject();
        fields.add("projectName", ToolJson.strProp("Open EDT configuration project."));
        fields.add("revision", ToolJson.strProp("Local Git ref or commit. Omit to read the live project."));
        schema.add("properties", fields);
        JsonArray required = new JsonArray();
        required.add("projectName");
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static Source source(JsonObject args, String key, boolean required) {
        if (!args.has(key) && !required) return null;
        if (!args.has(key) || !args.get(key).isJsonObject()) {
            throw new IllegalArgumentException(key + " must be a comparison source");
        }
        JsonObject source = args.getAsJsonObject(key);
        String projectName = ToolJson.getStr(source, "projectName");
        String revision = ToolJson.getStr(source, "revision");
        if (projectName == null || projectName.isBlank() || revision != null && revision.isBlank()) {
            throw new IllegalArgumentException(key + " requires projectName and a nonempty revision if supplied");
        }
        if (source.keySet().stream().anyMatch(field -> !field.equals("projectName") && !field.equals("revision"))) {
            throw new IllegalArgumentException(key + " supports only projectName and revision");
        }
        return new Source(projectName, revision);
    }

    static JsonObject pageProperties() {
        JsonObject props = new JsonObject();
        props.add("offset", boundedProperty("Zero-based page offset, default 0.", 0, Integer.MAX_VALUE));
        props.add("limit", boundedProperty("Page size, default 50.", 1, 200));
        return props;
    }

    static JsonObject boundedProperty(String description, int min, int max) {
        JsonObject property = ToolJson.intProp(description);
        property.addProperty("minimum", min);
        property.addProperty("maximum", max);
        return property;
    }

    static int integer(JsonObject args, String field, int defaultValue, int min, int max) {
        return args.has(field) ? integer(args.get(field), field, min, max) : defaultValue;
    }

    static int integer(JsonElement element, String field, int min, int max) {
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            try {
                int result = element.getAsBigDecimal().intValueExact();
                if (result >= min && result <= max) return result;
            } catch (ArithmeticException ignored) {
                // A fractional value or integer overflow is rejected below.
            }
        }
        throw new IllegalArgumentException(field + " must be an integer between " + min + " and " + max);
    }

    static JsonObject page(String id, ConfigurationComparison snapshot, Page page,
            int objectIndex, List<Integer> path, int offset) {
        var answer = new JsonObject();
        answer.addProperty("comparisonId", id);
        answer.addProperty("total", page.total());
        answer.addProperty("offset", offset);
        if (page.nextOffset() != null) answer.addProperty("nextOffset", page.nextOffset());
        var items = new JsonArray();
        int index = offset;
        for (var change : page.items()) {
            JsonObject item = GSON.toJsonTree(change).getAsJsonObject();
            item.remove("children");
            int current = index++;
            item.addProperty("index", current);
            if (objectIndex < 0) {
                item.addProperty("hasDetails", snapshot.hasDetails(current, path));
            } else {
                var childPath = new ArrayList<>(path);
                childPath.add(current);
                item.addProperty("hasDetails", snapshot.hasDetails(objectIndex, childPath));
            }
            items.add(item);
        }
        answer.add("items", items);
        return answer;
    }
}
