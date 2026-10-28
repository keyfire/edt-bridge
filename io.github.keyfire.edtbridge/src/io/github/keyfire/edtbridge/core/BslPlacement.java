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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Safe insertion placement based on parsed method and region ranges. */
public final class BslPlacement {
    private BslPlacement() {}
    public record Region(String name, int start, int bodyStart, int bodyEnd) {}
    public record Member(String name, int start, int end) {}
    public record Point(int offset, String previousMethod, String nextMethod) {}

    public static Point choose(int textEnd, List<Region> regions, List<Member> methods,
            int scopeStart, int scopeEnd, String region, String before, String after) {
        if (before != null && after != null) {
            throw new IllegalArgumentException("beforeMethod and afterMethod are mutually exclusive");
        }
        Region selected = null;
        if (region != null) {
            String[] segments = region.split("/", -1);
            for (String segment : segments) {
                if (segment.isBlank() || !segment.equals(segment.trim())) {
                    throw new IllegalArgumentException("invalid region path: " + region);
                }
            }
            List<Region> matches = new ArrayList<>();
            for (Region candidate : regions) {
                if (candidate.start() < scopeStart || candidate.bodyEnd() > scopeEnd) continue;
                String path = path(candidate, regions);
                if (segments.length == 1 ? candidate.name().equalsIgnoreCase(region)
                        : path.equalsIgnoreCase(region)) matches.add(candidate);
            }
            if (matches.size() != 1) {
                throw new IllegalArgumentException((matches.isEmpty() ? "region not found: "
                        : "ambiguous region: ") + region);
            }
            selected = matches.get(0);
        }
        List<Member> candidates = new ArrayList<>();
        for (Member member : methods) {
            if (member.start() < scopeStart || member.end() > scopeEnd) continue;
            if (selected != null && innermost(member, regions) != selected) continue;
            candidates.add(member);
        }
        candidates.sort(Comparator.comparingInt(Member::start));
        String anchorName = before != null ? before : after;
        if (anchorName != null) {
            List<Member> matches = candidates.stream().filter(m -> anchorName.equalsIgnoreCase(m.name())).toList();
            if (matches.size() != 1) throw new IllegalArgumentException((matches.isEmpty()
                    ? "method anchor not found in selected scope: " : "ambiguous method anchor: ") + anchorName);
            Member anchor = matches.get(0);
            int index = candidates.indexOf(anchor) + (before != null ? 0 : 1);
            return new Point(before != null ? anchor.start() : anchor.end(),
                    index > 0 ? candidates.get(index - 1).name() : null,
                    index < candidates.size() ? candidates.get(index).name() : null);
        }
        if (!candidates.isEmpty()) {
            Member last = candidates.get(candidates.size() - 1);
            return new Point(last.end(), last.name(), null);
        }
        return new Point(selected != null ? selected.bodyStart() : scopeStart == 0 && scopeEnd == textEnd
                ? textEnd : scopeStart, null, null);
    }

    private static Region innermost(Member member, List<Region> regions) {
        Region inner = null;
        for (Region candidate : regions) {
            if (candidate.bodyStart() <= member.start() && member.end() <= candidate.bodyEnd()
                    && (inner == null || candidate.start() > inner.start())) inner = candidate;
        }
        return inner;
    }

    private static String path(Region region, List<Region> regions) {
        Region parent = null;
        for (Region candidate : regions) {
            if (candidate.start() < region.start() && candidate.bodyEnd() > region.bodyEnd()
                    && (parent == null || candidate.start() > parent.start())) parent = candidate;
        }
        return parent == null ? region.name() : path(parent, regions) + "/" + region.name();
    }
}
