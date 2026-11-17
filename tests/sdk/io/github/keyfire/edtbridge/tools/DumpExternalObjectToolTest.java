/*
 * edt-bridge - a 1C:EDT bridge that exposes the live EDT model over MCP.
 * Copyright 2026 edt-bridge contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.keyfire.edtbridge.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.keyfire.edtbridge.edt.MetadataWriteGateway;

class DumpExternalObjectToolTest {
    /** What EDT's own dumper says about a project with no infobase, about 1.4 thousand characters in a session. */
    private static final String REFUSAL = "There are no developing infobase applications for the project "
            + "'Probe'. Associate an infobase with the project and try again. ".repeat(20);

    private static MetadataWriteGateway.DumpExternalObjectResult disk(boolean ok) {
        var result = new MetadataWriteGateway.DumpExternalObjectResult();
        result.ok = ok;
        result.applied = ok;
        result.project = "Probe";
        result.fqn = "ExternalReport.Probe";
        result.targetPath = "C:/out/Probe.erf";
        result.route = "auto";
        result.method = ok ? "disk:8.5.1.1529" : null;
        result.nativeError = REFUSAL;
        result.message = ok ? "dumped ExternalReport.Probe using the on-disk platform (8.5.1.1529; EDT's own dumper "
                + "refused first)" : "dump failed via the on-disk platform: exit code 1";
        return result;
    }

    @Test
    void aBuildThatSucceededNamesItsRouteAndNotTheRefusalBeforeIt() {
        var answer = DumpExternalObjectTool.answer(disk(true));
        assertFalse(answer.has("nativeError"));
        assertEquals("disk:8.5.1.1529", answer.get("method").getAsString());
        assertEquals("auto", answer.get("route").getAsString());
        assertTrue(answer.toString().length() < 600, answer.toString());
    }

    @Test
    void aBuildThatFailedKeepsWhatEdtSaid() {
        var answer = DumpExternalObjectTool.answer(disk(false));
        assertEquals(REFUSAL, answer.get("nativeError").getAsString());
    }
}
