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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Whether a load can send only the files that changed. Every doubt must end in a full load. */
class LoadPlanTest {

    private static final String GENERATION = "99bf6cd4045c8f43a86244a0dab4f24000000000";
    private static final String MODULE = "CommonModules/Календарь/Ext/Module.bsl";

    /** An export of ten files, the way the last successful load left it. */
    private static LoadSnapshot loaded() {
        LoadSnapshot s = new LoadSnapshot();
        s.target = LoadSnapshot.target("/ssrv.example.test\\sm", null);
        s.platformVersion = "8.5.1.1529";
        s.generation = GENERATION;
        s.files.put(LoadPlan.ROOT, "0".repeat(64));
        s.files.put(LoadPlan.SUPPORT, "1".repeat(64));
        s.files.put(MODULE, "2".repeat(64));
        for (int i = 0; i < 7; i++) {
            s.files.put("Catalogs/Справочник" + i + ".xml", Integer.toHexString(3 + i).repeat(64));
        }
        return s;
    }

    /** The export about to be loaded: the same files, no generation of its own yet. */
    private static LoadSnapshot export() {
        LoadSnapshot s = loaded();
        s.generation = null;
        return s;
    }

    private static void change(LoadSnapshot s, String path) {
        s.files.put(path, "f".repeat(64));
    }

    @Test
    @DisplayName("one changed module is loaded on its own")
    void oneModule() {
        LoadSnapshot now = export();
        change(now, MODULE);
        LoadPlan plan = LoadPlan.decide(loaded(), now, GENERATION, false);
        assertEquals(LoadPlan.Mode.PARTIAL, plan.mode);
        assertEquals(List.of(MODULE), plan.changed);
        assertEquals(10, plan.total);
    }

    @Test
    @DisplayName("a new file is loaded with the changed ones")
    void newFile() {
        LoadSnapshot now = export();
        change(now, MODULE);
        change(now, "CommonModules/Календарь/Ext/ManagerModule.bsl");
        LoadPlan plan = LoadPlan.decide(loaded(), now, GENERATION, false);
        assertEquals(LoadPlan.Mode.PARTIAL, plan.mode);
        assertEquals(List.of("CommonModules/Календарь/Ext/ManagerModule.bsl", MODULE), plan.changed);
    }

    @Test
    @DisplayName("nothing changed and nobody touched the infobase: nothing to load")
    void nothingChanged() {
        LoadPlan plan = LoadPlan.decide(loaded(), export(), GENERATION, false);
        assertEquals(LoadPlan.Mode.SKIP, plan.mode);
        assertTrue(plan.changed.isEmpty());
    }

    @Test
    @DisplayName("asked for a full load, the caller gets one even when nothing changed")
    void fullRequested() {
        assertEquals(LoadPlan.Mode.FULL, LoadPlan.decide(loaded(), export(), GENERATION, true).mode);
    }

    @Test
    @DisplayName("no record of an earlier load: full")
    void noSnapshot() {
        assertEquals(LoadPlan.Mode.FULL, LoadPlan.decide(null, export(), GENERATION, false).mode);
    }

    @Test
    @DisplayName("a record of another infobase is not used")
    void otherTarget() {
        LoadSnapshot now = export();
        now.target = LoadSnapshot.target("/ssrv.example.test\\lib", null);
        assertEquals(LoadPlan.Mode.FULL, LoadPlan.decide(loaded(), now, GENERATION, false).mode);
    }

    @Test
    @DisplayName("another platform writes another export format: full")
    void platformChanged() {
        LoadSnapshot now = export();
        now.platformVersion = "8.5.1.1600";
        LoadPlan plan = LoadPlan.decide(loaded(), now, GENERATION, false);
        assertEquals(LoadPlan.Mode.FULL, plan.mode);
        assertTrue(plan.reason.contains("8.5.1.1529 -> 8.5.1.1600"), plan.reason);
    }

    @Test
    @DisplayName("the infobase changed since the last load - by anyone - so only a full load is safe")
    void generationMoved() {
        LoadSnapshot now = export();
        change(now, MODULE);
        LoadPlan plan = LoadPlan.decide(loaded(), now, "0".repeat(40), false);
        assertEquals(LoadPlan.Mode.FULL, plan.mode);
        assertTrue(plan.reason.contains("changed after this bridge last loaded it"), plan.reason);
    }

    @Test
    @DisplayName("an unknown generation, now or then, cannot rule out a change: full")
    void generationUnknown() {
        LoadSnapshot now = export();
        change(now, MODULE);
        assertEquals(LoadPlan.Mode.FULL, LoadPlan.decide(loaded(), now, null, false).mode);
        assertEquals(LoadPlan.Mode.FULL, LoadPlan.decide(loaded(), now, " ", false).mode);
        LoadSnapshot old = loaded();
        old.generation = null;
        assertEquals(LoadPlan.Mode.FULL, LoadPlan.decide(old, now, GENERATION, false).mode);
    }

    @Test
    @DisplayName("a file gone from the export is a removal, and a removal is not loaded file by file")
    void fileRemoved() {
        LoadSnapshot now = export();
        now.files.remove("Catalogs/Справочник0.xml");
        LoadPlan plan = LoadPlan.decide(loaded(), now, GENERATION, false);
        assertEquals(LoadPlan.Mode.FULL, plan.mode);
        assertTrue(plan.reason.startsWith("1 file(s) are gone"), plan.reason);
    }

    @Test
    @DisplayName("a changed root can take the configuration off vendor support when loaded alone: full")
    void rootChanged() {
        LoadSnapshot now = export();
        change(now, MODULE);
        change(now, LoadPlan.ROOT);
        assertEquals(LoadPlan.Mode.FULL, LoadPlan.decide(loaded(), now, GENERATION, false).mode);
    }

    @Test
    @DisplayName("changed vendor support settings: full")
    void supportChanged() {
        LoadSnapshot now = export();
        change(now, LoadPlan.SUPPORT);
        assertEquals(LoadPlan.Mode.FULL, LoadPlan.decide(loaded(), now, GENERATION, false).mode);
    }

    @Test
    @DisplayName("when most of the files changed a partial load gains nothing: full")
    void mostChanged() {
        LoadSnapshot now = export();
        for (int i = 0; i < 6; i++) {
            change(now, "Catalogs/Справочник" + i + ".xml");
        }
        LoadPlan plan = LoadPlan.decide(loaded(), now, GENERATION, false);
        assertEquals(LoadPlan.Mode.FULL, plan.mode);
        assertTrue(plan.reason.contains("6 of 10"), plan.reason);

        LoadSnapshot half = export();
        for (int i = 0; i < 5; i++) {
            change(half, "Catalogs/Справочник" + i + ".xml");
        }
        assertEquals(LoadPlan.Mode.PARTIAL, LoadPlan.decide(loaded(), half, GENERATION, false).mode,
                "exactly half is still partial");
    }

    @Test
    @DisplayName("the list file holds one path per line in the system's separators and no empty line")
    void listFile() {
        List<String> paths = List.of("CommonModules/Календарь/Ext/Module.bsl", "Catalogs/Товары.xml");
        assertEquals("CommonModules\\Календарь\\Ext\\Module.bsl\nCatalogs\\Товары.xml",
                LoadPlan.listFile(paths, '\\'));
        String unix = LoadPlan.listFile(paths, '/');
        assertEquals("CommonModules/Календарь/Ext/Module.bsl\nCatalogs/Товары.xml", unix);
        assertFalse(unix.endsWith("\n"), "a trailing break would be an empty line");
    }
}
