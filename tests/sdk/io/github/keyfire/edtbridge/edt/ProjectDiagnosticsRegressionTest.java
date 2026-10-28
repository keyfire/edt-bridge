package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectDiagnosticsRegressionTest {
    @Test
    void exactModulePathCountsANewDiskFileBeforeRefresh() {
        ProjectGateway.UnseenFiles files = new ProjectGateway.UnseenFiles();
        files.noteNew("src/CommonModules/Orders/Module.bsl",
                "src/CommonModules/Orders/Module.bsl", List.of());
        assertEquals(1, files.count);
        assertEquals(List.of("src/CommonModules/Orders/Module.bsl (new on disk)"), files.paths);
    }

    @Test
    void exactModulePathExcludesANeighboringNewFile() {
        ProjectGateway.UnseenFiles files = new ProjectGateway.UnseenFiles();
        files.noteNew("src/CommonModules/OrdersCopy/Module.bsl",
                "src/CommonModules/Orders/Module.bsl", List.of());
        assertEquals(0, files.count);
    }
}
