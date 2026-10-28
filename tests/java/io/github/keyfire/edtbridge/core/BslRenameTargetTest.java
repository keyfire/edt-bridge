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
package io.github.keyfire.edtbridge.core;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class BslRenameTargetTest {
    @Test void fullAliasSeparatesTheLastSegmentOfNestedOwners() {
        var target = BslRenameTarget.resolve(null, null, "DataProcessor.Demo.Form.Main.Run");
        assertEquals("DataProcessor.Demo.Form.Main", target.owner());
        assertEquals("Run", target.methodName());
    }
    @Test void shortAliasUsesExplicitOwnerAndAllowsMatchingAliases() {
        assertEquals("Run", BslRenameTarget.resolve("CommonModule.Api", null, "Run").methodName());
        assertEquals("Run", BslRenameTarget.resolve("CommonModule.Api", "Run", "CommonModule.Api.run").methodName());
        assertNull(BslRenameTarget.resolve("Catalog.Customers", null, null));
    }
    @Test void refusesConflictingOwnerNameAndEmptyAlias() {
        assertThrows(IllegalArgumentException.class, () -> BslRenameTarget.resolve("CommonModule.Other", null, "CommonModule.Api.Run"));
        assertThrows(IllegalArgumentException.class, () -> BslRenameTarget.resolve("CommonModule.Api", "Other", "Run"));
        assertThrows(IllegalArgumentException.class, () -> BslRenameTarget.resolve(null, null, ""));
        assertThrows(IllegalArgumentException.class, () -> BslRenameTarget.resolve(null, null, "CommonModule.Api."));
    }
}
