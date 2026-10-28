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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import io.github.keyfire.edtbridge.core.TreeRemoval;

/** Claims a new project under its workspace rule and proves ownership before rollback. */
final class ProjectCreationClaim {
    @FunctionalInterface interface Creation { void run() throws Exception; }
    private static final QualifiedName OWNER = new QualifiedName(
            "io.github.keyfire.edtbridge", "externalImportOwner");
    private final IWorkspace workspace;
    private final IProject project;
    private final Path directory;
    private final String token = UUID.randomUUID().toString();

    private ProjectCreationClaim(IWorkspace workspace, IProject project, Path directory) {
        this.workspace = workspace;
        this.project = project;
        this.directory = directory.toAbsolutePath().normalize();
    }

    static ProjectCreationClaim create(IWorkspace workspace, IProject project, Path directory,
            Creation creation) throws Exception {
        ProjectCreationClaim claim = new ProjectCreationClaim(workspace, project, directory);
        // EDT's project manager also acquires the root rule; a project-only outer rule cannot contain it.
        claim.exclusive(workspace.getRoot(), () -> {
            if (project.exists() || Files.exists(claim.directory)) {
                throw new IOException("the destination appeared since validation; nothing imported");
            }
            creation.run();
            if (!project.exists() || !claim.hasExpectedLocation()) {
                throw new IOException("native project creation did not establish the expected destination");
            }
            // A failed or partial native creation receives no ownership marker.
            project.setSessionProperty(OWNER, claim.token);
        });
        return claim;
    }

    boolean rollback() throws Exception {
        boolean[] removed = {false};
        exclusive(workspace.getRuleFactory().deleteRule(project), () -> {
            if (!project.exists() || !token.equals(project.getSessionProperty(OWNER))
                    || !hasExpectedLocation()) return;
            project.delete(true, true, new NullProgressMonitor());
            removed[0] = !project.exists() && TreeRemoval.delete(directory);
        });
        return removed[0];
    }

    private boolean hasExpectedLocation() {
        return project.getLocation() != null && directory.equals(
                project.getLocation().toFile().toPath().toAbsolutePath().normalize());
    }

    private void exclusive(ISchedulingRule rule, Creation operation) throws Exception {
        Exception[] failure = {null};
        workspace.run(monitor -> {
            try {
                operation.run();
            } catch (Exception exception) {
                failure[0] = exception;
            }
        }, rule, IWorkspace.AVOID_UPDATE, new NullProgressMonitor());
        if (failure[0] != null) throw failure[0];
    }
}
