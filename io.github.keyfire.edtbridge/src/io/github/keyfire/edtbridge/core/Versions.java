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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ordering of dotted version literals ("8.5.1", "8.3.21") by their digit groups.
 *
 * <p>Pure string work, kept out of the gateways so it can be tested without EDT. The literals come
 * from platform enums (compatibility modes) and platform directories, where a string comparison
 * lies as soon as a group reaches two digits ("8.10" would sort before "8.5").
 */
public final class Versions {

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private Versions() {
    }

    /**
     * The digit groups of a version literal, e.g. {@code "8.5.1"} to {@code {8, 5, 1}}. A literal
     * without digits ({@code "DontUse"}, {@code null}) gives an empty array - and so does one whose
     * group overflows int, because such a string is not a version this ordering is meant for.
     */
    public static int[] numbers(String literal) {
        if (literal == null) {
            return new int[0];
        }
        List<Integer> groups = new ArrayList<>();
        Matcher m = DIGITS.matcher(literal);
        while (m.find()) {
            try {
                groups.add(Integer.parseInt(m.group()));
            } catch (NumberFormatException e) {
                return new int[0];
            }
        }
        int[] r = new int[groups.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = groups.get(i);
        }
        return r;
    }

    /**
     * Order two version literals by their digit groups, the shorter side padded with zeros - so
     * "8.5" is below "8.5.1", and "8.10" is above "8.5". A literal without digits sorts below
     * every literal that has them.
     */
    public static int compare(String a, String b) {
        int[] x = numbers(a);
        int[] y = numbers(b);
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = (i < x.length) ? x[i] : 0;
            int yi = (i < y.length) ? y[i] : 0;
            if (xi != yi) {
                return Integer.compare(xi, yi);
            }
        }
        return 0;
    }
}
