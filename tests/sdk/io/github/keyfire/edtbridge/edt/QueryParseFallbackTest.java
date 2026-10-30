package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryParseFallbackTest {
    @Test
    void ordinaryDynamicListKeepsTheQlParser() throws Exception {
        assertEquals(List.of("Catalog.Items"), QueryUsageGateway.parseQuery("dynamicList", dcs -> {
            assertFalse(dcs, "a valid QL query must not be parsed twice");
            return List.of("Catalog.Items");
        }));
    }

    @Test
    void dynamicListRetriesDcsOnlyAfterSyntaxFailure() throws Exception {
        assertEquals(List.of("InformationRegister.Pending"), QueryUsageGateway.parseQuery("dynamicList", dcs -> {
            if (!dcs) throw new QueryUsageGateway.QuerySyntaxException("unexpected brace");
            return List.of("InformationRegister.Pending");
        }));
    }

    @Test
    void invalidDcsQueryStillFails() {
        IOException error = assertThrows(IOException.class, () ->
                QueryUsageGateway.parseQuery("dynamicList", dcs -> {
                    throw new QueryUsageGateway.QuerySyntaxException(dcs ? "invalid DCS" : "invalid QL");
                }));
        assertTrue(error.getMessage().contains("invalid DCS"));
        assertTrue(error.getMessage().contains("edt_search_queries"));
    }

    @Test
    void missingLanguageServiceIsNotTreatedAsSyntaxFailure() {
        IOException error = new IOException("language service unavailable");
        assertSame(error, assertThrows(IOException.class, () ->
                QueryUsageGateway.parseQuery("dynamicList", dcs -> { throw error; })));
    }

    @Test
    void dcsDatasetUsesDcsDirectly() throws Exception {
        assertEquals("Catalog.Items", QueryUsageGateway.parseQuery("dcs", dcs -> {
            assertTrue(dcs);
            return "Catalog.Items";
        }));
    }
}
