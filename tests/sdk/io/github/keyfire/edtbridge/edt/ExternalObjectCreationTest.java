package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.stream.StreamSupport;
import org.eclipse.emf.ecore.EClass;
import org.junit.jupiter.api.Test;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalReport;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.version.Version;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.keyfire.edtbridge.tools.CreateExternalObjectTool;

class ExternalObjectCreationTest {
    @Test void kindIsOptionalAndOffersProcessorOrReport() {
        JsonObject schema = new CreateExternalObjectTool().descriptor().getAsJsonObject("inputSchema");
        JsonObject kind = schema.getAsJsonObject("properties").getAsJsonObject("kind");
        assertNotNull(kind, "the public tool must accept the external object kind");
        assertEquals("string", kind.get("type").getAsString());
        assertEquals(List.of("processor", "report"),
                StreamSupport.stream(kind.getAsJsonArray("enum").spliterator(), false)
                        .map(v -> v.getAsString()).toList());
        assertFalse(StreamSupport.stream(schema.getAsJsonArray("required").spliterator(), false)
                .anyMatch(v -> "kind".equals(v.getAsString())));
    }

    @Test void legacyOverloadKeepsProcessorWhenNameValidationFails() {
        var result = new MetadataWriteGateway().createExternalObject("invalid name", null, null, false);
        JsonObject json = new Gson().toJsonTree(result).getAsJsonObject();
        assertEquals("processor", string(json, "kind"));
        assertEquals("ExternalDataProcessor", string(json, "objectType"));
        assertFalse(result.ok);
        assertFalse(result.applied);
    }

    @Test void unknownKindFailsBeforeAccessingTheWorkspace() {
        JsonObject args = new JsonObject();
        args.addProperty("name", "SampleReport");
        args.addProperty("kind", "template");
        args.addProperty("apply", true);
        JsonObject response = new CreateExternalObjectTool().call(args);
        assertFalse(response.has("isError") && response.get("isError").getAsBoolean(),
                "kind validation must finish without a workspace");
        JsonObject result = JsonParser.parseString(response.getAsJsonArray("content")
                .get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();
        assertFalse(result.get("ok").getAsBoolean());
        assertFalse(result.get("applied").getAsBoolean());
        assertTrue(result.get("message").getAsString().contains("kind"));
    }

    @Test void reportKindSelectsTheReportTypeBeforeNameValidation() {
        JsonObject args = new JsonObject();
        args.addProperty("name", "invalid name");
        args.addProperty("kind", "report");
        JsonObject response = new CreateExternalObjectTool().call(args);
        JsonObject result = JsonParser.parseString(response.getAsJsonArray("content")
                .get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();
        assertEquals("report", string(result, "kind"));
        assertEquals("ExternalReport", string(result, "objectType"));
        assertFalse(result.get("applied").getAsBoolean());
    }

    @Test void reportRootIsFreshNamedAndUsesTheRequestedMetadataClass() throws Exception {
        IModelObjectFactory factory = metadataFactory();
        MdObject first = root(factory, "ExternalReport", "SampleReport");
        MdObject second = root(factory, "ExternalReport", "SampleReport");
        assertInstanceOf(ExternalReport.class, first);
        assertEquals("SampleReport", first.getName());
        assertNull(first.eContainer());
        assertNotSame(first, second);
    }

    @Test void processorRootKeepsTheOriginalMetadataClass() throws Exception {
        MdObject root = root(metadataFactory(), "ExternalDataProcessor", "SampleProcessor");
        assertInstanceOf(ExternalDataProcessor.class, root);
        assertEquals("SampleProcessor", root.getName());
    }

    @Test void wrongFactoryResultCannotBecomeAReportProject() {
        IModelObjectFactory factory = (IModelObjectFactory) Proxy.newProxyInstance(
                IModelObjectFactory.class.getClassLoader(), new Class<?>[] {IModelObjectFactory.class},
                (proxy, method, args) -> MdClassFactory.eINSTANCE.createExternalDataProcessor());
        assertThrows(IllegalStateException.class, () -> root(factory, "ExternalReport", "SampleReport"));
    }

    private static String string(JsonObject json, String name) {
        assertTrue(json.has(name), "missing result field: " + name);
        return json.get(name).getAsString();
    }

    private static IModelObjectFactory metadataFactory() {
        // The injected EDT factory requires OSGi. Keep real SDK metadata objects at that boundary.
        return (IModelObjectFactory) Proxy.newProxyInstance(IModelObjectFactory.class.getClassLoader(),
                new Class<?>[] {IModelObjectFactory.class}, (proxy, method, args) -> {
                    assertEquals("create", method.getName());
                    assertEquals(2, args.length);
                    assertSame(Version.LATEST, args[1]);
                    return MdClassFactory.eINSTANCE.create((EClass) args[0]);
                });
    }

    private static MdObject root(IModelObjectFactory factory, String type, String name) throws Exception {
        Method method = assertDoesNotThrow(() -> MetadataWriteGateway.class.getDeclaredMethod(
                "newExternalObjectRoot", IModelObjectFactory.class, String.class, Version.class, String.class));
        method.setAccessible(true);
        try {
            return (MdObject) method.invoke(null, factory, type, Version.LATEST, name);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException) throw (RuntimeException) failure.getCause();
            throw failure;
        }
    }
}
