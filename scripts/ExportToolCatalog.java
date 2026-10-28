import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Export the real descriptors without starting the HTTP server or an EDT instance. */
public final class ExportToolCatalog {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("io.github.keyfire.edtbridge.mcp.McpServer");
        var method = type.getDeclaredMethod("toolsList");
        method.setAccessible(true);
        Object server = type.getMethod("getInstance").invoke(null);
        Files.writeString(Path.of(args[0]), method.invoke(server).toString(), StandardCharsets.UTF_8);
    }
}
