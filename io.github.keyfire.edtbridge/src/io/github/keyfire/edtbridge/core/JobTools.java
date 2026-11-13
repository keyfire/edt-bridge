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

import java.util.List;

/**
 * Which tools may run as a job, and the names of the job tools.
 *
 * <p>A tool runs as a job when its call passes {@code async=true}. Only the tools that can take longer
 * than a client waits for a call take the argument: a build, a load into an infobase, a dump, an
 * import, a validation of a whole project. A quick tool keeps its plain call.
 */
public final class JobTools {

    /** The tool that answers the state and the result of a job. */
    public static final String STATUS = "edt_job_status";
    /** The tool that cancels a job. */
    public static final String CANCEL = "edt_cancel_job";
    /** The argument that runs a call as a job. */
    public static final String ASYNC = "async";
    /**
     * How long a call with {@code async=true} waits before it answers with a job id. A call refused at
     * once or a plan of a dry run comes back as itself, without a job to poll.
     */
    public static final long INLINE_MILLIS = 1_500;

    /** The tools whose calls may take minutes, in the order of the tool list. */
    private static final List<String> LONG = List.of(
            "edt_project_errors",
            "edt_infobase_dump",
            "edt_update_database_config",
            "edt_dump_external_object",
            "edt_update_infobase",
            "edt_create_infobase",
            "edt_build_extension",
            "edt_clean_project");

    /** The description of the {@code async} argument. */
    public static final String ASYNC_DESCRIPTION = "true = run the call as a background job: the answer is a "
            + "jobId at once, and edt_job_status gives the state and, at the end, the same result the call "
            + "returns without it. Use it when the call may outlast the time your client waits for a tool; "
            + "edt_cancel_job stops the job. A call refused at once or a dry-run plan still answers directly.";

    private JobTools() {
    }

    /** Whether the tool takes {@code async=true}. */
    public static boolean runsAsJob(String tool) {
        return tool != null && LONG.contains(tool);
    }

    /** The tools that take {@code async=true}. */
    public static List<String> longTools() {
        return LONG;
    }
}
