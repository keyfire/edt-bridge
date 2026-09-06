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

/**
 * Whether a validation marker still describes the file it sits on.
 *
 * <p>A marker is a snapshot: EDT wrote it when it last validated the file, and nothing on it
 * says so. A file changed after that - saved with auto-build off, switched by git, written by a
 * script around the workspace - keeps its old markers, and a report built from them is a
 * plausible report about code that is gone. Until now the only way to find out was a clean with
 * a full rebuild: minutes on a large configuration, for one module.
 *
 * <p>Three facts settle it. The marker says when it was created; the disk says when the file was
 * last written; the workspace says whether it has read that write (a resource is synchronized
 * when the stamp it recorded matches the disk). The verdict:
 *
 * <ul>
 * <li>{@code stale} - the file changed after the marker was created, so the marker may describe
 *     code that is no longer there; whether the workspace noticed does not matter. A file the
 *     workspace has not read is stale by definition: no validation can have run over a change
 *     nobody has seen. So is a file that is gone from disk;</li>
 * <li>{@code unsynchronized} - the workspace has not read the file's last write. The stronger
 *     finding: not only are the markers old, the file may hold problems that nothing reports
 *     yet, because the checks never saw it.</li>
 * </ul>
 *
 * <p>Pure arithmetic - no EDT or Eclipse types - so the boundary cases live under test.
 */
public final class MarkerFreshness {

    private MarkerFreshness() {
    }

    /** The verdict on one marker against the file it sits on. */
    public static final class Verdict {
        /** The marker predates the file's last change, or the file is gone. */
        public final boolean stale;
        /** The file changed on disk and the workspace has not read the change. */
        public final boolean unsynchronized;

        Verdict(boolean stale, boolean unsynchronized) {
            this.stale = stale;
            this.unsynchronized = unsynchronized;
        }

        /** True when there is nothing to report about this marker. */
        public boolean current() {
            return !stale && !unsynchronized;
        }
    }

    /**
     * Judge one marker.
     *
     * @param markerCreatedAt when the marker was created, epoch milliseconds; {@code <= 0} when
     *                        the marker does not say
     * @param fileModifiedAt  the file's last write on disk, epoch milliseconds; {@code <= 0} when
     *                        the file is not there
     * @param workspaceInSync whether the workspace has read that write
     */
    public static Verdict judge(long markerCreatedAt, long fileModifiedAt, boolean workspaceInSync) {
        boolean unsynchronized = !workspaceInSync;
        if (fileModifiedAt <= 0) {
            // The file is gone: whatever the marker says, it describes nothing that exists.
            return new Verdict(true, unsynchronized);
        }
        boolean stale = unsynchronized || (markerCreatedAt > 0 && fileModifiedAt > markerCreatedAt);
        return new Verdict(stale, unsynchronized);
    }

    /**
     * The one line that tells the caller what to do about it, or {@code null} when nothing is
     * stale and nothing is unsynchronized.
     *
     * @param staleProblems       listed problems whose marker is stale
     * @param unsynchronizedFiles files the workspace has not read
     * @param afterRefresh        whether the report was taken right after a refresh - the advice
     *                            is then a full clean, not another refresh
     */
    public static String hint(int staleProblems, int unsynchronizedFiles, boolean afterRefresh) {
        if (staleProblems <= 0 && unsynchronizedFiles <= 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (staleProblems > 0) {
            sb.append(staleProblems).append(" problem(s) predate the last change of their file");
        }
        if (unsynchronizedFiles > 0) {
            if (sb.length() > 0) {
                sb.append(" and ");
            }
            sb.append(unsynchronizedFiles)
              .append(" file(s) changed on disk since the workspace last read them");
            if (staleProblems <= 0) {
                sb.append(" and may hold problems nothing reports yet");
            }
        }
        sb.append(afterRefresh
                ? " even after the refresh – edt_clean_project rebuilds the project from scratch"
                : " – pass refresh=true to revalidate");
        return sb.toString();
    }
}
