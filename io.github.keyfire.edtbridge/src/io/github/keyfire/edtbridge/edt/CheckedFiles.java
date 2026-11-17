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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When EDT last finished checking a file the bridge wrote and waited for: a schema write records it once EDT
 * has taken the new file into its model and its checks have run. The problem report reads it to tell a marker
 * the new check did not renew from one that waits for a check. Kept for the life of the bridge only.
 */
final class CheckedFiles {
    private static final Map<String, Long> CHECKED = new ConcurrentHashMap<>();

    private CheckedFiles() { }

    static void record(String project, String path, long checkedAt) {
        if (project != null && path != null) CHECKED.merge(project + "/" + path, checkedAt, Math::max);
    }

    /** The end of the last check the bridge saw for this file, epoch ms; 0 when it saw none. */
    static long checkedAt(String project, String path) {
        if (project == null || path == null) return 0L;
        return CHECKED.getOrDefault(project + "/" + path, 0L);
    }
}
