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
package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceRuleFactory;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.runtime.QualifiedName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectCreationClaimTest {
    @TempDir Path temporary;

    @Test void rechecksDestinationUnderProjectRuleAndPreservesRacingForeignProject() throws Exception {
        Fixture fixture = new Fixture(temporary.resolve("Target"));
        fixture.beforeRule = () -> fixture.foreign("foreign project");
        assertThrows(Exception.class, () -> fixture.claim());
        assertEquals(0, fixture.createCalls);
        assertEquals(0, fixture.deleteCalls);
        assertEquals("foreign project", Files.readString(fixture.directory.resolve("owner.txt")));
        assertEquals(1, fixture.ruleRuns);
    }

    @Test void failedCreateDoesNotMarkAnUnprovenPartialProjectAsOwned() throws Exception {
        Fixture fixture = new Fixture(temporary.resolve("Target"));
        assertThrows(IOException.class, () -> ProjectCreationClaim.create(
                fixture.workspace, fixture.project, fixture.directory, () -> {
                    assertTrue(fixture.ruleHeld, "native create must run under the project rule");
                    fixture.foreign("unproven partial creation");
                    throw new IOException("native create failed");
                }));
        assertTrue(fixture.properties.isEmpty());
        assertEquals(0, fixture.deleteCalls);
        assertEquals("unproven partial creation", Files.readString(fixture.directory.resolve("owner.txt")));
    }

    @Test void successfulClaimRollsBackItsOwnProjectUnderTheSameRule() throws Exception {
        Fixture fixture = new Fixture(temporary.resolve("Target"));
        ProjectCreationClaim claim = fixture.claim();
        assertFalse(fixture.properties.isEmpty(), "successful native create records ownership");
        assertTrue(claim.rollback());
        assertEquals(1, fixture.deleteCalls);
        assertFalse(fixture.exists);
        assertFalse(Files.exists(fixture.directory));
        assertEquals(2, fixture.ruleRuns);
    }

    @Test void nativeCreateCanAcquireItsNestedRootRuleWithoutUpgrade() throws Exception {
        Fixture fixture = new Fixture(temporary.resolve("Target"));
        ProjectCreationClaim claim = ProjectCreationClaim.create(
                fixture.workspace, fixture.project, fixture.directory, () -> {
                    fixture.workspace.run(monitor -> {
                        fixture.exists = true;
                        try {
                            Files.createDirectory(fixture.directory);
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    }, fixture.root, IWorkspace.AVOID_UPDATE, null);
                });
        assertTrue(claim.rollback());
        assertEquals(3, fixture.ruleRuns);
    }

    @Test void rollbackPreservesAProjectReplacedByAnotherOperation() throws Exception {
        Fixture fixture = new Fixture(temporary.resolve("Target"));
        ProjectCreationClaim claim = fixture.claim();
        fixture.properties.clear();
        fixture.foreign("replacement project");
        assertFalse(claim.rollback());
        assertEquals(0, fixture.deleteCalls);
        assertEquals("replacement project", Files.readString(fixture.directory.resolve("owner.txt")));
    }

    @Test void rollbackRefusesAProjectWhoseLocationChanged() throws Exception {
        Fixture fixture = new Fixture(temporary.resolve("Target"));
        ProjectCreationClaim claim = fixture.claim();
        fixture.location = temporary.resolve("Different");
        assertFalse(claim.rollback());
        assertEquals(0, fixture.deleteCalls);
        assertTrue(Files.exists(fixture.directory));
    }

    private static final class Fixture {
        final Path directory;
        Path location;
        boolean exists;
        boolean ruleHeld;
        int ruleRuns;
        int createCalls;
        int deleteCalls;
        final Map<QualifiedName, Object> properties = new HashMap<>();
        Checked beforeRule = () -> {};
        IProject project;
        IWorkspace workspace;
        IWorkspaceRoot root;
        IResourceRuleFactory rules;
        Object currentRule;

        Fixture(Path directory) {
            this.directory = directory;
            this.location = directory;
            project = (IProject) Proxy.newProxyInstance(IProject.class.getClassLoader(),
                    new Class<?>[] {IProject.class}, (proxy, method, arguments) -> switch (method.getName()) {
                        case "exists" -> exists;
                        case "getLocation" -> org.eclipse.core.runtime.Path.fromOSString(location.toString());
                        case "getSessionProperty" -> properties.get(arguments[0]);
                        case "setSessionProperty" -> {
                            assertTrue(ruleHeld, "ownership must be recorded under the project rule");
                            properties.put((QualifiedName) arguments[0], arguments[1]);
                            yield null;
                        }
                        case "delete" -> {
                            assertTrue(ruleHeld, "rollback must hold its delete rule");
                            assertSame(project, currentRule, "rollback uses the rule factory's delete rule");
                            deleteCalls++;
                            exists = false;
                            properties.clear();
                            yield null;
                        }
                        case "toString" -> "Target";
                        default -> method.getReturnType() == boolean.class ? false : null;
                    });
            root = (IWorkspaceRoot) Proxy.newProxyInstance(IWorkspaceRoot.class.getClassLoader(),
                    new Class<?>[] {IWorkspaceRoot.class}, (proxy, method, arguments) -> null);
            rules = (IResourceRuleFactory) Proxy.newProxyInstance(IResourceRuleFactory.class.getClassLoader(),
                    new Class<?>[] {IResourceRuleFactory.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("deleteRule")) {
                            assertSame(project, arguments[0]);
                            return project;
                        }
                        return null;
                    });
            workspace = (IWorkspace) Proxy.newProxyInstance(IWorkspace.class.getClassLoader(),
                    new Class<?>[] {IWorkspace.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("getRoot")) return root;
                        if (method.getName().equals("getRuleFactory")) return rules;
                        if (method.getName().equals("run")) {
                            boolean previousHeld = ruleHeld;
                            Object previousRule = currentRule;
                            if (previousHeld) assertSame(previousRule, arguments[1], "nested native rule is contained");
                            else beforeRule.run();
                            ruleRuns++;
                            ruleHeld = true;
                            currentRule = arguments[1];
                            try {
                                ((IWorkspaceRunnable) arguments[0]).run(null);
                            } finally {
                                ruleHeld = previousHeld;
                                currentRule = previousRule;
                            }
                        }
                        return method.getReturnType() == boolean.class ? false : null;
                    });
        }

        ProjectCreationClaim claim() throws Exception {
            return ProjectCreationClaim.create(workspace, project, directory, () -> {
                assertTrue(ruleHeld, "native create must run under the workspace rule");
                assertSame(root, currentRule, "native manager requires the root creation rule");
                createCalls++;
                if (exists) throw new IOException("foreign target exists");
                exists = true;
                Files.createDirectory(directory);
            });
        }

        void foreign(String text) throws IOException {
            exists = true;
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("owner.txt"), text);
        }
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
