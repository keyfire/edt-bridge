package io.github.keyfire.edtbridge.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.core.WriteScope;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriteScopeGateTest {
    /** Argument names that look like a file or directory; each must be classified in WriteScope. */
    private static final Pattern PATH_LIKE = Pattern.compile("^(path|file|folder).*|.*(Path|Paths|Dir|Directory|File|Folder)$");

    @TempDir
    Path out;

    @AfterEach
    void clearScope() {
        System.clearProperty(WriteScope.WRITE_PROPERTY);
        System.clearProperty(WriteScope.READ_PROPERTY);
        System.clearProperty(WriteScope.OUTPUT_PROPERTY);
    }

    private static Object invoke(String method, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i].getClass();
        }
        Method m = McpServer.class.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(McpServer.getInstance(), args);
    }

    private static JsonObject call(String tool, JsonObject arguments) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("name", tool);
        params.add("arguments", arguments);
        return (JsonObject) invoke("toolsCall", params);
    }

    private static String text(JsonObject result) {
        return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    @Test
    void everyPathLikeArgumentOfEveryToolIsClassified() throws Exception {
        JsonObject list = (JsonObject) invoke("toolsList");
        List<String> unclassified = new ArrayList<>();
        int tools = 0;
        for (JsonElement element : list.getAsJsonArray("tools")) {
            JsonObject tool = element.getAsJsonObject();
            String name = tool.get("name").getAsString();
            tools++;
            JsonObject properties = tool.getAsJsonObject("inputSchema").getAsJsonObject("properties");
            if (properties == null) continue;
            for (String argument : properties.keySet()) {
                if (PATH_LIKE.matcher(argument).matches() && !WriteScope.isClassifiedPath(name, argument)) {
                    unclassified.add(name + "." + argument);
                }
            }
        }
        assertTrue(tools > 80, "tools read: " + tools);
        assertEquals(List.of(), unclassified,
                "a path argument the write scope does not know: add it to OUTPUT_PATHS when the tool writes it, "
                        + "otherwise to OTHER_PATHS");
        // Every output argument the scope lists exists, so a renamed argument does not slip out of the check.
        for (var entry : WriteScope.OUTPUT_PATHS.entrySet()) {
            JsonObject tool = null;
            for (JsonElement element : list.getAsJsonArray("tools")) {
                if (entry.getKey().equals(element.getAsJsonObject().get("name").getAsString())) tool = element.getAsJsonObject();
            }
            assertNotNull(tool, entry.getKey());
            for (String argument : entry.getValue()) {
                assertTrue(tool.getAsJsonObject("inputSchema").getAsJsonObject("properties").has(argument),
                        entry.getKey() + "." + argument);
            }
        }
    }

    @Test
    void aCallOutsideTheScopeIsRefusedBeforeTheToolRuns() throws Exception {
        System.setProperty(WriteScope.WRITE_PROPERTY, "Own");
        System.setProperty(WriteScope.READ_PROPERTY, "Base");
        System.setProperty(WriteScope.OUTPUT_PROPERTY, out.toString());

        JsonObject write = new JsonObject();
        write.addProperty("projectName", "Base");
        write.addProperty("fqn", "CommonModule.Common");
        write.addProperty("methodText", "Procedure A() EndProcedure");
        JsonObject refused = call("edt_add_method", write);
        assertTrue(refused.get("isError").getAsBoolean());
        assertTrue(text(refused).contains("projects open for writing: Own"), text(refused));

        JsonObject dump = new JsonObject();
        dump.addProperty("projectName", "Own");
        dump.addProperty("objectName", "Own");
        dump.addProperty("targetPath", out.getParent().resolve("Own.epf").toString());
        assertTrue(text(call("edt_dump_external_object", dump)).contains("outside the output directory"));

        JsonObject read = new JsonObject();
        read.addProperty("projectName", "Other");
        read.addProperty("fqn", "Catalog.Items");
        assertTrue(text(call("edt_metadata_details", read)).contains("readable projects: Own, Base"));
    }

    @Test
    void theStatusShowsTheScope() throws Exception {
        JsonObject inactive = (JsonObject) invoke("scopeStatus");
        assertFalse(inactive.get("active").getAsBoolean());
        System.setProperty(WriteScope.WRITE_PROPERTY, "Own");
        System.setProperty(WriteScope.OUTPUT_PROPERTY, out.toString());
        JsonObject status = (JsonObject) invoke("scopeStatus");
        assertTrue(status.get("active").getAsBoolean());
        assertEquals("Own", status.getAsJsonArray("writeProjects").get(0).getAsString());
        assertEquals(out.toString(), status.get("outputDir").getAsString());
    }
}
