package io.github.keyfire.edtbridge.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

class McpAuthenticationTest {
    @Test
    void missingTokenReturnsActionableJsonAtBothProtectedEndpoints() throws Exception {
        System.setProperty("edt.bridge.token", "test-only-token");
        HttpServer listener = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            McpServer bridge = McpServer.getInstance();
            for (String endpoint : new String[] {"/mcp", "/shutdown"}) {
                var handler = McpServer.class.getDeclaredMethod(
                        endpoint.equals("/mcp") ? "handle" : "handleShutdown",
                        com.sun.net.httpserver.HttpExchange.class);
                handler.setAccessible(true);
                listener.createContext(endpoint, exchange -> {
                    try {
                        handler.invoke(bridge, exchange);
                    } catch (ReflectiveOperationException failure) {
                        throw new RuntimeException(failure);
                    }
                });
            }
            listener.start();
            HttpClient client = HttpClient.newHttpClient();
            for (String endpoint : new String[] {"/mcp", "/shutdown"}) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + listener.getAddress().getPort() + endpoint))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(401, response.statusCode());
                var error = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("error");
                assertNotNull(error, endpoint + " must explain authentication failure");
                assertTrue(error.get("message").getAsString().contains("EDT_BRIDGE_TOKEN"));
                assertFalse(response.body().contains("test-only-token"));
                var wrongToken = HttpRequest.newBuilder(request.uri())
                        .header("Authorization", "Bearer wrong-token")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build();
                var rejected = client.send(wrongToken, HttpResponse.BodyHandlers.ofString());
                assertEquals(401, rejected.statusCode());
                assertTrue(rejected.body().contains("EDT_BRIDGE_TOKEN"));
            }
            var allowed = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                    + listener.getAddress().getPort() + "/mcp"))
                    .header("Authorization", "Bearer test-only-token")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                    .build();
            var accepted = client.send(allowed, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, accepted.statusCode());
            assertTrue(JsonParser.parseString(accepted.body()).getAsJsonObject().has("result"));
        } finally {
            listener.stop(0);
            System.clearProperty("edt.bridge.token");
        }
    }
}
